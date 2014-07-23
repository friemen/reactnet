(ns reactnet.reactor
  (:require [clojure.string :as s]
            [reactnet.scheduler :as sched]
            [reactnet.reactives]
            [reactnet.core :refer :all])
  (:import [clojure.lang PersistentQueue]
           [reactnet.reactives Behavior Eventstream SeqStream]))


;; TODOS
;; - How to prevent scheduled tasks from accumulating?

;; ===========================================================================
;; EXPERIMENTAL NEW REACTOR API IMPL


(defn behavior
  ([n-agent label]
     (behavior n-agent label nil))
  ([n-agent label value]
     (Behavior. (-> n-agent deref :id)
                label
                (atom [value (now)])
                (atom true))))


(defn eventstream
  [n-agent label]
  (Eventstream. (-> n-agent deref :id)
                label
                (atom {:queue (PersistentQueue/EMPTY)
                       :last-occ nil
                       :completed false})
                1000))

(defn seqstream
  [n-agent xs]
  (assoc (SeqStream. (-> n-agent deref :id)
                     (atom {:seq (seq xs)
                            :last-occ nil})
                     true)
    :label "seq"))


(defn async
  [f]
  {:async f})


(defn unpack-fn
  [fn-or-map]
  (if-let [f (:async fn-or-map)]
    [make-async-link-fn f]
    [make-sync-link-fn fn-or-map]))



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
  (let [uncompleted (remove completed? queue)
        r           (first uncompleted)]
    (if (and r (or (nil? input) (completed? input)))
      (assoc queue-state
        :queue (vec (rest uncompleted))
        :input r
        :add [(make-link "temp" [r] [output]
                         :complete-fn
                         (fn [r]
                           (merge (swap! q-atom switch-reactive q-atom)
                                  {:remove-by #(= [r] (:inputs %))})))])
      queue-state)))


(defn- enqueue-reactive
  [queue-state q-atom r]
  (switch-reactive (update-in queue-state [:queue] conj r) q-atom))




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
        state    (atom (make-reactive-queue new-r)) ]
    (add-links! n-agent (make-link "mapcat'" [reactive] []
                                   :eval-fn (fn [{:keys [input-rvts] :as input}]
                                              (let [r (f (fvalue input-rvts))]
                                                (swap! state enqueue-reactive state r)))
                                   :complete-on-remove [new-r]))
    new-r))


(defn rmapcat
  [f & reactives]
  (let [[make-link-fn f] (unpack-fn f)]
    (derive-new eventstream
                "mapcat"
                (make-link-fn f (fn [input vs ex]
                                  (assoc input
                                    :output-rvts (if-not ex
                                                   (enqueue-values vs (-> input :output-reactives first)))
                                    :exception ex)))
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
                (make-link-fn f (fn [{:keys [input-rvts] :as input} v ex]
                                  (if v
                                    (make-result-map input
                                                     (fvalue input-rvts)
                                                     ex))))
                [reactive])))


(defn rtake
  [no reactive]
  (let [c (atom no)]
    (derive-new eventstream
              "take"
              (fn [{:keys [input-rvts] :as input}]
                (let [v (fvalue input-rvts)]
                  (if (> @c 0)
                    (do (swap! c dec)
                        (make-result-map input v))
                    {})))
              [reactive])))


(defn rconcat
  [& reactives]
  (let [n-agent (-> reactives first network-id network-by-id)
        new-r   (eventstream n-agent "concat")
        state   (atom (make-reactive-queue new-r reactives))
        link    (-> (swap! state switch-reactive state) :add first)]
    (add-links! n-agent link)
    new-r))


(defn rstartwith
  [start-reactive reactive]
  (rconcat start-reactive reactive))


(defn rswitch
  [reactive]
  (let [n-agent (-> reactive network-id network-by-id)
        new-r   (eventstream n-agent "switch")]
    (add-links! n-agent
                (make-link "switcher" [reactive] []
                           :eval-fn
                           (fn [{:keys [input-rvts] :as input}]
                             (let [r (fvalue input-rvts)]
                               {:remove-by #(= (:outputs %) [new-r])
                                :add (if (satisfies? IReactive r)
                                       [(make-link "switch" [r] [new-r])])}))))
    new-r))


(defn rbuffer
  [no reactive]
  (let [l (java.util.LinkedList.)]
    (derive-new eventstream
                "buffer"
                (fn [{:keys [input-rvts] :as input}]
                  (let [v (fvalue input-rvts)]
                    (when (>= (.size l) no)
                      (.removeLast l))
                    (.addFirst l v)
                    (make-result-map input (vec l))))
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


(defn swap-conj!
  [a reactive]
  (subscribe (partial swap! a conj) reactive))


(defn rdelay
  [millis reactive]
  (let [n-agent (-> reactive network-id network-by-id)]
    (derive-new eventstream
                "delay"
                (fn [{:keys [input-rvts output-reactives] :as input}]
                  (let [output (first output-reactives)
                        v      (fvalue input-rvts)]
                    (sched/once scheduler millis #(push! output v))
                    nil))
                [reactive])))


(defn rthrottle
  [millis max-queue-size reactive]
  (let [n-agent (-> reactive network-id network-by-id)
        queue-atom (atom (make-queue max-queue-size))
        new-r (derive-new eventstream
                          "throttle"
                          (fn [{:keys [input-rvts] :as input}]
                            (let [v (fvalue input-rvts)]
                              (swap! queue-atom enqueue v)
                              nil))
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


#_ (def e1 (eventstream n "e1"))
#_ (def e2 (eventstream n "e2"))


#_ (def e2 (rmapcat' #(seqstream n (range %)) e1) )
#_ (subscribe println e2)



#_ (def c (rconcat e1 e2))
#_ (def results (atom []))
#_ (subscribe (partial swap! results conj) c)

#_ (do
(push! e2 :bar1)
(push! e2 :bar2)
(push! e2 :bar3)
(push! e2 :bar4)
(push! e1 :foo)
(push! e1 ::reactnet.core/completed))

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



