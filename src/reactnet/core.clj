(ns reactnet.core
  (:require [clojure.set :refer [union]]
            [clojure.string :as s]))

;; TODOs
;; - Create more unit tests
;; - Implement 'concat' combinator, which requires the completed state
;; - Handle the initial state of the network
;; - Add network modifying combinators like 'switch' or RxJava's 'flatMap' 
;; - Make scheduler available in different ns, support at and at-fixed-rate 
;; - Limit max number of items in agents pending queue (provides back pressure)
;; - Add pause! and resume! for the network


;; Ideas about error handling
;; - An exception is thrown by custom functions invoked from a link
;;   function, therefore remove the error/set-error! from IReactive
;; - A link contains an error-handler function
;; - It should support features like 'return', 'retry', 'resume', 'ignore'
;; - It should allow redirection of an exception to a specific eventstream
;; - A retry would push! the same values again
;; - Special care must be taken for async operations


;; ---------------------------------------------------------------------------
;; Concepts

;; Reactive:
;; Contains a time-varying value.
;; Serves as abstraction of event streams and behaviors.

(defprotocol IReactive
  (network-id [r]
    "Returns a string containing the fully qualified name of a network
  agent var.")
  (get-value [r]
    "Returns current value of this reactive.")
  (consume! [r]
    "Returns current value of this reactive and may turn the state into unavailable.")
  (available? [r]
    "Returns true if a value is available.")
  (busy? [r]
    "Returns true if the reactive cannot accept a new value.")
  (deliver! [r value-timestamp-pair]
    "Sets a pair of value and timestamp, returns true if a
  propagation of the value should be triggered."))


;; Link:
;; A map that combines m input reactives, n output reactives and a link function f.
;;  :label          Label for pretty printing
;;  :inputs         Input reactives
;;  :outputs        Output reactives
;;  :f              A link function (see below)
;;  :error-handler  An error handler function
;;  :level          The level within the reactive network
;;                  (max level of all input reactives + 1)

;; Link function:
;; A function that takes two args (input and output reactive) and returns
;; a Result map (see below).

;; Error Handler function
;; A function that takes the link and the Result map that the link function
;; returned. It may return a new Result map (see below) or nil.

;; Result:
;; A map returned by a link function with the following entries
;;  :input-values     A map {reactive -> value} containing the input values
;;  :output-values    A map {reactive -> value} containing the values for
;;                    each output reactive, or a vector containing of such
;;                    maps, i.e. {reactive -> [value*]}.                    
;;  :exception        Exception, or nil if output-values is valid

;; Network:
;; A map containing
;;  :id             A string containing the fqn of the agent var
;;  :links          Collection of links
;;  :reactives      Set of reactives (derived)
;;  :level-map      Map {reactive -> topological-level} (derived)
;;  :links-map      Map {reactive -> Seq of links} (derived)
;;  :values         Seq of pending values in the form [reactive [value timestamp]]

;; Reactive Values:
;; A map {reactive -> [[value timestamp]*]} containing for each
;; reactive a vector of value-timestamp-pairs.


;; ---------------------------------------------------------------------------
;; Factories

(declare push!)

(defn now
  []
  (System/currentTimeMillis))


(defn make-link
  [label f inputs outputs]
  {:label label
   :f f
   :inputs inputs
   :outputs outputs
   :level 0})


(defn safely-apply
  [f vs]
  (try [(apply f vs) nil]
       (catch Exception ex (do (.printStackTrace ex) [nil ex]))))


(defn make-output-value-map
  [value outputs]
  (reduce (fn [m r] (assoc m r value)) {} outputs))


(defn make-result-map
  [value ex inputs outputs]
  {:output-values (if-not ex (make-output-value-map value outputs))
   :exception ex})


(defn make-async-link-fn
  [f result-fn]
  (fn [inputs outputs]
    (future (let [[result ex] (safely-apply f (map consume! inputs))
                  result-map (result-fn result ex inputs outputs)]
              (doseq [[r v] (:output-values result-map)]
                (push! r v))))
    {:output-values {}}))


(defn make-sync-link-fn
  ([f]
     (make-sync-link-fn f make-result-map))
  ([f result-fn]
     (fn [inputs outputs]
       (let [[result ex] (safely-apply f (map consume! inputs))]
         (result-fn result ex inputs outputs)))))


(defn async
  [f]
  {:async f})


(defn unpack-fn
  [fn-or-map]
  (if-let [f (:async fn-or-map)]
    [make-async-link-fn f]
    [make-sync-link-fn fn-or-map]))


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
     :level-map level-map
     :values nil}))


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
  (str "L" (:level l)
       " [" (s/join " " (map :label (:inputs l)))
       "] -- " (:label l) " --> ["
       (s/join " " (mapv :label (:outputs l)))
       "] " (if (every? available? (:inputs l))
              "WILL EVALUATE" "incomplete inputs")))


