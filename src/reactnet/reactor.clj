(ns reactnet.reactor
  "Factories and combinators for FRP style behaviors and eventstreams."
  (:refer-clojure :exclude [concat count delay distinct drop drop-while
                            drop-last filter into merge map mapcat reduce
                            remove some swap! take take-last take-while])
  (:require [clojure.core :as c]
            [clojure.string :as s]
            [reactnet.scheduler :as sched]
            [reactnet.reactives]
            [reactnet.executors]
            [reactnet.core :as rn
             :refer [add-links! broadcast-value completed? default-link-fn
                     enq enqueue-values execute fvalue link-inputs
                     link-outputs make-link make-network *netref* now on-error
                     reactive? remove-links! single-value values zip-values]
             :exclude [push! complete!]]
            [reactnet.netrefs :refer [agent-netref]])
  (:import [clojure.lang PersistentQueue]
           [reactnet.reactives Behavior Eventstream Seqstream Fnbehavior]
           [reactnet.executors FutureExecutor]))


;; TODOS
;; - Remove the mandatory label from the behavior/eventstream factories
;; - How to prevent scheduled tasks from accumulating?
;; - Invent marker protocol to support behavior? and eventstream? regardless of impl class


;; ===========================================================================
;; EXPERIMENTAL NEW REACTOR API IMPL


(alter-var-root #'reactnet.core/*netref*
                (fn [_]
                  (agent-netref (make-network "default" []))))

(defonce ^:no-doc scheduler (sched/scheduler 5))


;; ---------------------------------------------------------------------------
;; Factories / Predicates

(defn behavior
  ([label]
     (behavior label nil))
  ([label value]
     (Behavior. label
                (atom [value (now)])
                (atom true))))


(defn behavior?
  [r]
  (instance? Behavior r))


(defn eventstream
  [label]
  (Eventstream. label
                (atom {:queue (PersistentQueue/EMPTY)
                       :last-occ nil
                       :completed false})
                1000))


(defn eventstream?
  [r]
  (or (instance? Eventstream r)
      (instance? Seqstream r)))


;; ---------------------------------------------------------------------------
;; Push! Complete! Reset

(defn push!
  [& rvs]
  (doseq [[r v] (partition 2 rvs)]
    (assert (reactive? r))
    (rn/push! r v)))


(defn complete!
  [& rs]
  (doseq [r rs]
    (rn/complete! r)))


(defn reset-network!
  []
  (sched/cancel-all scheduler)
  (rn/reset-network! *netref*))


;; ---------------------------------------------------------------------------
;; Enqueuing reactives and switching bewteen them

(defn- make-reactive-queue
  ([output]
     (make-reactive-queue output nil))
  ([output rs]
     {:queue (vec rs)
      :input nil
      :output output}))


