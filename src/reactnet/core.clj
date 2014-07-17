(ns reactnet.core
  (:require [clojure.set :refer [union]]
            [clojure.string :as s])
  (:import [clojure.lang PersistentQueue]))

;; TODOs
;; - complete-fn returns a Result map which must be handled properly in propagate!
;; - re-creation of the network must preserve foreign keys
;; - Automatically complete derived reactives where no link outputs points to
;; - Create unit test for add/remove links to/from network
;; - Create unit test for cyclic deps
;; - Handle the initial state of the network
;; - Add pause! and resume! for the network
;; - Graphviz visualization of the graph
;; - Support core.async
;; - Support interceptor?
;; - Make scheduler available in different ns, support at and at-fixed-rate 
;; - Back pressure: limit max number of items in agents pending queue

;; Ideas about completed state
;; - A reactive becomes obsolete if it is not part of any link.
;; - Completed does no apply to behaviours, but it is a general concept
;; - A completed reactive must not receive any outputs -> remove it from links :outputs.
;; - A link with one completed input is 'dead'
;; - A link with only completed outputs is 'dead'
;; - Derived reactives become completed when all links delivering to them are dead.

;; Ideas about error handling
;; - An exception is thrown by custom functions invoked from a link
;;   function
;; - A link contains an error-fn function
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
    "Returns latest value of this reactive.")
  (available? [r]
    "Returns true if a value is available.")
  (pending? [r]
    "Returns true if values are waiting for being consumed.")
  (completed? [r]
    "Returns true if the reactive will neither accept nor return a new value.")
  (consume! [r]
    "Returns current value of this reactive and may turn the state into unavailable.")
  (deliver! [r value-timestamp-pair]
    "Sets a pair of value and timestamp, returns true if a
  propagation of the value should be triggered."))


;; Link:
;; A map that combines m input reactives, n output reactives and a link function f.
;;  :label            Label for pretty printing
;;  :inputs           Input reactives
;;  :outputs          Output reactives
;;  :f                A link function (see below)
;;  :error-fn         An error handler function [result ex -> Result]
;;  :complete-fn      A function [reactive -> nil] called when one of the
;;                    input reactives becomes completed 
;;  :level            The level within the reactive network
;;                    (max level of all input reactives + 1)

;; Link function:
;; A function that takes two args (input and output reactives) and returns
;; a Result map (see below) or nil, which denotes that the function has
;; not consumed any value.

;; Error Handler function:
;; A function that takes the Link and the Result map that the link function
;; returned. It may return a new Result map (see below) or nil.

;; Result:
;; A map returned by a link function with the following entries
;;  :input-values     A map {reactive -> value} containing the input values
;;  :output-values    A map {reactive -> value} containing the values for
;;                    each output reactive, or a vector containing of such
;;                    maps, i.e. {reactive -> [value*]}.                    
;;  :exception        Exception, or nil if output-values is valid
;;  :add              A seq of links to be added to the network
;;  :remove-by        A predicate that matches links to be removed
;;                    from the network

;; Network:
;; A map containing
;;  :id               A string containing the fqn of the agent var
;;  :links            Collection of links
;;  :reactives        Set of reactives (derived)
;;  :level-map        Map {reactive -> topological-level} (derived)
;;  :links-map        Map {reactive -> Seq of links} (derived)


;; ---------------------------------------------------------------------------
;; Factories

(defn now
  []
  (System/currentTimeMillis))


(defn make-link
  ([label f inputs outputs]
     (make-link label f inputs outputs nil))
  ([label f inputs outputs options-map]
     (merge {:label label
             :f f
             :inputs inputs
             :outputs outputs
             :error-fn nil
             :complete-fn nil
             :level 0}
            options-map)))


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
  (str (if (completed? r) "C " "  ") (:label r) ":" (pr-str (get-value r))))