(defn str-rvalue
  [[r [v timestamp]]]
  (str (:label r) ": " v))


(defn str-rvalues
  [[r vs]]
  (str (:label r) ": [" (->> vs (map first) (s/join ", ")) "]"))

(defn pp
  [n-agent]
  (let [links (:links @n-agent)
        reactives (:reactives @n-agent)]
    (println (str "Values\n" (s/join ", " (map str-react reactives))
                  "\nLinks\n" (s/join "\n" (map str-link links))))))

(def debug? false)

(defn dump
  [& args]
  (when debug?
    (apply println args))
  (first args))


(defn dump-links
  [links]
  (dump (apply str (repeat 60 \-)))
  (dump (->> links (map str-link) (s/join "\n")))
  (dump (apply str (repeat 60 \-))))


;; ---------------------------------------------------------------------------
;; Getting information about the reactive graph

(defn reactives-from-links
  "Returns a set of all reactives occurring as inputs our outputs in
  links."
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
  "Returns a map {reactive/link -> level} containing all reactives and
  links in the network, where level is an integer representing
  topological order."
  [links]
  (let [root (atom nil)
        rfm (reactive-followers-map links)
        rfm-with-root (assoc rfm root (set (keys rfm)))
        levels (fn levels [visited level reactive]
                 (if-not (visited reactive)
                   (cons [reactive level]
                         (mapcat (partial levels (conj visited reactive) (+ level 2)) (rfm-with-root reactive)))))
        level-map-wo-root (dissoc (->> (levels #{} 0 root)
                                       (reduce (fn [m [r l]]
                                                 (assoc m r (max (or (m r) 0) l)))
                                               {}))
                                  root)
        level-map-incl-links (->> links
                                  (map (fn [l]
                                         [l (->> (:inputs l)
                                                 (map level-map-wo-root)
                                                 (reduce max)
                                                 inc)]))
                                  (into level-map-wo-root))]
    level-map-incl-links))


(defn reactive-values-map
  "Returns a reactive values map {reactive -> [[value timestamp]+]
  from a sequence of pairs [reactive [value timestamp]]."
  [rv-pairs]
  (reduce (fn [m [r v]]
            (update-in m [r] (comp vec conj) v))
          {}
          rv-pairs))


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


(defn- handle-exception!
  [{:keys [error-handler] :as link} {:keys [exception] :as result}]
  (when exception
    (if error-handler
      (error-handler link result)
      (.printStackTrace exception))))


(defn- update-reactive-value!
  "Updates a reactive's value and returns the links that have the
  reactive as one of their inputs."
  [links-map [reactive value-timestamp]]
  (if-not (busy? reactive)
    (if (deliver! reactive value-timestamp)
      [nil (links-map reactive)]
      [nil nil])
    [[reactive value-timestamp] nil]))


(defn- update-reactive-values!
  "Updates all reactives, collects links to be subsequently evaluated,
  and returns a sorted distinct seq of links."
  [links-map pending-links reactive-values]
  (let [results         (map (partial update-reactive-value! links-map) reactive-values)
        rejected-values (->> results (map first) (remove nil?))
        links           (->> results
                             (mapcat second)
                             (concat pending-links)
                             (sort-by :level (comparator <))
                             distinct)]
    [rejected-values links]))


(defn- eval-link!
  "Evaluates one link, returning a reactive values map for those
  reactives that can be updated in the same cycle. Values for
  reactives that are below the link's level will be pushed to the
  network for processing in the next cycle."
  [level-map {:keys [f inputs outputs level] :as link}]
  (let [timestamp     (now)
        result        (merge {:input-values (->> inputs
                                                 (map #(vector % (get-value %)))
                                                 (into {}))}
                             (try (f inputs outputs)
                                  (catch Exception ex {:exception ex})))
        error-result  (handle-exception! link result)
        ;; make a vector [{reactives -> value}*] with many maps
        rvms          (let [ov (:output-values (merge result error-result))]  
                        (if-not (sequential? ov) [ov] ov))
        ;; make a Reactive Values map {reactive -> [[value timestamp]*]}
        ;; containing for each reactive a vector of result value / timestamp pairs
        rvsm          (->> rvms
                           (mapcat seq)
                           (reduce (fn [m [r v]]
                                     (let [r-level (level-map r)]
                                       (if (or (nil? r-level) (< r-level level))
                                         ;; push values into next cacle
                                         ;; whose reactive level is either
                                         ;; unknown or is lower than
                                         ;; current level
                                         (do (push! r v timestamp)
                                             m)
                                         (update-in m [r] (comp vec conj) [v timestamp]))))
                                   {}))]
    rvsm))


(defn- eval-links!
  "From a seq of links, sorted ascending by level, evaluates all links
  in the same level as the first. 
  Returns a pair of reactive values and a seq of unevaluated links."
  [level-map links]
  (let [available-links  (->> links (filter #(every? available? (:inputs %))) )
        level            (-> available-links first :level)
        pending-links    (->> available-links (remove #(= (:level %) level)))
        rvsm             (->> available-links
                              (filter #(= (:level %) level))
                              (map (partial eval-link! level-map))
                              (apply (partial merge-with concat)))]
    [rvsm pending-links]))


(defn- topmost-values
  "Returns a seq of pairs [reactive -> [value timestamp]] for all
  reactives that have a value left."
  [reactive-values]
  (->> reactive-values
       (remove (comp empty? second))
       (map (fn [[r vs]] [r (first vs)]))))


(defn- without-topmost-values
  "Returns a seq of pairs [reactive [[value timestamp]+]] where the
  first value-timestamp pair for each reactive is dropped."
  [reactive-values]
  (->> reactive-values
       (remove (comp empty? second))
       (map (fn [[r vs]] [r (vec (rest vs))]))))


(defn propagate!
  "Executes one propagation cycle, returns the network."
  ([network reactive-values]
     (propagate! network [] reactive-values))
  ([{:keys [links-map level-map] :as network}
    pending-links
    reactive-values]
     (let [[pending-values links] (update-reactive-values! links-map
                                                           pending-links
                                                           reactive-values)
           _ (dump "REJECTED " (map (juxt (comp :label first) (comp first second)) pending-values))
           _ (dump-links links)
           ;; *-rvsm is a map {reactive -> [[value timestamp]*]}
           [current-rvsm pending-links] (eval-links! level-map links)
           previous-rvsm (reactive-values-map (:values network))]

       (loop [n (dissoc network :values)
              rvss (merge-with concat previous-rvsm current-rvsm)]
         (let [top-rvs (topmost-values rvss)]
           ;; top-rvs is a seq of pairs [reactive [value timestamp]]
           (if (seq top-rvs)
             (recur (propagate! n pending-links top-rvs) (without-topmost-values rvss))
             (update-in n [:values] #(concat pending-values %))))))))


(defn push!
  "Starts asynchronously a propagation cycle, where initially the
  reactive is updated with the given value. Returns the value."
  ([reactive value]
     (push! reactive value (now)))
  ([reactive value timestamp]
     (send-off (-> reactive network-id network-by-id)
               propagate!
               {reactive [value timestamp]})
     value))


;; ===========================================================================
;; BELOW HERE STARTS EXPERIMENTAL NEW REACTOR API IMPL

;; ---------------------------------------------------------------------------
;; A trivial implementation of the IReactive protocol

(defrecord React [n-id label a eventstream? completed? avail?]
  IReactive
  (network-id [this] n-id)
  (get-value [this]
    (first @a))
  (consume! [this]
    (when eventstream?
      (reset! avail? false))
    (first @a))
  (available? [r] @avail?)
  (busy? [r] (and eventstream? @avail?))
  (deliver! [this [value timestamp]]
    (when (or eventstream? (not= (first @a) value))
      (dump "SET" (str-react this) "<-" value)
      (reset! a [value timestamp])
      (reset! avail? true)
      true))
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
             (atom [value (now)])
             false
             (atom false)
             (atom true))))

(defn behavior?
  [r]
  (= (:eventstream? r) false))

(defn eventstream
  [n-agent label]
  (React. (-> n-agent deref :id)
          label
          (atom [nil (System/currentTimeMillis)])
          true
          (atom false)
          (atom false)))

(defn eventstream?
  [r]
  (= (:eventstream? r) true))


;; ---------------------------------------------------------------------------
;; Simplistic scheduler support

(import [java.util.concurrent ScheduledThreadPoolExecutor TimeUnit])


(defonce ^:private scheduler (ScheduledThreadPoolExecutor. 5))

(defonce tasks (atom {}))

(defn clean-tasks!
  []
  (swap! tasks (fn [task-map]
                 (->> task-map
                      (remove #(let [t (second %)]
                                 (or (.isCancelled t) (.isDone t))))
                      (into {})))))

(defn cancel-tasks!
  []
  (doseq [[r f] @tasks]
    (.cancel f true)))


;; ---------------------------------------------------------------------------
;; More constructors of reactives

(defn rsample
  [n-agent millis f]
  (let [new-r (eventstream n-agent "sample")
        task (.scheduleAtFixedRate scheduler
                                   #(push! new-r
                                           (try (f)
                                                (catch Exception ex
                                                  (do (.printStackTrace ex)
                                                      ;; TODO what to push in case f fails?
                                                      ex))))
                                   0 millis TimeUnit/MILLISECONDS)]
    (swap! tasks assoc new-r task)
    new-r))


;; ---------------------------------------------------------------------------
;; Some combinators


(defn derive-new
  [factory-fn label link-fn inputs]
  {:pre [(seq inputs)]}
  (let [n-id (network-id (first inputs))
        n-agent (network-by-id n-id)
        new-r (factory-fn n-agent label)]
    (add-link! n-agent (make-link label link-fn inputs [new-r]))
    new-r))


(defn rhold
  [reactive]
  (derive-new behavior
              "hold"
              (make-sync-link-fn identity make-result-map)
              [reactive]))


(defn rmap
  [f & reactives]
  (let [[make-link-fn f] (unpack-fn f)]
    (derive-new eventstream
                "map"
                (make-link-fn f make-result-map)
                reactives)))


(defn rmapcat
  [f & reactives]
  (let [[make-link-fn f] (unpack-fn f)]
    (derive-new eventstream
                "mapcat"
                (make-link-fn f (fn [result ex inputs outputs]
                                  {:output-values (if-not ex (mapv #(make-output-value-map % outputs) result))
                                   :exception ex}))
                reactives)))


(defn rreduce
  [f initial-value & reactives]
  (let [[make-link-fn f] (unpack-fn f)
        accu (atom initial-value)]
    (derive-new behavior
                "reduce"
                (make-link-fn (fn [& vs]
                                (swap! accu #(apply (partial f %) vs)))
                              make-result-map)
                reactives)))


(defn rmerge
  [& reactives]
  (let [n-agent (-> reactives first network-id network-by-id)
        new-r (eventstream n-agent "merge")]
    (doseq [r reactives]
      (add-link! n-agent (make-link "merge"
                                    (make-sync-link-fn identity make-result-map)
                                    [r] [new-r])))
    new-r))


(defn rfilter
  [pred reactive]
  (let [[make-link-fn f] (unpack-fn pred)]
    (derive-new eventstream
                "filter"
                (make-link-fn f (fn [result ex inputs outputs]
                                  (if result
                                    (make-result-map (-> inputs first consume!)
                                                     ex
                                                     inputs
                                                     outputs))))
                [reactive])))


(defn rtake
  [no reactive]
  (let [c (atom no)]
    (derive-new eventstream
              "take"
              (fn [inputs outputs]
                (let [v (-> inputs first consume!)]
                  (if (> @c 0)
                    (do (swap! c dec)
                        (make-result-map v nil inputs outputs))
                    {})))
              [reactive])))


(defn rbuffer
  [no reactive]
  (let [l (java.util.LinkedList.)]
    (derive-new eventstream
                "buffer"
                (fn [inputs outputs]
                  (let [v (-> inputs first consume!)]
                    (when (>= (.size l) no)
                      (.removeLast l))
                    (.addFirst l v)
                    (make-result-map (vec l) nil inputs outputs)))
                [reactive])))


(defn subscribe
  [f reactive]
  (let [[make-link-fn f] (unpack-fn f)
        n-id (network-id reactive)
        n-agent (network-by-id n-id)]
    (add-link! n-agent (make-link "subscriber" (make-link-fn f (constantly nil)) [reactive] []))
    reactive))


(defn rdelay
  [millis reactive]
  (let [n-agent (-> reactive network-id network-by-id)]
    (derive-new eventstream
                "delay"
                (fn [inputs outputs]
                  (let [output (first outputs)
                        v (-> inputs first consume!)]
                    (swap! tasks assoc output
                           (.schedule scheduler #(push! output v) millis TimeUnit/MILLISECONDS))))
                [reactive])))


(defn make-queue
  [max-size]
  {:queue (clojure.lang.PersistentQueue/EMPTY)
   :dequeued []
   :max-size max-size})


(defn- enqueue [{:keys [queue dequeued max-size] :as q} v]
  (assoc q :queue
         (conj (if (>= (count queue) max-size)
                 (pop queue)
                 queue)
               v)))


(defn- dequeue [{:keys [queue dequeued] :as q}]
  (if-let [v (first queue)]
    (assoc q
      :queue (pop queue)
      :dequeued [v])
    (assoc q
      :dequeued [])))


(defn rthrottle
  [millis max-queue-size reactive]
  (let [n-agent (-> reactive network-id network-by-id)
        queue-atom (atom (make-queue max-queue-size))
        new-r (derive-new eventstream
                          "throttle"
                          (fn [inputs _]
                            (let [v (-> inputs first consume!)]
                              (swap! queue-atom enqueue v)))
                          [reactive])]
    (swap! tasks assoc new-r
           (.scheduleAtFixedRate scheduler
                                 #(let [vs (:dequeued (swap! queue-atom dequeue))]
                                    (when-not (empty? vs) (push! new-r (first vs))))
                                 millis millis TimeUnit/MILLISECONDS))
    new-r))



;; ---------------------------------------------------------------------------
;; Example network


(defnetwork n)

(def e1 (eventstream n "e1"))
(def e2 (eventstream n "e2"))

(def r (rmap + e1 e2))
(subscribe #(println %) r)

#_ (def f (->> e1 (rtake 3) (rfilter (partial = "foo"))))
#_ (subscribe #(println %)
           (rmerge f e2))




#_ (def b (->> e1
            (rbuffer 3)
            (rdelay 3000)
            (subscribe (fn [value] (println value)))))

#_ (def c (->> e1 (rmapcat #(repeat 3 %)) (subscribe #(println %))))

#_ (->> (constantly "foo")
     (rsample n 1000)
     (subscribe (fn [value] (println value))))


(def x (behavior n "x" nil))
(def y (behavior n "y" 2))
(def x+y (rmap + x y))
(def zs (->> (rmap * x x+y)
             (rreduce conj [])))

#_ (->> x+y (rdelay 3000) (subscribe #(println %)))

#_ (doseq [i (range 10)]
     (push! x i))


(def data {:name "bar" :addresses [{:street "1"}
                                   {:street "2"}
                                   {:street "3"}]})

(def p (behavior n "p" nil))
(def a (rmapcat :addresses p))
(def pname (rhold (rmap :name p)))
(def pair (rmap vector pname a))
(subscribe #(println "OUTPUT" %) pair)



:ok
