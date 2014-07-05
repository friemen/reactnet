(ns reactnet.core
  (:require [clojure.set :refer [union]]
            [clojure.string :as s]
            [clojure.data.priority-map :refer [priority-map-by]]))

;; TODOs
;; - Associate reactives with their network
;; - Introduce error and completed state in protocol
;; - Create combinators like map, filter, lift, reduce, delay, take
;; - Limit max number of items in pending queue (back pressure)
;; - Create API for changing the network
;; - Add pause! and resume!
;; ... and many more ...



;; ---------------------------------------------------------------------------
;; Concepts

;; Reactive:
;; Contains a time-varying value.
;; Serves as abstraction of event streams and behaviors.

(defprotocol IReactive
  (silent-set! [r value]
    "Sets reactive's value, returns true if any
  re-evaluation of the network should be triggered.")
  (get-value [r]
    "Returns current value of this reactive."))


;; Link:
;; A map that combines m input reactives, n output reactives and a link function f.
;;  :label    Label for pretty printing.
;;  :inputs   Input reactives.
;;  :outputs  Output reactives.
;;  :f        A link function with the arity of the number of input reactives.
;;  :level    The level within the reactive network (max level of all input reactives).

;; Link function:
;; A function taking 4 args (reactive which caused the evaluation, it's value,
;; input reactives, output reactives) which returns a Result map.

;; Result:
;; A map with the following entries
;;  :output-values    A map {reactive->value} containing the values for
;;                    each output reactive.
;; :+links            Links to add to the network
;; :-links            Links to remove from the network

;; Network:
;; A map containing 
;;  :command        A ref holding a promise whose delivery starts a propagation cycle
;;  :pending-queue  A collection keeping external stimuli
;;  :links          Collection of links
;;  :reactives      Set of reactives (derived)
;;  :level-map      Map {reactive -> topological-level} (derived)
;;  :links-map      Map {reactive -> Seq of links} (derived)

;; External Stimulus:
;; A map containing
;;  :reactive
;;  :value



;; ---------------------------------------------------------------------------
;; Factories


(defn make-link
  [label f inputs outputs]
  {:label label
   :f f
   :inputs inputs
   :outputs outputs
   :level 0})

(defn make-stimulus
  [reactive value]
  {:reactive reactive
   :value value})


(declare reactives-from-links
         reactive-links-map
         reactive-level-map)

(defn make-network
  [& links]
  (let [level-map (reactive-level-map links)
        leveled-links (map #(assoc % :level (level-map %)) links)]
    {:reactives (reactives-from-links leveled-links)
     :links leveled-links
     :links-map (reactive-links-map leveled-links)
     :level-map level-map}))


;; ---------------------------------------------------------------------------
;; Pretty printing

(defn str-react
  [r]
  (str (:label r) ":" (get-value r)))

(defn str-link  
  [l]
  (str "[" (s/join " " (map :label (:inputs l)))
       "] -- " (:label l) " --> ["
       (s/join " " (mapv :label (:outputs l)))
       "] L" (:level l)))

(defn str-stimulus
  [s]
  (str "Stimulus " (str-react (:reactive s)) " <- " (:value s)))

(defn pp
  [n-agent]
  (let [links (:links @n-agent)
        reactives (:reactives @n-agent)]
    (println (str "Values\n" (s/join ", " (map str-react reactives))
                  "\nLinks\n" (s/join "\n" (map str-link links))))))

;; ---------------------------------------------------------------------------
;; Getting information about the reactive graph


(defn reactives-from-links
  [links]
  (->> links
       (mapcat (fn [l] (concat (:inputs l) (:outputs l))))
       set))


(defn reactive-links-map
  "Returns a map {reactive -> (Seq of links)}."
  [links]
  (->> links
       (mapcat (fn [{:keys [inputs outputs] :as link}]
                 (for [i inputs] [i link])))
       (reduce (fn [m [i link]]
                 (update-in m [i] conj link))
               {})))


(defn reactive-followers-map
  "Returns a map {reactive -> (Set of following reactives)}."
  [links]
  (->> links
       reactive-links-map
       (map (fn [[r links]]
              [r (->> links (mapcat :outputs) set)]))
       (into {})))


(defn reactive-level-map
  "Returns a map {reactive -> level} where level is a number representing topological order."
  [links]
  (let [root (atom nil)
        rfm (reactive-followers-map links)
        rfm-with-root (assoc rfm root (set (keys rfm)))
        levels (fn levels [visited level reactive]
                 (if-not (visited reactive)
                   (cons [reactive level]
                         (mapcat (partial levels (conj visited reactive) (inc level)) (rfm-with-root reactive)))))
        level-map-wo-root (dissoc (->> (levels #{} 0 root)
                                       (reduce (fn [m [r l]]
                                                 (assoc m r (max (or (m r) 0) l)))
                                               {}))
                                  root)
        level-map-incl-links (->> links
                                  (map (fn [l]
                                         [l (->> (:inputs l)
                                                 (map level-map-wo-root)
                                                 (reduce max))]))
                                  (into level-map-wo-root))]
    level-map-incl-links))


;; ---------------------------------------------------------------------------
;; Propagation network

(declare push!)


(defn- propagate!
  "Executes one propagation cycle."
  [{:keys [links-map level-map] :as n}
   {:keys [reactive value]}]
  {:pre [n]}
  (when (silent-set! reactive value)
    (loop [links (->> (links-map reactive) (sort-by :level (comparator <)))]
      #_ (println (->> links (map str-link) (s/join ", ")))
      (when-let [{:keys [f inputs outputs level]} (first links)]
        (let [result-map (f reactive value inputs outputs)
              new-links (->> result-map :output-values
                             (mapcat (fn [[reactive value]]
                                       ;; only changes to downstream reactives will be handled in this cycle
                                       (if (< (level-map reactive) level)
                                         (do (push! n reactive value)
                                             ;; for those that are upstream
                                             ;; push! will add links to the pending queue
                                             nil)
                                         (do (silent-set! reactive value)
                                             ;; these links will be returned for processing within the cycle
                                             (links-map reactive)))))
                             (remove nil?))]
          (recur (->> links rest (concat new-links) (sort-by :level (comparator <)) distinct))))))
  n)


(defn push!
  [n-agent reactive value]
  (send-off n-agent propagate! {:reactive reactive :value value})
  value)


(defn map*
  [f]
  (fn [reactive value inputs outputs]
    (let [result (->> inputs (map get-value) (apply f))]
      {:output-values (->> outputs (reduce (fn [m r] (assoc m r result)) {}))})))


(defn reduce*
  [f]
  (fn [reactive value inputs outputs]
    {:pre [(= 1 (count outputs))]}
    (let [accu-reactive (first outputs)
          accu-value    (get-value accu-reactive)
          result (->> inputs (map get-value) (cons accu-value) (apply f))]
      {:output-values {accu-reactive result}})))


(defn merge*
  [reactive value inputs outputs]
  {:pre [(= 1 (count outputs))]}
  {:output-values {(first outputs) value}})


;; ---------------------------------------------------------------------------
;; A trivial implementation of the IReactive protocol

(defrecord React [label a completed? error?]
  IReactive
  (silent-set! [this value]
    (when (not= @a value)
      (println (str-react this) "<-" value)
      (reset! a value)
      true))
  (get-value [this] @a)
  clojure.lang.IDeref
  (deref [this] @a))

(prefer-method print-method java.util.Map clojure.lang.IDeref)
(prefer-method print-method clojure.lang.IRecord clojure.lang.IDeref)

(defn react
  [label value]
  (React. label (atom value) false false))


;; ---------------------------------------------------------------------------
;; Example network

(def rs {:x (react "x" 0)
         :y (react "y" 2)
         :x+y (react "x+y" 0)
         :z (react "z" 0)
         :zs (react "zs" [])})

(def n (agent (make-network (make-link "+" (map* +) [(:x rs) (:y rs)] [(:x+y rs)])
                            (make-link "*" (map* *) [(:x rs) (:x+y rs)] [(:z rs)])
                            (make-link "reduce-conj" (reduce* conj) [(:z rs)] [(:zs rs)]))
              :error-handler (fn [_ ex] (.printStackTrace ex))))

#_ (doseq [x (range 10)]
     (push! n (:x rs) x))
