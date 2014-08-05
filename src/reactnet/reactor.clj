(ns reactnet.reactor
  "Factories and combinators for FRP style behaviors and eventstreams."
  (:refer-clojure :exclude [concat count delay distinct drop drop-while drop-last
                            filter into merge map mapcat reduce remove some
                            swap! take take-last take-while])
  (:require [clojure.core :as c]
            [clojure.string :as s]
            [reactnet.scheduler :as sched]
            [reactnet.reactives]
            [reactnet.core :as rn :refer :all :exclude [push! complete!]]
            [reactnet.netrefs :refer [agent-netref]])
  (:import [clojure.lang PersistentQueue]
           [reactnet.reactives Behavior Eventstream Seqstream Fnbehavior]))


;; TODOS
;; - How to prevent scheduled tasks from accumulating?
;; - Invent marker protocol to support behavior? and eventstream? regardless of impl class
;; - Implement unsubscribe
;; - Implement proper error handling
;; - Implement execution-modes (sync, async, future)
;; - Implement empty
;; - Implement repeat
;; - Is cycle useful?

;; ===========================================================================
;; EXPERIMENTAL NEW REACTOR API IMPL


(alter-var-root #'reactnet.core/*netref*
                (fn [_]
                  (agent-netref (make-network "default" []))))

(defonce ^{:doc "A single scheduler."}
  scheduler (sched/scheduler 5))


;; ---------------------------------------------------------------------------
;; Factories / Predicates

(defn behavior
  ([label]
     (behavior "" nil))
  ([label value]
     (Behavior. label
                (atom [value (now)])
                (atom true))))


(defn eventstream
  [label]
  (Eventstream. label
                (atom {:queue (PersistentQueue/EMPTY)
                       :last-occ nil
                       :completed false})
                1000))



(defn eventstream?
  [reactive]
  (or (instance? Eventstream reactive)
      (instance? Seqstream reactive)))


(defn behavior?
  [reactive]
  (instance? Behavior reactive))


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
  (remove-links! *netref* (constantly true)))


;; ---------------------------------------------------------------------------
;; Enqueuing reactives and switching bewteen them

