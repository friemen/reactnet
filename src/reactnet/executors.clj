(ns reactnet.executors
  "Default IExecutor implementations: FutureExecutor."
  (:require [reactnet.core :as rn])
  (:import [reactnet.core IExecutor]))

(deftype FutureExecutor []
  IExecutor
  (execute [e netref f]
    (future
      (rn/with-netref netref
        (try (f)
             (catch Exception ex (.printStackTrace ex) ex))))))