(defn str-link  
  [l]
  (str "  L" (:level l)
       " [" (s/join " " (map :label (:inputs l)))
       "] -- " (:label l) " --> ["
       (s/join " " (mapv :label (:outputs l)))
       "] " (if (every? available? (:inputs l))
              "READY" "incomplete")))


(defn str-rvalue
  [[r [v timestamp]]]
  (str (:label r) ": " v))


(defn str-rvalues
  [[r vs]]
  (str (:label r) ": [" (->> vs (map first) (s/join ", ")) "]"))


(defn pp
  [n-agent]
  (let [links (:links @n-agent)
        reactives (:reactives @n-agent)
        rvsm (reduce (fn [m [r [v t]]]
                       (update-in m [r] (comp vec conj) v))
                     {}
                     (:values @n-agent))]
    (println (str "Reactives\n" (s/join "\n" (map str-react reactives))
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


(defn dump-values
  [label rvs]
  (dump label (->> rvs
                   (map (fn [[r [v t]]]
                          (str (:label r) " " v)))
                   (s/join ", "))))


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
  "Returns a map {reactive -> (Seq of links)}, where the reactive is
  an input of the links it points to."
  [links]
  (->> links
       (mapcat (fn [{:keys [inputs] :as link}]
                 (for [r inputs] [r link])))
       (reduce (fn [m [r link]]
                 (update-in m [r] conj link))
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


(defn ready?
  "Returns true for a link if
  - all inputs are available,
  - at least one output is not completed."
  [{:keys [inputs outputs]}]
  (and (every? available? inputs)
       (remove completed? outputs)))


(defn dead?
  "Returns true for a link if at least of it's inputs is completed."
  [{:keys [inputs]}]
  (some completed? inputs))


;; ---------------------------------------------------------------------------
;; Modifying the network

(defn- add-link
  [{:keys [id links]} link]
  (make-network id (conj links link)))


(defn- cleanup
  "Removes all links from the network that have at least one input
  in completed state."
  [{:keys [id links]}]
  (->> links
       (filter #(not-any? completed? (:inputs %)))
       (make-network id)))


(defn add-link!
  [n-agent link]
  (send-off n-agent add-link link))


;; ---------------------------------------------------------------------------
;; Propagation within network


(defn- handle-exception!
  "Invokes the links error-fn function, or prints stacktrace if
  the link has no error-fn."
  [{:keys [error-fn] :as link} {:keys [exception] :as result}]
  (when exception
    (if error-fn
      (error-fn link result)
      (.printStackTrace exception))))


(defn- update-reactive-values!
  "Updates all reactives from the reactive-values map and returns them
  in a sequence."
  [reactive-values]
  (doseq [[r vt] reactive-values]
    (when-not (completed? r)
      (deliver! r vt)))
  (map first reactive-values))


(defn- eval-link!
  "Evaluates one link, returning Result map, nor nil if the link function
  did not consume a value."
  [{:keys [f inputs outputs level] :as link}]
  (let [result        (try (f inputs outputs)
                           (catch Exception ex {:exception ex}))
        inputs        {:input-values (->> inputs
                                          (map #(vector % (get-value %)))
                                          (into {}))}
        error-result  (handle-exception! link result)]
    (if result
      (merge inputs result error-result))))



(declare push!)


(defn propagate!
  "Executes one propagation cycle.
  Returns the network."
  ([network pending-reactives]
     (propagate! network [] pending-reactives))
  ([{:keys [links-map level-map] :as network}
    pending-links
    pending-reactives]
     (dump "\n= PROPAGATE" (apply str (repeat 49 "=")))
     (let [links           (->> pending-reactives
                                (mapcat links-map)
                                (concat pending-links)
                                (sort-by :level (comparator <))
                                distinct)
           _               (dump-links links)
           available-links (->> links (filter ready?))
           level           (-> available-links first :level)
           same-level?     (fn [l] (= (:level l) level))
           results         (->> available-links
                                (filter same-level?)
                                (map eval-link!)
                                (remove nil?))
           dead-links      (->> links (filter dead?))
           upstream?       (fn [[r _]]
                             (let [r-level (level-map r)]
                               (or (nil? r-level) (< r-level level))))
           no-consume?     (empty? results)
           timestamp       (now)
           ;; *-rvs is a sequence of reactive value pairs [r [v t]]
           all-rvs         (->> results
                                (map :output-values)
                                (remove nil?)
                                (mapcat (fn [ov]
                                          (if-not (sequential? ov)
                                            (seq ov)
                                            (mapcat seq ov))))
                                (map (fn [[r v]] [r [v timestamp]])))
           _               (dump-values "VALUES" all-rvs)
           downstream-rvs  (->> all-rvs
                                (remove upstream?)
                                (sort-by (comp level-map first) (comparator <)))
           upstream-rvs    (->> all-rvs (filter upstream?))   
           remove-links    (->> results
                                (map :remove-by)
                                (remove nil?)
                                (reduce (fn [ls pred]
                                          (->> network :links (filter pred) (concat ls)))
                                        dead-links)
                                set)
           add-links       (->> results (mapcat :add) set)
           pending-links   (->> available-links (remove same-level?))
           new-network     (->> network :links
                                (remove remove-links)
                                (concat add-links)
                                (make-network (:id network)))]
       
       ;; push value into next cycle if reactive level is either
       ;; unknown or is lower than current level
       (doseq [[r [v t]] upstream-rvs]
         (push! r v t))
    
       (if no-consume?
         (assoc network :no-consume? true)
         (loop [n new-network
                rvs downstream-rvs] 
           (let [[rvm remaining-rvs] (reduce (fn [[rvm remaining] [r vt]]
                                               (if (rvm r)
                                                 [rvm (conj remaining [r vt])]
                                                 [(assoc rvm r vt) remaining]))
                                             [{} []]
                                             rvs)]
             (if (seq rvm)
               (recur (propagate! n pending-links (update-reactive-values! rvm))
                      remaining-rvs)
               (dissoc n :no-consume?))))))))


(defn update-and-propagate!
  "Updates reactives with the contents of the reactive-values map,
  and runs propagation cycles as long as values are consumed. 
  Returns the network."
  [{:keys [reactives] :as network} reactive-values]
  (loop [n (propagate! network (update-reactive-values! reactive-values))
         pending-reactives (->> n :reactives (filter pending?))]
    (let [next-n      (propagate! n pending-reactives)
          progress?   (not (:no-consume? next-n))
          next-prs    (->> n :reactives (filter pending?))]
      (if (and progress? (seq next-prs))
        (recur next-n next-prs)
        n))))


(defn push!
  "Starts an update of a reactive and a propagation cycle in a
  different thread using network agent's send-off. 
  Returns the value."
  ([reactive value]
     (push! reactive value (now)))
  ([reactive value timestamp]
     (send-off (-> reactive network-id network-by-id)
               update-and-propagate!
               {reactive [value timestamp]})
     value))


(defn complete!
  "Delivers the ::completed value into a reactive and notifies the complete-fn
  handler of links that the reactive is an input of."
  [reactive]
  (deliver! reactive [::completed (now)])
  (doseq [l (-> reactive network-id network-by-id :links-map (get reactive))]
    (if-let [f (:complete-fn l)]
      (f reactive))))


;; ===========================================================================
;; BELOW HERE STARTS EXPERIMENTAL NEW REACTOR API IMPL

;; ---------------------------------------------------------------------------
;; A Behavior implementation of the IReactive protocol


(defrecord Behavior [n-id label a new?]
  IReactive
  (network-id [this]
    n-id)
  (get-value [this]
    (first @a))
  (available? [r]
    true)
  (pending? [r]
    @new?)
  (completed? [r]
    (= ::completed (first @a)))
  (consume! [this]
    (reset! new? false)
    (dump "CONSUME!" (first @a) "<-" (:label this))
    (first @a))
  (deliver! [this [value timestamp]]
    (when (not= (first @a) value)
      (dump "DELIVER!" (:label this) "<-" value)
      (reset! a [value timestamp])
      (reset! new? true)))
  clojure.lang.IDeref
  (deref [this]
    (first @a)))

(prefer-method print-method java.util.Map clojure.lang.IDeref)
(prefer-method print-method clojure.lang.IRecord clojure.lang.IDeref)


(defn behavior
  ([n-agent label]
     (behavior n-agent label nil))
  ([n-agent label value]
     (Behavior. (-> n-agent deref :id)
                label
                (atom [value (now)])
                (atom true))))


;; ---------------------------------------------------------------------------
;; A buffered Eventstream implementation of the IReactive protocol


(defrecord Eventstream [n-id label a n]
  IReactive
  (network-id [this]
    n-id)
  (get-value [this]
    (:last-value @a))
  (available? [this]
    (seq (:queue @a)))
  (pending? [this]
    (available? this))
  (completed? [this]
    (and (:completed @a) (empty? (:queue @a))))
  (consume! [this]
    (:last-value (swap! a (fn [{:keys [queue] :as a}]
                            (when (empty? queue)
                              (throw (IllegalStateException. (str "Eventstream '" label "' is empty"))))
                            (dump "CONSUME!" (ffirst queue) "<-" (:label this))
                            (assoc a
                              :last-value (ffirst queue)
                              :queue (pop queue))))))
  (deliver! [this value-timestamp]
    (let [will-complete (= (first value-timestamp) ::completed)]
      (seq (:queue (swap! a (fn [{:keys [completed queue] :as a}]
                              (if completed
                                a
                                (if will-complete
                                  (assoc a :completed true)
                                  (if (<= n (count queue))
                                    (throw (IllegalStateException. (str "Cannot add more than " n " items to stream '" label "'")))
                                    (do (dump "DELIVER!" (:label this) "<-" (first value-timestamp))
                                        (assoc a :queue (conj queue value-timestamp))))))))))))
  clojure.lang.IDeref
  (deref [this]
    (let [{:keys [queue last-value]} @a]
      (or last-value (ffirst queue)))))



(defn eventstream
  [n-agent label]
  (Eventstream. (-> n-agent deref :id)
                label
                (atom {:queue (PersistentQueue/EMPTY)
                       :last-value nil
                       :completed false})
                10))


;; ---------------------------------------------------------------------------
;; An IReactive implementation based on a sequence

(defrecord SeqStream [n-id seq-val-atom eventstream?]
  IReactive
  (network-id [this]
    n-id)
  (get-value [this]
    (-> seq-val-atom deref :last-value))
  (available? [this]
    (-> seq-val-atom deref :seq seq))
  (pending? [this]
    (available? this))
  (completed? [this]
    (-> seq-val-atom deref :seq nil?))
  (consume! [this]
    (:last-value  (swap! seq-val-atom (fn [{:keys [seq]}]
                                        {:seq (next seq)
                                         :last-value (first seq)}))))
  (deliver! [r value-timestamp-pair]
    (throw (UnsupportedOperationException. "Unable to deliver a value to a seq"))))


(defn seqstream
  [n-agent xs]
  (SeqStream. (-> n-agent deref :id)
              (atom {:seq (seq xs)
                     :last-value nil})
              true))


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
;; Link function factories and execution

(defn safely-apply
  "Applies f to xs, but catches Exception instances.
  Returns a pair of [result exception]."
  [f xs]
  (try [(apply f xs) nil]
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
              ;; TODO also handle add/remove links
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



#_ (def b (rmap {:f foobar
                 :link-fn-factory [sync, future, go]
                 :result-fn (fn [])
                 :error-fn (fn []) }))


;; ---------------------------------------------------------------------------
;; Queue to be used with an atom or agent

(defn make-queue
  [max-size]
  {:queue (PersistentQueue/EMPTY)
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

;; TODO
(defn rmapcat'
  [f reactive]
  (let [[make-link-fn f] (unpack-fn f)
        n-agent          (-> reactive network-id network-by-id)
        new-r            (eventstream n-agent "mapcat'")
        queue-atom       (atom [])
        complete-fn      (fn [r]
                           (let [current (first @queue-atom)
                                 new-current (first (swap! queue-atom #(->> % (remove completed?) vec)))]
                             (if (not= current new-current)
                               nil ;; NOW CHANGE THE LINKS
                               )))]
    (derive-new eventstream
                "mapcat'"
                (make-link-fn f
                              (fn [result ex inputs outputs]
                                (swap! queue-atom enqueue result)
                                
                                #_ {:add-links [(make-link "" (make-sync-link-fn identity) [result] [new-r]
                                                           {:complete-fn complete-fn})]})
                              [reactive] []))))


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


(defn rconcat
  [& reactives]
  (let [n-agent (-> reactives first network-id network-by-id)
        new-r   (eventstream n-agent "concat")
        f       (fn [inputs outputs]
                  (if-let [r (->> reactives (remove completed?) first)]
                    (if (available? r)
                      (make-result-map (consume! r) nil inputs outputs))))]
    (doseq [r reactives]
      (add-link! n-agent (make-link "concat" f [r] [new-r])))
    new-r))


(defn rswitch
  [reactive]
  (let [n-agent (-> reactive network-id network-by-id)
        new-r   (eventstream n-agent "switch")]
    (add-link! n-agent
               (make-link "switcher"
                          (fn [inputs outputs]
                            (let [r (-> inputs first consume!)]
                              {:remove-links [#(= (:outputs %) [new-r])]
                               :add-links [(make-link "switch" (make-sync-link-fn identity) [r] [new-r])]}))
                          [reactive] []))
    new-r))


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
    (add-link! n-agent (make-link "subscriber" (make-link-fn f (constantly {})) [reactive] []))
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

(comment
  (def e1 (eventstream n "e1"))
  (def e2 (eventstream n "e2"))

  (def c (rconcat e1 e2))
  (def results (atom []))
  (subscribe (partial swap! results conj) c)

  #_ (do
       (push! e2 :bar1)
       (push! e2 :bar2)
       (push! e2 :bar3)
       (push! e2 :bar4)
       (push! e1 :foo)
       (push! e1 ::completed)))


(def e1 (eventstream n "e1"))
(def e2 (eventstream n "e2"))
(def s (eventstream n "s"))
(def switched (rswitch s))
(subscribe println switched)



#_ (def r (rmap + e1 e2))
#_ (subscribe #(println %) r)

#_ (def f (->> e1 (rtake 3) (rfilter (partial = "foo"))))
#_ (subscribe println
           (rmerge f e2))




#_ (def b (->> e1
            (rbuffer 3)
            (rdelay 3000)
            (subscribe (fn [value] (println value)))))

#_ (def c (->> e1 (rmapcat #(repeat 3 %)) (subscribe #(println %))))

#_ (->> (constantly "foo")
     (rsample n 1000)
     (subscribe (fn [value] (println value))))

(comment
  (def x (behavior n "x" nil))
  (def y (behavior n "y" 2))
  (def x+y (rmap + x y))
  (def zs (->> (rmap * x x+y)
               (rreduce conj [])))


  (doseq [i (range 10)]
    (push! x i))
  (->> x+y (rdelay 3000) (subscribe #(println %))))

(comment
  (def data {:name "bar" :addresses [{:street "1"}
                                     {:street "2"}
                                     {:street "3"}]})

  (def p (behavior n "p"))
  (def a (rmapcat :addresses p))
  (def pname (rmap :name p))
  (def pnameb (rhold pname))
  (def pair (rmap vector pnameb a))
  (subscribe #(println "OUTPUT" %) pair))

:ok
