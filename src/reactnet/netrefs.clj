(ns reactnet.netrefs
  "Default INetworkRef implementations."
  (:require [reactnet.core :refer [INetworkRef *netref*]]))

;; put this into it's own ns

(defn- sleep-if-necessary
  "Puts the current thread to sleep if the queue of pending agent
  computations exceeds max-items."
  [n-agent max-items millis]
  (when (< max-items (.getQueueCount n-agent))
    (Thread/sleep millis)))


(defrecord AgentNetref [n-agent]
  INetworkRef
  (update [this f args]
    (sleep-if-necessary n-agent 1000 100)
    (send-off n-agent (fn [n]
                        (binding [*netref* this]
                          (apply (partial f n) args)))))
  (network [this]
    @n-agent))


(defn agent-netref
  "Wraps and returns the network in an agent based NetworkRef."
  [network]
  (AgentNetref. (agent network
                       :error-handler (fn [_ ex] (.printStackTrace ex)))))


(defrecord AtomNetref [n-atom]
  INetworkRef
  (update [this f args]
    (binding [*netref* this]
      (swap! n-atom (fn [n]
                      (apply (partial f n) args)))))
  (network [this]
    @n-atom))


(defn atom-netref
  "Wraps and returns the network in an atom based NetworkRef. Only
  used for unit testing."
  [network]
  (AtomNetref. (atom network)))



