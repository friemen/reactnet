(ns reactnet.netrefs
  "Default INetworkRef implementations: Agent and Atom based."
  (:require [reactnet.core :refer [INetworkRef *netref* update-and-propagate!]]))

;; put this into it's own ns

(defn- sleep-if-necessary
  "Puts the current thread to sleep if the queue of pending agent
  computations exceeds max-items."
  [n-agent max-items millis]
  (when (< max-items (.getQueueCount n-agent))
    (Thread/sleep millis)))


(defrecord AgentNetref [n-agent]
  INetworkRef
  (enq [this stimulus]
    (sleep-if-necessary n-agent 1000 100)
    (send-off n-agent (fn [n]
                        (binding [*netref* this]
                          (update-and-propagate! n stimulus)))))
  (network [this]
    @n-agent))


(defn agent-netref
  "Wraps and returns the network in an agent based NetworkRef."
  [network]
  (AgentNetref. (agent network
                       :error-handler (fn [_ ex] (.printStackTrace ex)))))


(defrecord AtomNetref [n-atom]
  INetworkRef
  (enq [this stimulus]
    (binding [*netref* this]
      (swap! n-atom (fn [n]
                      (update-and-propagate! n stimulus)))))
  (network [this]
    @n-atom))


(defn atom-netref
  "Wraps and returns the network in an atom based NetworkRef. Only
  used for unit testing."
  [network]
  (AtomNetref. (atom network)))



