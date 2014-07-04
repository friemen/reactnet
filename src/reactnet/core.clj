(ns reactnet.core
  (:require [clojure.set :refer [union]]
            [clojure.string :as s]
            [clojure.data.priority-map :refer [priority-map-by]]))

;; TODOs
;; - Support complex results
;; - Use return value of silent-push! to decide if links should be evaluated
;; - Support for merge
;; - Create API for changing the network

;; Examples
;; - Introduce async execution
;; - Play around with take, buffer or drop
;; - Introduce throtteling
;; - Implement usual suspects like map, filter, remove, reduce


;; ---------------------------------------------------------------------------
;; Concepts

;; Reactive:
;; Contains a time-varying value. Supports functions silent-push! and get-value

;; Link:
;; Combines m input reactives, n output reactives, a link function f.
;;  :label
;;  :inputs   Input
;;  :outputs
;;  :f
;;  :level

;; Link function:
;; A function taking 3 args (causing source reactive, inputs, outputs)
;; which returns a Result map.

;; Result:
;; A link function produces either a map containing ::results or another value.
;; The value is pushed into to the output reactives.
;; The ::results map contains the following entries
;;   ::results    A map {reactive->value} containing the values for
;;                each output reactive.

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


(defprotocol IReactive
  (silent-push! [r value])
  (get-value [r]))


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
    {:reactives (ref (reactives-from-links leveled-links))
     :links (ref leveled-links)
     :command (atom (promise))
     :pending-queue (ref [])
     :links-map (ref (reactive-links-map leveled-links))
     :level-map (ref level-map)}))


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
  [n]
  (let [links @(:links n)
        reactives @(:reactives n)]
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


(defn push!
  [n reactive value]
  (silent-push! reactive value)
  (dosync (let [links (get @(:links-map n) reactive)]
            (alter (:pending-queue n) into links)
            (deliver @(:command n) :continue)))
  value)

(defn eval-link!
  [n {:keys [f inputs outputs level]}]
  (let [level-map @(:level-map n)
        links-map @(:links-map n)
        result (apply f (map get-value inputs))]
    (->> outputs
         (mapcat (fn [reactive]
                   ;; only changes to downstream reactives will be handled in this cycle
                   (if (< (level-map reactive) level)
                     (do (push! n reactive result)
                         ;; for those that are upstream
                         ;; push! will add links to the pending queue
                         nil)
                     (do (silent-push! reactive result)
                         ;; these links will be returned for processing within the cycle
                         (links-map reactive)))))
         (remove nil?))))


(defn propagate!
  [n]
  (dosync
   (let [pending @(:pending-queue n)]
     (alter (:pending-queue n) empty)
     (loop [q (->> pending
                   (map (juxt identity :level))
                   (into (priority-map-by (comparator <))))]
       #_ (println (->> q (map first) (map str-link) (s/join ", ")))
       
       (if-let [l (ffirst q)]
         #_ (println (str-link l))
         (let [current-links (->> q rest (map first))
               new-links (eval-link! n l)]
           (recur (->> current-links
                       (concat new-links)
                       distinct
                       (map (juxt identity :level))
                       (into (empty q))))))))))


(defn run-network
  [n]
  (future
    (try (reset! (:command n) (promise))
         (println "Ready")
         (loop []
           (let [command (-> n :command deref deref)]
             (reset! (:command n) (promise))
             (when (not= :exit command)
               (propagate! n)
               (Thread/sleep 2000)
               (recur))))
         (catch Exception ex
           (do (println ex)
               (.printStackTrace ex))))
    (println "Exiting.")))


(defn stop-network
  [n]
  (dosync (alter (:pending-queue n) empty))
  (deliver @(:command n) :exit))





;; ---------------------------------------------------------------------------
;; Sample data

(defrecord React [label a completed? error?]
  IReactive
  (silent-push! [this value]
    (println (str-react this) "<-" value)
    (reset! a value))
  (get-value [this] @a)
  clojure.lang.IDeref
  (deref [this] @a))

(prefer-method print-method java.util.Map clojure.lang.IDeref)
(prefer-method print-method clojure.lang.IRecord clojure.lang.IDeref)

(defn react
  [label value]
  (React. label (atom value) false false))


(def rs {:x (react "x" 0)
         :y (react "y" 2)
         :x+y (react "x+y" 0)
         :z (react "z" 0)})

(def n (make-network (make-link "+" + [(:x rs) (:y rs)] [(:x+y rs)])
                     (make-link "*" * [(:x rs) (:x+y rs)] [(:z rs)])))

