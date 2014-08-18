(ns reactnet.netrefs
  "Default INetworkRef implementations: Agent and Atom based."
  (:require [reactnet.core :refer [INetworkRef *netref* update-and-propagate! pending?]]
            [reactnet.scheduler :as sched]))




(defrecord AgentNetref [max-queue-size n-agent sched]
  INetworkRef
  (enq [this stimulus]
    (when (>= (.getQueueCount n-agent) max-queue-size)
      ;; don't add more values to already pending reactives
      (when (and (seq (:rvt-map stimulus))
                 (every? pending? (keys (:rvt-map stimulus))))
        (throw (IllegalStateException. (str "Cannot enqueue more than " max-queue-size
                                            " stimuli, failed for reactives "
                                            (->> stimulus :rvt-map keys (map :label)))))))
    (send-off n-agent (fn [n]
                        (binding [*netref* this]
                          (let [n (update-and-propagate! n stimulus)]
                            (when (agent-error n-agent)
                              (println (agent-error n-agent)))
                            n))))
    this)
  (scheduler [this]
    sched)
  (network [this]
    @n-agent))


(def max-queue-size 1000)

(defn agent-netref
  "Wraps and returns the network in an agent based NetworkRef."
  [network]
  (AgentNetref. max-queue-size (agent network) (sched/scheduler 15)))


(defrecord AtomNetref [n-atom sched]
  INetworkRef
  (enq [this stimulus]
    (binding [*netref* this]
      (swap! n-atom (fn [n]
                      (update-and-propagate! n stimulus)))
      this))
  (scheduler [this]
    sched)
  (network [this]
    @n-atom))


(defn atom-netref
  "Wraps and returns the network in an atom based NetworkRef. Only
  used for unit testing."
  [network]
  (AtomNetref. (atom network) (sched/scheduler 5)))