(defn- make-reactive-queue
  ([output]
     (make-reactive-queue output nil))
  ([output reactives]
     {:queue (vec reactives)
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
        :add [(make-link "temp" [r] [output]
                         :complete-fn
                         (fn [_ r]
                           (c/merge (c/swap! q-atom switch-reactive q-atom)
                                    {:remove-by #(= [r] (link-inputs %))})))])
      queue-state)))


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
  If passed any other value return a function that always returns it."
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
    (let [netref *netref*
          new-r  (eventstream "just")]
      (execute executor #(rn/push! netref new-r (f)))
      new-r)
    (assoc (seqstream [((sample-fn x))])
      :label "just")))


(defn sample
  [millis {:keys [executor f] :as x}]
  (let [netref  *netref*
        new-r   (eventstream "sample")
        task-f  (if (and executor f)
                  #(execute executor (fn [] (rn/push! netref new-r (f))))
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
;; Some combinators


(declare match)


(defn amb
  [& reactives]
  {:pre [(every? reactive? reactives)]
   :post [(reactive? %)]}
  (let [new-r   (eventstream "amb")
        f       (fn [{:keys [input-reactives input-rvts] :as input}]
                  (let [r (first input-reactives)]
                    {:remove-by #(= (link-outputs %) [new-r])
                     :add [(make-link "amb-selected" [r] [new-r] :complete-on-remove [new-r])]
                     :output-rvts (single-value (fvalue input-rvts) new-r)}))
        links   (->> reactives (c/map #(make-link "amb-tentative" [%] [new-r] :link-fn f)))]
    (apply (partial add-links! *netref*) links)
    new-r))


(defn any
  ([reactive]
     (any identity reactive))
  ([pred reactive]
     (match pred true false reactive)))


(defn buffer
  [no millis reactive]  
  {:pre [(number? no) (number? millis) (reactive? reactive)]
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
    (derive-new eventstream "buffer" [reactive]
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
  [millis reactive]
  (buffer -1 millis reactive))


(defn buffer-c
  [no reactive]
  (buffer no -1 reactive))


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
  [& reactives]
  {:pre [(every? reactive? reactives)]
   :post [(reactive? %)]}
  (let [new-r   (eventstream "concat")
        state   (atom (make-reactive-queue new-r reactives))
        link    (-> (c/swap! state switch-reactive state) :add first)]
    (add-links! *netref* link)
    new-r))


(defn count
  [reactive]
  {:pre [(reactive? reactive)]
   :post [(reactive? %)]}
  (let [c (atom 0)]
    (derive-new eventstream "count" [reactive]
                :link-fn
                (fn [{:keys [input-rvts] :as input}]
                  (make-result-map input (c/swap! c inc))))))


(defn debounce
  [millis reactive]
  {:pre [(number? millis) (reactive? reactive)]
   :post [(reactive? %)]}
  (let [task (atom nil)]
    (derive-new eventstream "debounce" [reactive]
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
  [millis reactive]
  {:pre [(pos? millis) (reactive? reactive)]
   :post [(reactive? %)]}
  (let [netref *netref*]
    (derive-new eventstream "delay" [reactive]
                :link-fn
                (fn [{:keys [input-rvts output-reactives] :as input}]
                  (let [output (first output-reactives)
                        v      (fvalue input-rvts)]
                    (sched/once scheduler millis #(rn/push! netref output v))
                    {:prevent-completion [output]})))))


(defn distinct
  [reactive]
  (let [vs (atom #{})]
    (derive-new eventstream "distinct" [reactive]
                :link-fn
                (fn [{:keys [input-rvts output-reactives] :as input}]
                  (let [v (fvalue input-rvts)]
                    (when-not (@vs v)
                      (c/swap! vs conj v)
                      {:output-rvts (single-value v (first output-reactives))}))))))


(defn drop-while
  [pred reactive]
  (derive-new eventstream "drop" [reactive]
              :link-fn
              (fn [{:keys [input-rvts] :as input}]
                (if (apply pred (values input-rvts))
                  {}
                  (make-result-map input (fvalue input-rvts))))))


(defn drop
  [no reactive]
  {:pre [(pos? no) (reactive? reactive)]
   :post [(reactive? %)]}
  (drop-while (countdown no) reactive))


(defn drop-last
  [no reactive]
  {:pre [(pos? no) (reactive? reactive)]
   :post [(reactive? %)]}
  (let [q (atom (make-queue no))]
    (derive-new eventstream "drop-last" [reactive]
                :link-fn
                (fn [{:keys [input-rvts output-reactives]}]
                  (let [vs (:dequeued (c/swap! q enqueue (fvalue input-rvts)))]
                    (if (seq vs)
                      {:output-rvts (single-value (first vs) (first output-reactives))}
                      {}))))))

(defn every
  ([reactive]
     (every identity reactive))
  ([pred reactive]
     (match (complement pred) false true reactive)))


(defn filter
  [pred reactive]
  {:pre [(fn-spec? pred) (reactive? reactive)]
   :post [(reactive? %)]}
  (let [[make-link-fn f] (unpack-fn pred)]
    (derive-new eventstream "filter" [reactive]
                :link-fn
                (make-link-fn f (fn [{:keys [input-rvts] :as input} v ex]
                                  (if v
                                    (make-result-map input
                                                     (fvalue input-rvts)
                                                     ex)))))))


(defn hold
  [reactive]
  {:pre [(reactive? reactive)]
   :post [(behavior? %)]}
  (derive-new behavior "hold" [reactive]))


(defn into
  [ & reactives]
  {:pre [(< 1 (c/count reactives)) (every? reactive? reactives)]
   :post [(reactive? %)]}
  (add-links! *netref* (make-link "into" [(last reactives)] (c/drop-last reactives)))
  (first reactives))


(defn map
  [f & reactives]
  {:pre [(fn-spec? f) (every? reactive? reactives)]
   :post [(reactive? %)]}
  (let [[make-link-fn f] (unpack-fn f)]
    (derive-new eventstream "map" reactives
                :link-fn
                (make-link-fn f make-result-map))))


(defn mapcat'
  [f reactive]
  {:pre [(fn-spec? f) (reactive? reactive)]
   :post [(reactive? %)]}
  (let [[make-link-fn f] (unpack-fn f)
        new-r    (eventstream "mapcat'")
        state    (atom (make-reactive-queue new-r)) ]
    (add-links! *netref* (make-link "mapcat'" [reactive] []
                                   :link-fn
                                   (fn [{:keys [input-rvts] :as input}]
                                     (let [r (f (fvalue input-rvts))]
                                       (c/swap! state enqueue-reactive state r)))
                                   :complete-on-remove [new-r]))
    new-r))


(defn mapcat
  [f & reactives]
  {:pre [(fn-spec? f) (every? reactive? reactives)]
   :post [(reactive? %)]}
  (let [[make-link-fn f] (unpack-fn f)]
    (derive-new eventstream "mapcat" reactives
                :link-fn
                (make-link-fn f (fn [input vs ex]
                                  (assoc input
                                    :output-rvts (if-not ex
                                                   (enqueue-values vs (-> input :output-reactives first)))
                                    :exception ex))))))


(defn match
  ([pred match-value default-value reactive]
  {:pre [(fn-spec? pred) (reactive? reactive)]
   :post [(reactive? %)]}
  (let [[make-link-fn pred] (unpack-fn pred)]
    (derive-new eventstream "match" [reactive]
                :link-fn
                (make-link-fn pred
                              (fn [{:keys [output-reactives]} v ex]
                                (if v
                                  {:output-rvts (single-value match-value (first output-reactives))
                                   :remove-by #(= output-reactives (link-outputs %))})))
                :complete-fn (fn [l r]
                               {:output-rvts (single-value default-value (-> l link-outputs first))})))))


(defn merge
  [& reactives]
  {:pre [(every? reactive? reactives)]
   :post [(reactive? %)]}
  (let [new-r   (eventstream "merge")
        links   (->> reactives (c/map #(make-link "merge" [%] [new-r])))]
    (apply (partial add-links! *netref*) links)
    new-r))


(defn reduce
  [f initial-value reactive]
  {:pre [(fn-spec? f) (reactive? reactive)]
   :post [(reactive? %)]}
  (let [[make-link-fn f] (unpack-fn f)
        accu             (atom initial-value)]
    (derive-new eventstream "reduce" [reactive]
                :link-fn
                (make-link-fn (fn [v] (c/swap! accu f v))
                              (constantly {}))
                :complete-fn
                (fn [l r]
                  {:output-rvts (single-value @accu (-> l link-outputs first))}))))


(defn remove
  [pred reactive]
  (filter (if (map? pred)
            (update-in pred [:f] complement)
            (complement pred))
          reactive))


(defn scan
  [f initial-value reactive]
  {:pre [(reactive? reactive)]
   :post [(reactive? %)]}
  (let [[make-link-fn f] (unpack-fn f)
        accu             (atom initial-value)]
    (derive-new eventstream "scan" [reactive]
                :link-fn
                (make-link-fn (fn [v] (c/swap! accu f v))
                              make-result-map))))


(defn startwith
  [start-reactive reactive]
  (concat start-reactive reactive))


(defn switch
  [reactive]
  {:pre [(reactive? reactive)]
   :post [(reactive? %)]}
  (let [new-r (eventstream "switch")]
    (add-links! *netref*
                (make-link "switcher" [reactive] []
                           :link-fn
                           (fn [{:keys [input-rvts] :as input}]
                             (let [r (fvalue input-rvts)]
                               {:remove-by #(= (link-outputs %) [new-r])
                                :add (if (reactive? r)
                                       [(make-link "switch" [r] [new-r])])}))))
    new-r))


(defn snapshot
  [f-or-ref-or-value reactive]
  (let [f (sample-fn f-or-ref-or-value)]
    (derive-new eventstream "snapshot" [reactive]
                :link-fn
                (fn [{:keys [output-reactives]}]
                  {:output-rvts (single-value (f) (first output-reactives))}))))


(defn subscribe
  [f reactive]
  {:pre [(fn-spec? f) (reactive? reactive)]
   :post [(reactive? %)]}
  (let [[make-link-fn f] (unpack-fn f)]
    (add-links! *netref* (make-link "subscriber" [reactive] []
                                    :link-fn (make-link-fn f (constantly {}))))
    reactive))


(defn swap!
  [a f reactive]
  (subscribe (partial c/swap! a f) reactive))


(defn take-while
  [pred reactive]
  {:pre [(fn-spec? pred) (reactive? reactive)]
   :post [(reactive? %)]}
  (derive-new eventstream "take" [reactive]
              :link-fn
              (fn [{:keys [input-rvts output-reactives] :as input}]
                (if (apply pred (values input-rvts))
                  {:output-rvts (single-value (fvalue input-rvts) (first output-reactives))}
                  {:no-consume true
                   :remove-by #(= (link-outputs %) output-reactives)}))))


(defn take
  [no reactive]
  {:pre [(pos? no) (reactive? reactive)]
   :post [(reactive? %)]}
  (take-while (countdown no) reactive))


(defn take-last
  [no reactive]
  {:pre [(pos? no) (reactive? reactive)]
   :post [(reactive? %)]}
  (let [q (atom (make-queue no))]
    (derive-new eventstream "take-last" [reactive]
                :link-fn
                (fn [{:keys [input-rvts]}]
                  (c/swap! q enqueue (fvalue input-rvts)))
                :complete-fn
                (fn [l r]
                  {:output-rvts (enqueue-values (-> q deref :queue)
                                                (-> l link-outputs first))}))))


(defn throttle
  [f millis max-queue-size reactive]
  {:pre [(fn-spec? f) (pos? millis) (pos? max-queue-size) (reactive? reactive)]
   :post [(reactive? %)]}
  (let [q     (atom (make-queue max-queue-size))
        new-r (derive-new eventstream "throttle" [reactive]
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



;; ---------------------------------------------------------------------------
;; Expression lifting


(defn ^:no-doc as-behavior
  [label x]
  (if (behavior? x)
     x
    (behavior label x)))


(defn ^:no-doc lift-fn
  [f & reactives]
  {:pre [(fn-spec? f) (every? reactive? reactives)]
   :post [(reactive? %)]}
  (derive-new behavior "lift-fn" reactives
              :link-fn
              (make-sync-link-fn f)))


(defn ^:no-doc lift-if
  [test-b then-b else-b]
  (derive-new behavior "lift-if" [test-b then-b else-b]
              :link-fn
              (make-sync-link-fn (fn [test then else]
                                   (if test then else)))))


(defn ^:no-doc lift-cond
  [& test-expr-bs]
  (derive-new behavior "lift-cond" test-expr-bs
              :link-fn
              (make-sync-link-fn (fn [& args]
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
    (if (#{'if 'cond 'let} (first expr))
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
