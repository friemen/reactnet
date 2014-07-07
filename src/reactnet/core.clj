(ns reactnet.core
  (:require [clojure.set :refer [union]]
            [clojure.string :as s]))

;; TODOs
;; - Who exactly is "in error", an input?, a link?, an output
;; - Make use of completed?
;; - Add a network modifying behavior like switch 
;; - Limit max number of items in agents pending queue (back pressure)
;; - Add pause! and resume!
;; ... and many more ...


;; ---------------------------------------------------------------------------
;; Concepts

;; Reactive:
;; Contains a time-varying value.
;; Serves as abstraction of event streams and behaviors.

(defprotocol IReactive
  (get-value [r]
    "Returns current value of this reactive.")
  (network-id [r]
    "Returns a string containing the fully qualified name of a network
  agent var.")
  (silent-set! [r value-timestamp-pair]
    "Sets a pair of value and timestamp, returns true if a
  propagation of the value should be triggered.")
  (set-error! [r ex]
    "Stores the exception in this reactive.")
  (error [r]
    "Returns the exception if in error state, nil otherwise."))


;; Link:
;; A map that combines m input reactives, n output reactives and a link function f.
;;  :label    Label for pretty printing.
;;  :inputs   Input reactives.
;;  :outputs  Output reactives.
;;  :f        A link function with the arity of the number of input reactives.
;;  :level    The level within the reactive network (max level of all input reactives).

;; Link function:
;; A function taking 4 args (reactive which caused the evaluation, it's value,
;; the timestamp, input reactives, output reactives) which returns a Result map.

;; Result:
;; A map with the following entries
;;  :output-values    A map {reactive->value} containing the values for
;;                    each output reactive.
;; :+links            Links to add to the network
;; :-links            Links to remove from the network

;; Network:
;; A map containing
;;  :id             A string containing the fqn of the agent var
;;  :links          Collection of links
;;  :reactives      Set of reactives (derived)
;;  :level-map      Map {reactive -> topological-level} (derived)
;;  :links-map      Map {reactive -> Seq of links} (derived)

;; External Stimulus:
;; A map containing
;;  :reactive     The reactive whose value is to be set
;;  :value        The value to set
;;  :timestamp    The timestamp in ms when the value was pushed


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
  [reactive value timestamp]
  {:reactive reactive
   :value value
   :timestamp timestamp})


(declare reactives-from-links
         reactive-links-map
         reactive-level-map)

(defn make-network
  [id links]
  (let [level-map (reactive-level-map links)
        leveled-links (map #(assoc % :level (level-map %)) links)]
    {:id id
     :reactives (reactives-from-links leveled-links)
     :links leveled-links
     :links-map (reactive-links-map leveled-links)
     :level-map level-map}))


(defmacro defnetwork
  [symbol]
  `(def ~symbol (agent (make-network ~(str *ns* "/" symbol) [])
                       :error-handler ~(fn [_ ex] (.printStackTrace ex)))))

(defn network-by-id
  [id]
  (let [[ns-name sym-name] (s/split id #"/")]
    (some-> ns-name symbol the-ns ns-publics (get (symbol sym-name)) var-get)))


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
;; Modifying the network

(defn- add-link
  [{:keys [id links]} link]
  (make-network id (conj links link)))


(defn add-link!
  [n-agent link]
  (send-off n-agent add-link link))


;; ---------------------------------------------------------------------------
;; Propagation within network

(declare push!)


(defn- propagate!
  "Executes one propagation cycle."
  [{:keys [links-map level-map] :as n}
   {:keys [reactive value timestamp]}]
  {:pre [n]}
  (when (silent-set! reactive [value timestamp])
    (loop [links (->> (links-map reactive) (sort-by :level (comparator <)))]
      #_ (println (->> links (map str-link) (s/join ", ")))
      (when-let [{:keys [f inputs outputs level]} (first links)]
        (let [result-map (try (f reactive value timestamp inputs outputs)
                              (catch Exception ex {:exception ex}))
              new-links (->> result-map :output-values
                             (mapcat (fn [[r value]]
                                       ;; only changes to downstream reactives will be handled in this cycle
                                       (if (< (level-map r) level)
                                         (do (push! r value timestamp)
                                             ;; for those that are upstream
                                             ;; push! will add links to the pending queue
                                             nil)
                                         (do (silent-set! r [value timestamp])
                                             ;; these links will be returned for processing within the cycle
                                             (links-map r)))))
                             (remove nil?))]
          ;; handle :exception value in result-map
          (when-let [ex (:exception result-map)]
            (.printStackTrace ex)
            (doseq [r outputs] ;; are outputs the right addressee?
              (set-error! r ex)))
          (recur (->> links rest (concat new-links) (sort-by :level (comparator <)) distinct))))))
  n)


(defn push!
  ([reactive value]
     (push! reactive value (System/currentTimeMillis)))
  ([reactive value timestamp]
     (send-off (-> reactive network-id network-by-id) propagate! (make-stimulus reactive value timestamp))
     value))


(defn output-value-map
  [value outputs]
  (->> outputs (reduce (fn [m r] (assoc m r value)) {})))


;; ===========================================================================
;; BELOW HERE STARTS EXPERIMENTAL NEW REACTOR API IMPL

;; ---------------------------------------------------------------------------
;; A trivial implementation of the IReactive protocol

(defrecord React [n-id label a eventstream? completed? error]
  IReactive
  (get-value [this] (first @a))
  (network-id [this] n-id)
  (silent-set! [this [value timestamp]]
    (when (or eventstream? (not= (first @a) value))
      (println (str-react this) "<-" value)
      (reset! a [value timestamp])
      true))
  (set-error! [this ex] (reset! error ex))
  (error [this] @error)
  clojure.lang.IDeref
  (deref [this] (first @a)))

(prefer-method print-method java.util.Map clojure.lang.IDeref)
(prefer-method print-method clojure.lang.IRecord clojure.lang.IDeref)

(defn behavior
  ([n-agent label]
     (behavior n-agent label nil))
  ([n-agent label value]
     (React. (-> n-agent deref :id)
             label
             (atom [value (System/currentTimeMillis)])
             false
             (atom false)
             (atom nil))))

(defn behavior?
  [r]
  (= (:eventstream? r) false))

(defn eventstream
  [n-agent label]
  (React. (-> n-agent deref :id)
          label
          (atom [nil (System/currentTimeMillis)])
          true
          false
          false))

(defn eventstream?
  [r]
  (= (:eventstream? r) true))


;; ---------------------------------------------------------------------------
;; Some combinators


(defn derive-new
  [factory-fn label f inputs]
  {:pre [(seq inputs)]}
  (let [n-id (network-id (first inputs))
        n-agent (network-by-id n-id)
        new-r (factory-fn n-agent label)]
    (add-link! n-agent (make-link label f inputs [new-r]))
    new-r))



(defn rmap
  [f & reactives]
  (derive-new eventstream
              "map"
              (fn [reactive value timestamp inputs outputs]
                (let [result (->> inputs (map get-value) (apply f))]
                  {:output-values (output-value-map result outputs)}))
              reactives))


(defn rreduce
  [f initial-value & reactives]
  (derive-new behavior
              "reduce"
              (fn [reactive value timestamp inputs outputs]
                {:pre [(= 1 (count outputs))]}
                (let [accu-reactive (first outputs)
                      accu-value    (or (get-value accu-reactive) initial-value)
                      result (->> inputs (map get-value) (cons accu-value) (apply f))]
                  {:output-values {accu-reactive result}}))
              reactives))


(defn rmerge
  [& reactives]
  (derive-new eventstream
              "merge"
              (fn [reactive value timestamp inputs outputs]
                {:output-values (output-value-map value outputs)})
              reactives))


(defn rfilter
  [pred reactive]
  (derive-new eventstream
              "filter"
              (fn [reactive value timestamp inputs outputs]
                {:output-values (if (pred value)
                                  (output-value-map value outputs)
                                  {})})
              [reactive]))


(defn rtake
  [no reactive]
  (let [c (atom no)]
    (derive-new eventstream
              "take"
              (fn [reactive value timestamp inputs outputs]
                {:output-values (if (> @c 0)
                                  (do (swap! c dec)
                                      (output-value-map value outputs))
                                  {})})
              [reactive])))


(defn rbuffer
  [no reactive]
  (let [l (java.util.LinkedList.)]
    (derive-new eventstream
                "buffer"
                (fn [reactive value timestamp inputs outputs]
                  (when (>= (.size l) no)
                    (.removeLast l))
                  (.addFirst l value)
                  {:output-values (output-value-map (vec l) outputs)})
                [reactive])))


(defn subscribe
  [f reactive]
  (let [n-id (network-id reactive)
        n-agent (network-by-id n-id)
        callback-fn (fn [reactive value timestamp inputs outputs]
                      (f (-> inputs first get-value) timestamp))]
    (add-link! n-agent (make-link "subscriber" callback-fn [reactive] []))
    reactive))

(import [java.util.concurrent ScheduledThreadPoolExecutor TimeUnit])

(defn- now
  []
  (System/currentTimeMillis))

(defonce ^:private scheduler (ScheduledThreadPoolExecutor. 5))

(defn rdelay
  [millis reactive]
  (let [n-agent (-> reactive network-id network-by-id)]
    (derive-new eventstream
                "delay"
                (fn [reactive value timestamp inputs outputs]
                  (.schedule scheduler #(push! (first outputs) value) millis TimeUnit/MILLISECONDS))
                [reactive])))


;; ---------------------------------------------------------------------------
;; Example network


(defnetwork n)
(def x (behavior n "x" 0))
(def y (behavior n "y" 2))
(def x+y (rmap + x y))
(def zs (->> (rmap * x x+y)
             (rreduce conj [])))

(def e1 (eventstream n "e1"))
(def e2 (eventstream n "e2"))

(def f (->> e1 (rtake 3) (rfilter (partial = "foo"))))

(subscribe (fn [value timestamp] (println value timestamp))
           (rmerge f e2))

(def b (->> e1
            (rbuffer 3)
            (rdelay 3000)
            (subscribe (fn [value timestamp] (println value)))))

#_ (doseq [i (range 10)]
     (push! x i))