(defn- switch-reactive
  [{:keys [queue input output] :as queue-state} q-atom]
  (let [uncompleted (c/remove completed? queue)
        r           (first uncompleted)]
    (if (and r (or (nil? input) (completed? input)))
      (assoc queue-state
        :queue (vec (rest uncompleted))
        :input r
        :dont-complete [output]
        :add [(make-link "temp" [r] [output]
                         :complete-fn
                         (fn [_ r]
                           (c/merge (c/swap! q-atom switch-reactive q-atom)
                                    {:remove-by #(= [r] (link-inputs %))})))])
      (dissoc queue-state :dont-complete :add))))


(defn- enqueue-reactive
  [queue-state q-atom r]
  (switch-reactive (update-in queue-state [:queue] conj r) q-atom))


;; ---------------------------------------------------------------------------
;; Misc internal utils

(defn- countdown
  "Returns a function that accepts any args and return n times true,
  and then forever false."
  [n]
  (let [c (atom n)]
    (fn [& args]
      (<= 0 (c/swap! c dec)))))


(defn- sample-fn
  "If passed a function returns it wrapped in an exception handler.
  If passed a IDeref (atom, agent, behavior, ...) returns a function that derefs it.
  If passed any other value return (constantly value)."
  [f-or-ref-or-value]
  (cond
   (fn? f-or-ref-or-value)
   #(try (f-or-ref-or-value)
         (catch Exception ex
           (do (.printStackTrace ex)
               ;; TODO what to push in case f fails?
               ex)))
   (instance? clojure.lang.IDeref f-or-ref-or-value)
   #(deref f-or-ref-or-value)
   :else
   (constantly f-or-ref-or-value)))


(defn- derive-new
  "Creates, links and returns a new reactive which will complete if
  the link to it is removed."
  [factory-fn label inputs
   & {:keys [link-fn complete-fn error-fn]
      :or {link-fn default-link-fn}}]
  {:pre [(seq inputs)]}
  (let [new-r (factory-fn label)]
    (add-links! *netref* (make-link label inputs [new-r]
                                    :link-fn link-fn
                                    :complete-fn complete-fn
                                    :error-fn error-fn
                                    :complete-on-remove [new-r]))
    new-r))


(defn- safely-apply
  "Applies f to xs, and catches exceptions.
  Returns a pair of [result exception], at least one of them being nil."
  [f xs]
  (try [(apply f xs) nil]
       (catch Exception ex [nil ex])))


(defn- make-result-map
  "Input is a Result map as passed into a Link function. If the
  exception ex is nil produces a broadcasting output-rvts, otherwise
  adds the exception. Returns an updated Result map."
  ([input value]
     (make-result-map input value nil))
  ([{:keys [output-reactives] :as input} value ex]
     (assoc input 
       :output-rvts (if-not ex (broadcast-value value output-reactives))
       :exception ex)))


(defn- make-link-fn
  "Takes a function f and wraps it's synchronous execution so that
  exceptions are caught and the return value is properly assigned to
  all output reactives.

  The optional result-fn is a function [Result Value Exception -> Result] 
  that returns the input Result map with updated values for 
  :output-rvts, :exception, :add, :remove-by, :no-consume entries.

  Returns a link function."
  ([f]
     (make-link-fn f make-result-map))
  ([f result-fn]
     {:pre [f]}
     (fn [{:keys [input-rvts] :as input}]
       (let [[v ex] (safely-apply f (values input-rvts))]
         (result-fn input v ex)))))


(defn- unpack-fn-spec
  "Returns a pair of [executor f]. If fn-spec is only a
  function (instead of a map containing both) then [nil f] is
  returned."
  [{:keys [f executor] :as fn-spec}]
  (if executor
    [executor f]
    [nil fn-spec]))


(defn- fn-spec?
  "Returns true if f is either a function or a map containing a
  function in an :f entry."
  [f]
  (or (instance? clojure.lang.IFn f)
      (and (map? f) (instance? clojure.lang.IFn (:f f)))))



;; ---------------------------------------------------------------------------
;; Queue to be used with an atom or agent

(defn- make-queue
  [max-size]
  {:queue (PersistentQueue/EMPTY)
   :dequeued []
   :max-size max-size})


(defn- enqueue
  [{:keys [queue dequeued max-size] :as q} v]
  (if (>= (c/count queue) max-size)
    (assoc q
      :queue (conj (pop queue) v)
      :dequeued [(first queue)])
    (assoc q
      :queue (conj queue v)
      :dequeued [])))


(defn- dequeue
  [{:keys [queue dequeued] :as q}]
  (if-let [v (first queue)]
    (assoc q
      :queue (pop queue)
      :dequeued [v])
    (assoc q
      :dequeued [])))


(defn- dequeue-all
  [{:keys [queue dequeued] :as q}]
  (assoc q
    :queue (empty queue)
    :dequeued (vec queue)))


;; ---------------------------------------------------------------------------
;; More constructors of reactives


(defn fnbehavior
  [f]
  (assoc (Fnbehavior. f)
    :label (str f)))

(declare seqstream)

(defn just
  [{:keys [executor f] :as x}]
  (if (and executor f)
    (let [new-r  (eventstream "just")]
      (execute executor *netref* #(rn/push! new-r (f)))
      new-r)
    (assoc (seqstream [((sample-fn x))])
      :label "just")))


(defn sample
  [millis {:keys [executor f] :as x}]
  (let [netref  *netref*
        new-r   (eventstream "sample")
        task-f  (if (and executor f)
                  #(execute executor netref (fn [] (rn/push! netref new-r (f))))
                  #(rn/push! netref new-r ((sample-fn x))))]
    (sched/interval scheduler millis task-f)
    new-r))


(defn seqstream
  [xs]
  (assoc (Seqstream. (atom {:seq (seq xs)
                            :last-occ nil})
                     true)
    :label "seq"))


(defn timer
  [millis]
  (let [ticks  (atom 0)
        new-r  (eventstream "timer")
        netref *netref*]
    (sched/interval scheduler millis millis
                    #(rn/push! netref new-r (c/swap! ticks inc)))
    new-r))



;; ---------------------------------------------------------------------------
;; Common combinators


(declare match)


(defn amb
  [& rs]
  {:pre [(every? reactive? rs)]
   :post [(reactive? %)]}
  (let [new-r   (eventstream "amb")
        f       (fn [{:keys [input-reactives input-rvts] :as input}]
                  (let [r (first input-reactives)]
                    {:remove-by #(= (link-outputs %) [new-r])
                     :add [(make-link "amb-selected" [r] [new-r] :complete-on-remove [new-r])]
                     :output-rvts (single-value (fvalue input-rvts) new-r)}))
        links   (->> rs (c/map #(make-link "amb-tentative" [%] [new-r] :link-fn f)))]
    (apply (partial add-links! *netref*) links)
    new-r))


(defn any
  ([r]
     (any identity r))
  ([pred r]
     (match pred true false r)))


(defn buffer
  [no millis r]  
  {:pre [(number? no) (number? millis) (reactive? r)]
   :post [(reactive? %)]}
  (let [b      (atom {:queue [] :dequeued nil})
        task   (atom nil)
        enq    (fn [{:keys [queue] :as q} x]
                 (if (and (> no 0) (>= (c/count queue) no))
                   (assoc q :queue [x] :dequeued queue)
                   (assoc q :queue (conj queue x) :dequeued nil)))
        deq    (fn [{:keys [queue] :as q}]
                 (assoc q :queue [] :dequeued queue))
        netref *netref*]
    (derive-new eventstream "buffer" [r]
                :link-fn
                (fn [{:keys [input-rvts output-reactives] :as input}]
                  (let [output         (first output-reactives)
                        {vs :dequeued
                         queue :queue} (c/swap! b enq (fvalue input-rvts))]
                    (when (and (= 1 (c/count queue)) (> millis 0))
                      (c/swap! task (fn [_]
                                    (sched/once scheduler millis
                                                (fn []
                                                  (let [vs (:dequeued (c/swap! b deq))]
                                                    (when (seq vs)
                                                      (rn/push! netref output vs))))))))
                    (if (seq vs)
                      (do (some-> task deref sched/cancel)
                          (make-result-map input vs)))))
                :complete-fn
                (fn [link r]
                  (let [vs (:queue @b)]
                    (some-> task deref sched/cancel)
                    (if (seq vs)
                      {:output-rvts (single-value vs (-> link link-outputs first))}))))))

(defn buffer-t
  [millis r]
  (buffer -1 millis r))


(defn buffer-c
  [no r]
  (buffer no -1 r))


(defn changes
  [behavior]
  {:pre [(behavior? behavior)]
   :post [(eventstream? %)]}
  (let [last-value (atom @behavior)]
    (derive-new eventstream "changes" [behavior]
                :link-fn (fn [{:keys [input-rvts output-reactives]}]
                           (let [old @last-value
                                 new (fvalue input-rvts)]
                             (reset! last-value new)
                             (if (not= old new)
                               {:output-rvts (single-value [old new] (first output-reactives))}
                               {}))))))


(defn concat
  [& rs]
  {:pre [(every? reactive? rs)]
   :post [(reactive? %)]}
  (let [new-r   (eventstream "concat")
        state   (atom (make-reactive-queue new-r rs))
        link    (-> (c/swap! state switch-reactive state) :add first)]
    (add-links! *netref* link)
    new-r))


(defn count
  [r]
  {:pre [(reactive? r)]
   :post [(reactive? %)]}
  (let [c (atom 0)]
    (derive-new eventstream "count" [r]
                :link-fn
                (fn [{:keys [input-rvts] :as input}]
                  (make-result-map input (c/swap! c inc))))))


(defn debounce
  [millis r]
  {:pre [(number? millis) (reactive? r)]
   :post [(reactive? %)]}
  (let [task (atom nil)]
    (derive-new eventstream "debounce" [r]
                :link-fn
                (fn [{:keys [input-rvts output-reactives] :as input}]
                  (let [output (first output-reactives)
                        v      (fvalue input-rvts)
                        old-t  @task
                        netref *netref*
                        new-t  (sched/once scheduler millis #(rn/push! netref output v))]
                    (when (and old-t (sched/pending? old-t))
                      (sched/cancel old-t))
                    (reset! task new-t)
                  nil)))))


(defn delay
  [millis r]
  {:pre [(pos? millis) (reactive? r)]
   :post [(reactive? %)]}
  (let [netref *netref*]
    (derive-new eventstream "delay" [r]
                :link-fn
                (fn [{:keys [input-rvts output-reactives] :as input}]
                  (let [output (first output-reactives)
                        v      (fvalue input-rvts)]
                    (sched/once scheduler millis #(rn/push! netref output v))
                    nil)))))


(defn distinct
  [r]
  (let [vs (atom #{})]
    (derive-new eventstream "distinct" [r]
                :link-fn
                (fn [{:keys [input-rvts output-reactives] :as input}]
                  (let [v (fvalue input-rvts)]
                    (when-not (@vs v)
                      (c/swap! vs conj v)
                      {:output-rvts (single-value v (first output-reactives))}))))))


(defn drop-while
  [pred r]
  (derive-new eventstream "drop" [r]
              :link-fn
              (fn [{:keys [input-rvts] :as input}]
                (if (apply pred (values input-rvts))
                  {:dont-complete nil}
                  (make-result-map input (fvalue input-rvts))))))


(defn drop
  [no r]
  {:pre [(pos? no) (reactive? r)]
   :post [(reactive? %)]}
  (drop-while (countdown no) r))


(defn drop-last
  [no r]
  {:pre [(pos? no) (reactive? r)]
   :post [(reactive? %)]}
  (let [q (atom (make-queue no))]
    (derive-new eventstream "drop-last" [r]
                :link-fn
                (fn [{:keys [input-rvts output-reactives]}]
                  (let [vs (:dequeued (c/swap! q enqueue (fvalue input-rvts)))]
                    (if (seq vs)
                      {:output-rvts (single-value (first vs) (first output-reactives))}
                      {:dont-complete nil}))))))

(defn every
  ([r]
     (every identity r))
  ([pred r]
     (match (complement pred) false true r)))


(defn filter
  [pred r]
  {:pre [(fn-spec? pred) (reactive? r)]
   :post [(reactive? %)]}
  (let [[executor f] (unpack-fn-spec pred)]
    (derive-new eventstream "filter" [r]
                :executor executor
                :link-fn
                (make-link-fn f (fn [{:keys [input-rvts] :as input} v ex]
                                  (if v
                                    (make-result-map input
                                                     (fvalue input-rvts)
                                                     ex)))))))


(defn flatmap
  [f r]
  {:pre [(fn-spec? f) (reactive? r)]
   :post [(reactive? %)]}
  (let [[executor f] (unpack-fn-spec f)
        new-r    (eventstream "flatmap")
        state    (atom (make-reactive-queue new-r)) ]
    (add-links! *netref* (make-link "flatmap" [r] [new-r]
                                    :complete-on-remove [new-r]
                                    :executor executor
                                    :link-fn
                                    (fn [{:keys [input-rvts] :as input}]
                                      (let [r (f (fvalue input-rvts))]
                                        (c/swap! state enqueue-reactive state r)))
                                    ))
    new-r))


(defn hold
  [r]
  {:pre [(reactive? r)]
   :post [(behavior? %)]}
  (derive-new behavior "hold" [r]))


(defn into
  [ & rs]
  {:pre [(< 1 (c/count rs)) (every? reactive? rs)]
   :post [(reactive? %)]}
  (add-links! *netref* (make-link "into" [(last rs)] (c/drop-last rs)))
  (first rs))


(defn map
  [f & rs]
  {:pre [(fn-spec? f) (every? reactive? rs)]
   :post [(reactive? %)]}
  (let [[executor f] (unpack-fn-spec f)]
    (derive-new eventstream "map" rs
                :executor executor
                :link-fn
                (make-link-fn f make-result-map))))


(defn mapcat
  [f & rs]
  {:pre [(fn-spec? f) (every? reactive? rs)]
   :post [(reactive? %)]}
  (let [[executor f] (unpack-fn-spec f)]
    (derive-new eventstream "mapcat" rs
                :executor executor
                :link-fn
                (make-link-fn f (fn [input vs ex]
                                  (assoc input
                                    :output-rvts (if-not ex
                                                   (enqueue-values vs (-> input :output-reactives first)))
                                    :exception ex))))))


(defn match
  ([pred match-value default-value r]
  {:pre [(fn-spec? pred) (reactive? r)]
   :post [(reactive? %)]}
  (let [[executor pred] (unpack-fn-spec pred)]
    (derive-new eventstream "match" [r]
                :executor executor
                :link-fn
                (make-link-fn pred
                              (fn [{:keys [output-reactives]} v ex]
                                (if v
                                  {:output-rvts (single-value match-value (first output-reactives))
                                   :remove-by #(= output-reactives (link-outputs %))})))
                :complete-fn (fn [l r]
                               {:output-rvts (single-value default-value (-> l link-outputs first))})))))


(defn merge
  [& rs]
  {:pre [(every? reactive? rs)]
   :post [(reactive? %)]}
  (let [new-r   (eventstream "merge")
        links   (->> rs (c/map #(make-link "merge" [%] [new-r])))]
    (apply (partial add-links! *netref*) links)
    new-r))


(defn reduce
  [f initial-value r]
  {:pre [(fn-spec? f) (reactive? r)]
   :post [(reactive? %)]}
  (let [[executor f] (unpack-fn-spec f)
        accu         (atom initial-value)]
    (derive-new eventstream "reduce" [r]
                :executor executor
                :link-fn
                (make-link-fn (fn [v] (c/swap! accu f v))
                              (constantly {}))
                :complete-fn
                (fn [l r]
                  {:output-rvts (single-value @accu (-> l link-outputs first))}))))


(defn remove
  [pred r]
  (filter (if (map? pred)
            (update-in pred [:f] complement)
            (complement pred))
          r))


(defn scan
  [f initial-value r]
  {:pre [(reactive? r)]
   :post [(reactive? %)]}
  (let [[executor f] (unpack-fn-spec f)
        accu         (atom initial-value)]
    (derive-new eventstream "scan" [r]
                :link-fn
                (make-link-fn #(c/swap! accu f %)))))


(defn startwith
  [start-r r]
  (concat start-r r))


(defn switch
  [r]
  {:pre [(reactive? r)]
   :post [(reactive? %)]}
  (let [new-r (eventstream "switch")]
    (add-links! *netref*
                (make-link "switcher" [r] []
                           :link-fn
                           (fn [{:keys [input-rvts] :as input}]
                             (let [r (fvalue input-rvts)]
                               {:remove-by #(= (link-outputs %) [new-r])
                                :add (if (reactive? r)
                                       [(make-link "switch" [r] [new-r])])}))))
    new-r))


(defn snapshot
  [f-or-ref-or-value r]
  {:pre [(reactive? r)]
   :post [(reactive? %)]}
  (let [f (sample-fn f-or-ref-or-value)]
    (derive-new eventstream "snapshot" [r]
                :link-fn
                (fn [{:keys [output-reactives]}]
                  {:output-rvts (single-value (f) (first output-reactives))}))))


(defn subscribe
  ([f r]
     (subscribe (gensym "subscriber") f r))
  ([key f r]
     {:pre [(fn-spec? f) (reactive? r)]
      :post [(reactive? %)]}
     (let [[executor f] (unpack-fn-spec f)]
       (add-links! *netref* (assoc (make-link "subscriber" [r] []
                                              :link-fn (make-link-fn f (constantly {}))
                                              :executor executor)
                              :subscriber-key key))
       r)))


(defn swap!
  [a f r]
  (subscribe (partial c/swap! a f) r))


(defn take-while
  [pred r]
  {:pre [(fn-spec? pred) (reactive? r)]
   :post [(reactive? %)]}
  (derive-new eventstream "take" [r]
              :link-fn
              (fn [{:keys [input-rvts output-reactives] :as input}]
                (if (apply pred (values input-rvts))
                  {:output-rvts (single-value (fvalue input-rvts) (first output-reactives))}
                  {:no-consume true
                   :dont-complete nil
                   :remove-by #(= (link-outputs %) output-reactives)}))))


(defn take
  [no r]
  {:pre [(pos? no) (reactive? r)]
   :post [(reactive? %)]}
  (take-while (countdown no) r))


(defn take-last
  [no r]
  {:pre [(pos? no) (reactive? r)]
   :post [(reactive? %)]}
  (let [q (atom (make-queue no))]
    (derive-new eventstream "take-last" [r]
                :link-fn
                (fn [{:keys [input-rvts]}]
                  (c/swap! q enqueue (fvalue input-rvts)))
                :complete-fn
                (fn [l r]
                  {:output-rvts (enqueue-values (-> q deref :queue)
                                                (-> l link-outputs first))}))))


(defn throttle
  [f millis max-queue-size r]
  {:pre [(fn-spec? f) (pos? millis) (pos? max-queue-size) (reactive? r)]
   :post [(reactive? %)]}
  (let [q     (atom (make-queue max-queue-size))
        new-r (derive-new eventstream "throttle" [r]
                           :link-fn
                           (fn [{:keys [input-rvts] :as input}]
                             (if (>= (-> q deref :queue c/count) max-queue-size)
                               {:no-consume true}
                               (c/swap! q enqueue (fvalue input-rvts)))))
        netref *netref*]
    (sched/interval scheduler millis millis
                    #(let [vs (:dequeued (c/swap! q dequeue-all))]
                       (when-not (empty? vs) (rn/push! netref new-r (f vs)))))
    new-r))


(defn unsubscribe
  [key r]
  (remove-links! *netref* #(and (= (:subscriber-key %) key)
                                (= (link-inputs %) [r])))
  r)


;; ---------------------------------------------------------------------------
;; Expression lifting


(defn ^:no-doc as-behavior
  [label x]
  (if (behavior? x)
     x
    (behavior label x)))


(defn ^:no-doc and-f
  [& xs]
  (if (next xs)
    (and (first xs) (apply and-f (rest xs)))
    (first xs)))


(defn ^:no-doc or-f
  [& xs]
  (if (next xs)
    (or (first xs) (apply or-f (rest xs)))
    (first xs)))


(defn ^:no-doc lift-fn
  [f & rs]
  {:pre [(fn-spec? f) (every? reactive? rs)]
   :post [(reactive? %)]}
  (derive-new behavior "lift-fn" rs
              :link-fn
              (make-link-fn f)))


(defn ^:no-doc lift-if
  [test-b then-b else-b]
  (derive-new behavior "lift-if" [test-b then-b else-b]
              :link-fn
              (make-link-fn (fn [test then else]
                              (if test then else)))))


(defn ^:no-doc lift-cond
  [& test-expr-bs]
  (derive-new behavior "lift-cond" test-expr-bs
              :link-fn
              (make-link-fn (fn [& args]
                              (let [[test-value
                                     expr-value] (->> args
                                                      (partition 2)
                                                      (c/drop-while (comp not first))
                                                      first)]
                                expr-value)))))

(defn- lift-exprs
  [exprs]
  (c/map (fn [expr] `(lift ~expr)) exprs))


(defn- lift-dispatch
  [expr]
  (if (list? expr)
    (if (#{'if 'cond 'let 'and 'or} (first expr))
      (first expr)
      'fn-apply)
    'symbol))


(defmulti ^:private lift*
  #'lift-dispatch)


(defmacro lift
  [expr]
  (lift* expr))


(defmethod lift* 'symbol
  [expr]
  `(as-behavior ~(str expr) ~expr))


(defmethod lift* 'fn-apply
  [[f & args]]
  `(lift-fn ~f ~@(lift-exprs args)))


(defmethod lift* 'if
  [[_ test-expr then-expr else-expr]]
  `(lift-if (lift ~test-expr) (lift ~then-expr) (lift ~else-expr)))


(defmethod lift* 'let
  [[_ bindings & exprs]]
  `(let ~(c/into []
               (c/mapcat (fn [[sym expr]]
                           [sym `(lift ~expr)])
                         (partition 2 bindings)))
     ~@(lift-exprs exprs)))


(defmethod lift* 'cond
  [[_ & exprs]]
  `(lift-cond ~@(lift-exprs exprs)))


(defmethod lift* 'and
  [[_ & exprs]]
  `(lift-fn and-f ~@(lift-exprs exprs)))


(defmethod lift* 'or
  [[_ & exprs]]
  `(lift-fn or-f ~@(lift-exprs exprs)))


;; ---------------------------------------------------------------------------
;; Error handling



(defn err-ignore
  [r]
  (on-error *netref* r
            (fn [result] {})))


(defn err-retry-after
  [millis r]
  (let [netref *netref*]
    (on-error *netref* r
              (fn [{:keys [input-rvts]}]
                (sched/once scheduler millis #(enq netref {:rvt-map (c/into {} (vec input-rvts))}))
                {}))))


(defn err-return
  [x r]
  (on-error *netref* r
            (fn [{:keys [output-reactives]}]
              {:output-rvts (single-value x (first output-reactives))})))


(defn err-switch
  [r-after-error r]
  {:pre [(reactive? r-after-error) (reactive? r)]}
  (on-error *netref* r
            (fn [{:keys [input-reactives]}]
              {:remove-by #(= (link-outputs %) [r])
               :add [(make-link "err" [r-after-error] [r]
                                :link-fn default-link-fn)]})))


(defn err-into
  [error-r r]
  (on-error *netref* r
            (fn [input]
              (enq *netref* {:rvt-map {error-r [input (now)]}}))))


;; ---------------------------------------------------------------------------
;; Async execution


(defn in-future
  [f]
  {:f f :executor (FutureExecutor.)})
