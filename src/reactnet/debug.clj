(ns reactnet.debug
  "Logging debug data to an internal agent."
  (:require [clojure.java.io :as io]))


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


(defn lines
  "Returns a seq of strings of the log agents content."
  ([]
     (lines (constantly true)))
  ([pred]
     (let [basetime (some-> @log-agent first :time)]
       (for [entry (->> @log-agent
                          (filter pred)
                          (sort-by :time))
               :let [t (Math/round (/ (- (:time entry) basetime) 1e6))]]
         (print-str (format "%6d" t) (format "%-10s" (:type entry)) "-" (dissoc entry :type :time))))))


(defn to-console
  "Writes lines to console."
  ([lines]
     (to-console 50 lines))
  ([max lines]
     (doseq [l (take max lines)]
       (println l))
     (when (> (count lines) max)
       (println (- (count lines) max) "more lines available"))))


(defn to-file
  ([lines]
     (to-file "/tmp/reactnet.log" lines))
  ([file lines]
     (with-open [w (io/writer file)]
       (doseq [l lines]
         (.write w l)
         (.write w "\n")))))


(defmacro log
  "Logs x if logging is on and returns x."
  [x]
  `(do (when log?
         (send log-agent conj (assoc (if (map? ~x) ~x {:data ~x})
                                :time (System/nanoTime))))
      ~x))


(defn log-pass-first
  [x logf]
  (log (logf x))
  x)


(defn log-pass-last
  [logf x]
  (log (logf x))
  x)


(defn ^:no-doc matches-reactive
  [label]
  (fn [x]
    (or (= (:r x) label)
        (seq (filter #(= % label) (concat (:inputs x) (:outputs x) (:rs x)))))))


