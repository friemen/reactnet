(ns reactnet.reactor
  (:require [clojure.string :as s]
            [reactnet.scheduler :as sched]
            [reactnet.core :refer :all])
  (:import [clojure.lang PersistentQueue]))


;; TODOS
;; - How to prevent scheduled tasks from accumulating?

;; ===========================================================================
;; EXPERIMENTAL NEW REACTOR API IMPL

;; ---------------------------------------------------------------------------
;; A Behavior implementation of the IReactive protocol


(defrecord Behavior [n-id label a new?]
  IReactive
  (network-id [this]
    n-id)
  (last-value [this]
    (first @a))
  (available? [r]
    true)
  (pending? [r]
    @new?)
  (completed? [r]
    (= ::reactnet.core/completed (first @a)))
  (consume! [this]
    (reset! new? false)
    (dump "CONSUME!" (first @a) "<-" (:label this))
    @a)
  (deliver! [this [value timestamp]]
    (when (not= (first @a) value)
      (dump "DELIVER!" (:label this) "<-" value)
      (reset! a [value timestamp])
      (reset! new? true)))
  clojure.lang.IDeref
  (deref [this]
    (first @a)))

(prefer-method print-method java.util.Map clojure.lang.IDeref)
(prefer-method print-method clojure.lang.IRecord clojure.lang.IDeref)


(defn behavior
  ([n-agent label]
     (behavior n-agent label nil))
  ([n-agent label value]
     (Behavior. (-> n-agent deref :id)
                label
                (atom [value (now)])
                (atom true))))


;; ---------------------------------------------------------------------------
;; A buffered Eventstream implementation of the IReactive protocol


(defrecord Eventstream [n-id label a n]
  IReactive
  (network-id [this]
    n-id)
  (last-value [this]
    (-> a deref :last-occ first))
  (available? [this]
    (seq (:queue @a)))
  (pending? [this]
    (available? this))
  (completed? [this]
    (and (:completed @a) (empty? (:queue @a))))
  (consume! [this]
    (:last-occ (swap! a (fn [{:keys [queue] :as a}]
                          (when (empty? queue)
                            (throw (IllegalStateException. (str "Eventstream '" label "' is empty"))))
                          (dump "CONSUME!" (ffirst queue) "<-" (:label this))
                          (assoc a
                            :last-occ (first queue)
                            :queue (pop queue))))))
  (deliver! [this value-timestamp]
    (let [will-complete (= (first value-timestamp) ::reactnet.core/completed)]
      (seq (:queue (swap! a (fn [{:keys [completed queue] :as a}]
                              (if completed
                                a
                                (if will-complete
                                  (assoc a :completed true)
                                  (if (<= n (count queue))
                                    (throw (IllegalStateException. (str "Cannot add more than " n " items to stream '" label "'")))
                                    (do (dump "DELIVER!" (:label this) "<-" (first value-timestamp))
                                        (assoc a :queue (conj queue value-timestamp))))))))))))
  clojure.lang.IDeref
  (deref [this]
    (let [{:keys [queue last-occ]} @a]
      (or (first last-occ) (ffirst queue)))))



(defn eventstream
  [n-agent label]
  (Eventstream. (-> n-agent deref :id)
                label
                (atom {:queue (PersistentQueue/EMPTY)
                       :last-occ nil
                       :completed false})
                1000))


;; ---------------------------------------------------------------------------
;; An IReactive implementation based on a sequence

(defrecord SeqStream [n-id seq-val-atom eventstream?]
  IReactive
  (network-id [this]
    n-id)
  (last-value [this]
    (-> seq-val-atom deref :last-occ first))
  (available? [this]
    (-> seq-val-atom deref :seq seq))
  (pending? [this]
    (available? this))
  (completed? [this]
    (-> seq-val-atom deref :seq nil?))
  (consume! [this]
    (:last-occ (swap! seq-val-atom (fn [{:keys [seq]}]
                                     {:seq (next seq)
                                      :last-occ [(first seq) (now)]}))))
  (deliver! [r value-timestamp-pair]
    (throw (UnsupportedOperationException. "Unable to deliver a value to a seq"))))


