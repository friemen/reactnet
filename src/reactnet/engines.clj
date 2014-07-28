(ns reactnet.engines
  (:require [reactnet.core :refer [IEngine *engine*]]))

;; put this into it's own ns

(defn- sleep-if-necessary
  "Puts the current thread to sleep if the queue of pending agent
  computations exceeds max-items."
  [n-agent max-items millis]
  (when (< max-items (.getQueueCount n-agent))
    (Thread/sleep millis)))


(defrecord AgentEngine [n-agent]
  IEngine
  (execute [this f args]
    (sleep-if-necessary n-agent 1000 100)
    (send-off n-agent (fn [n]
                        (binding [*engine* this]
                          (apply (partial f n) args)))))
  (network [this]
    @n-agent))


(defn agent-engine
  [network]
  (AgentEngine. (agent network
                       :error-handler (fn [_ ex] (.printStackTrace ex)))))


(defrecord AtomEngine [n-atom]
  IEngine
  (execute [this f args]
    (binding [*engine* this]
      (swap! n-atom (fn [n]
                      (apply (partial f n) args)))))
  (network [this]
    @n-atom))


(defn atom-engine
  [network]
  (AtomEngine. (atom network)))



