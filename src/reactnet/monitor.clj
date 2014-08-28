(ns reactnet.monitor
  "Functions for monitoring number/time values.")

(defn ^:no-doc add-duration
  [a start stop]
  (swap! a (fn [{:keys [n nanos] :as m}]
             (let [nanos (+ (or nanos 0) (- stop start))
                   millis (int (/ nanos 1e6))]
               (assoc m
                 :type :duration
                 :n (inc (or n 0))
                 :millis millis
                 :nanos nanos)))))


(defn ^:no-doc add-number
  [a v]
  (swap! a (fn [{:keys [n sum max min avg] :as m}]
             (let [new-n   (inc (or n 0))
                   new-sum (+ (or sum 0) v)]
               (assoc m
                 :type :number
                 :n new-n
                 :sum new-sum
                 :max (clojure.core/max v (or max Long/MIN_VALUE))
                 :min (clojure.core/min v (or min Long/MAX_VALUE))
                 :avg (float (/ new-sum new-n)))))))


(def ^{:doc "Activates collection of values if set to true."} profile? true)


(defmacro duration
  "Execute the expressions and measure the duration. 
  Updates the monitor specified by key.
  Returns the result of exprs evaluation."
  [monitors key & exprs]
  `(if profile?
     (let [start# (System/nanoTime)
           result# (do ~@exprs)
           stop# (System/nanoTime)]
       (add-duration (get ~monitors ~key) start# stop#)
       result#)
     (do ~@exprs)))


(defmacro number
  "Takes the number v and adds it to the sum in the monitor specified by key.
  Returns the monitor map or nil (if profile? is false)."
  [monitors key v]
  `(when profile?
     (add-number (get ~monitors ~key) ~v)))


(defn print-all
  "Printlns all monitors contained in the monitors map."
  [monitors]
  (doseq [[s a] (sort-by first monitors)]
    (println s @a)))


(defn reset-all!
  "Sets all monitor atoms in the monitors map to nil."
  [monitors]
  (doseq [[k a] monitors]
    (reset! a nil)))