(defn seqstream
  [n-agent xs]
  (assoc (SeqStream. (-> n-agent deref :id)
                     (atom {:seq (seq xs)
                            :last-occ nil})
                     true)
    :label "seq"))


;; ---------------------------------------------------------------------------
;; Link function factories and execution

(defn safely-apply
  "Applies f to xs, but catches Exception instances.
  Returns a pair of [result exception]."
  [f xs]
  (try [(apply f xs) nil]
       (catch Exception ex (do (.printStackTrace ex) [nil ex]))))


(defn values
  [rvt-map inputs]
  (->> inputs (map rvt-map) (map first)))


(defn first-value
  [rvt-map inputs]
  (-> inputs first rvt-map first))


(defn make-output-value-map
  [value outputs]
  (reduce (fn [m r] (assoc m r value)) {} outputs))


(defn make-result-map
  [rvt-map value ex inputs outputs]
  {:output-values (if-not ex (make-output-value-map value outputs))
   :exception ex})


(defn make-async-link-fn
  [f result-fn]
  (fn [rvt-map inputs outputs]
    (future (let [n-agent     (-> inputs first network-id network-by-id)
                  [result ex] (safely-apply f (values rvt-map inputs))
                  result-map  (result-fn rvt-map result ex inputs outputs)]
              (when (or (seq (:add result-map)) (:remove-by result-map))
                (send-off n-agent update-from-results! [result-map]))
              (doseq [[r v] (:output-values result-map)]
                (push! r v))))
    nil))


(defn make-sync-link-fn
  ([f]
     (make-sync-link-fn f make-result-map))
  ([f result-fn]
     (fn [rvt-map inputs outputs]
       (let [[result ex] (safely-apply f (values rvt-map inputs))]
         (result-fn rvt-map result ex inputs outputs)))))


(defn async
  [f]
  {:async f})


(defn unpack-fn
  [fn-or-map]
  (if-let [f (:async fn-or-map)]
    [make-async-link-fn f]
    [make-sync-link-fn fn-or-map]))



;; ---------------------------------------------------------------------------
;; Queue to be used with an atom or agent

(defn make-queue
  [max-size]
  {:queue (PersistentQueue/EMPTY)
   :dequeued []
   :max-size max-size})


(defn- enqueue [{:keys [queue dequeued max-size] :as q} v]
  (assoc q :queue
         (conj (if (>= (count queue) max-size)
                 (pop queue)
                 queue)
               v)))


(defn- dequeue [{:keys [queue dequeued] :as q}]
  (if-let [v (first queue)]
    (assoc q
      :queue (pop queue)
      :dequeued [v])
    (assoc q
      :dequeued [])))


;; ---------------------------------------------------------------------------
(defonce scheduler (sched/scheduler 5))


(defn halt!
  []
  (sched/cancel-all scheduler))

;; ---------------------------------------------------------------------------
;; More constructors of reactives

(defn rsample
  [n-agent millis f]
  (let [new-r (eventstream n-agent "sample")
        task  (sched/interval scheduler millis
                              #(push! new-r
                                      (try (f)
                                           (catch Exception ex
                                             (do (.printStackTrace ex)
                                                 ;; TODO what to push in case f fails?
                                                 ex)))))]
    new-r))


;; ---------------------------------------------------------------------------
;; Some combinators


(defn derive-new
  [factory-fn label link-fn inputs]
  {:pre [(seq inputs)]}
  (let [n-id    (network-id (first inputs))
        n-agent (network-by-id n-id)
        new-r   (factory-fn n-agent label)]
    (add-links! n-agent (make-link label inputs [new-r]
                                   :eval-fn link-fn
                                   :complete-on-remove [new-r]))
    new-r))


(defn rhold
  [reactive]
  (derive-new behavior
              "hold"
              (make-sync-link-fn identity make-result-map)
              [reactive]))


