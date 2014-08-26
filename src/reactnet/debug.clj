(ns reactnet.debug
  "Logging debug data to an internal agent.")


(def ^:no-doc log? false)
(def ^:no-doc log-agent (agent []))

(defn on
  "Turns logging on."
  []
  (alter-var-root #'log? (constantly true)))


(defn off
  "Turns logging off."
  []
  (alter-var-root #'log? (constantly false)))


(defn clear
  "Removes all log contents."
  []
  (send log-agent (constantly []))
  nil)


(defn dump
  "Prints log contents to console."
  ([]
     (dump (constantly true)))
  ([pred]
     (let [basetime (some-> @log-agent first :time)]
       (doseq [entry (->> @log-agent
                          (filter pred)
                          (sort-by :time))
               :let [t (Math/round (/ (- (:time entry) basetime) 1e6))]]
         
         (println (format "%6d" t) "-" (dissoc entry :time))))))


(defmacro log
  "Logs x if logging is on and returns x."
  [x]
  `(do (when log?
         (send log-agent conj (assoc (if (map? ~x) ~x {:data ~x})
                                :time (System/nanoTime))))
      ~x))