(defn rmap
  [f & reactives]
  (let [[make-link-fn f] (unpack-fn f)]
    (derive-new eventstream
                "map"
                (make-link-fn f make-result-map)
                reactives)))


(defn rmapcat'
  [f reactive]
  (let [[make-link-fn f] (unpack-fn f)
        n-agent  (-> reactive network-id network-by-id)
        new-r    (eventstream n-agent "mapcat'")
        state    (atom {:queue []
                        :active nil})
        switch   (fn switch [{:keys [queue active] :as state}]
                   (if-let [r (first queue)]
                     (if (or (nil? active) (completed? active))
                       {:queue (vec (rest queue))
                        :active r
                        :add [(make-link "mapcat'-temp" [r] [new-r]
                                         :completed-fn
                                         (fn [r]
                                           (merge (swap! state switch)
                                                  {:remove-by #(= [r] (:inputs %))})))]}
                       state)
                     state))
        enqueue  (fn [state r]
                   (switch (update-in state [:queue] conj r)))]
    (add-links! n-agent (make-link "mapcat'" [reactive] [new-r]
                                   :eval-fn (fn [rvt-map inputs _]
                                              (swap! state enqueue (first-value rvt-map inputs)))
                                   :complete-on-remove [new-r]))
    new-r))


(defn rmapcat
  [f & reactives]
  (let [[make-link-fn f] (unpack-fn f)]
    (derive-new eventstream
                "mapcat"
                (make-link-fn f (fn [rvt-map result ex inputs outputs]
                                  {:output-values (if-not ex (mapv #(make-output-value-map % outputs) result))
                                   :exception ex}))
                reactives)))


(defn rreduce
  [f initial-value & reactives]
  (let [[make-link-fn f] (unpack-fn f)
        accu             (atom initial-value)]
    (derive-new behavior
                "reduce"
                (make-link-fn (fn [& vs]
                                (swap! accu #(apply (partial f %) vs)))
                              make-result-map)
                reactives)))


(defn rmerge
  [& reactives]
  (let [n-agent (-> reactives first network-id network-by-id)
        new-r   (eventstream n-agent "merge")]
    (doseq [r reactives]
      (add-links! n-agent (make-link "merge" [r] [new-r]
                                     :eval-fn (make-sync-link-fn identity make-result-map))))
    new-r))


(defn rfilter
  [pred reactive]
  (let [[make-link-fn f] (unpack-fn pred)]
    (derive-new eventstream
                "filter"
                (make-link-fn f (fn [rvt-map result ex inputs outputs]
                                  (if result
                                    (make-result-map rvt-map
                                                     (first-value rvt-map inputs)
                                                     ex
                                                     inputs
                                                     outputs))))
                [reactive])))


(defn rtake
  [no reactive]
  (let [c (atom no)]
    (derive-new eventstream
              "take"
              (fn [rvt-map inputs outputs]
                (let [v (first-value rvt-map inputs)]
                  (if (> @c 0)
                    (do (swap! c dec)
                        (make-result-map rvt-map v nil inputs outputs))
                    {})))
              [reactive])))


(defn rconcat
  [& reactives]
  (let [n-agent (-> reactives first network-id network-by-id)
        new-r   (eventstream n-agent "concat")
        f       (fn [rvt-map inputs outputs]
                  (let [rs (remove completed? reactives)]
                    (if (seq rs)
                      (make-result-map rvt-map (first-value rvt-map rs) nil inputs outputs))))]
    (doseq [r reactives]
      (add-links! n-agent (make-link "concat" [r] [new-r] :eval-fn f)))
    new-r))


(defn rswitch
  [reactive]
  (let [n-agent (-> reactive network-id network-by-id)
        new-r   (eventstream n-agent "switch")]
    (add-links! n-agent
                (make-link "switcher" [reactive] []
                           :eval-fn
                           (fn [rvt-map inputs outputs]
                             (let [r (first-value rvt-map inputs)]
                               {:remove-by [#(= (:outputs %) [new-r])]
                                :add [(make-link "switch" [r] [new-r]
                                                 :eval-fn (make-sync-link-fn identity))]}))))
    new-r))


(defn rbuffer
  [no reactive]
  (let [l (java.util.LinkedList.)]
    (derive-new eventstream
                "buffer"
                (fn [rvt-map inputs outputs]
                  (let [v (first-value rvt-map inputs)]
                    (when (>= (.size l) no)
                      (.removeLast l))
                    (.addFirst l v)
                    (make-result-map rvt-map (vec l) nil inputs outputs)))
                [reactive])))


(defn subscribe
  [f reactive]
  {:pre [(fn? f)]}
  (let [[make-link-fn f] (unpack-fn f)
        n-id (network-id reactive)
        n-agent (network-by-id n-id)]
    (add-links! n-agent (make-link "subscriber" [reactive] []
                                   :eval-fn (make-link-fn f (constantly {}))))
    reactive))


(defn rdelay
  [millis reactive]
  (let [n-agent (-> reactive network-id network-by-id)]
    (derive-new eventstream
                "delay"
                (fn [rvt-map inputs outputs]
                  (let [output (first outputs)
                        v (first-value rvt-map)]
                    (sched/once scheduler millis #(push! output v))
                    {}))
                [reactive])))


(defn rthrottle
  [millis max-queue-size reactive]
  (let [n-agent (-> reactive network-id network-by-id)
        queue-atom (atom (make-queue max-queue-size))
        new-r (derive-new eventstream
                          "throttle"
                          (fn [rvt-map inputs _]
                            (let [v (first-value rvt-map inputs)]
                              (swap! queue-atom enqueue v)))
                          [reactive])]
    (sched/interval scheduler millis
                    #(let [vs (:dequeued (swap! queue-atom dequeue))]
                       (when-not (empty? vs) (push! new-r (first vs)))))
    new-r))



;; Howto define link functions succinctly?
#_ (def b (rmap {:f foobar
                 :link-fn-factory [sync, future, go]
                 :result-fn (fn [])
                 :error-fn (fn []) }))



;; ---------------------------------------------------------------------------
;; Example network


(defnetwork n)


(def e1 (eventstream n "e1"))
#_ (def e2 (rmapcat' #(seqstream n (range %)) e1) )
#_ (subscribe println e2)


(comment
  (def c (rconcat e1 e2))
  (def results (atom []))
  (subscribe (partial swap! results conj) c)

  #_ (do
       (push! e2 :bar1)
       (push! e2 :bar2)
       (push! e2 :bar3)
       (push! e2 :bar4)
       (push! e1 :foo)
       (push! e1 ::reactnet.core/completed)))

(comment
  (def e1 (eventstream n "e1"))
  (def e2 (eventstream n "e2"))
  (def s (eventstream n "s"))
  (def switched (rswitch s))
  (subscribe println switched))



#_ (def r (rmap + e1 e2))
#_ (subscribe #(println %) r)

#_ (def f (->> e1 (rtake 3) (rfilter (partial = "foo"))))
#_ (subscribe println
           (rmerge f e2))




#_ (def b (->> e1
            (rbuffer 3)
            (rdelay 3000)
            (subscribe (fn [value] (println value)))))

#_ (def c (->> e1 (rmapcat #(repeat 3 %)) (subscribe #(println %))))

#_ (->> (constantly "foo")
     (rsample n 1000)
     (subscribe (fn [value] (println value))))

(comment
  (def x (behavior n "x" nil))
  (def y (behavior n "y" 2))
  (def x+y (rmap + x y))
  (def zs (->> (rmap * x x+y)
               (rreduce conj [])))


  (doseq [i (range 10)]
    (push! x i))
  (->> x+y (rdelay 3000) (subscribe #(println %))))

(comment
  (def data {:name "bar" :addresses [{:street "1"}
                                     {:street "2"}
                                     {:street "3"}]})

  (def p (behavior n "p"))
  (def a (rmapcat :addresses p))
  (def pname (rmap :name p))
  (def pnameb (rhold pname))
  (def pair (rmap vector pnameb a))
  (subscribe #(println "OUTPUT" %) pair))


