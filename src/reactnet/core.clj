(ns reactnet.core
  (:require [clojure.string :as s])
  (:import [java.lang.ref WeakReference]))

;; TODOs
;; - Preserve somehow the timestamp when applying a link function:
;;   Use the max timestamp of all input values.
;; - Create unit test for cyclic deps
;; - Add pause! and resume! for the network
;; - Graphviz visualization of the network
;; - Support core.async
;; - Support interceptor?

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
;; Serves as abstraction of event streams and behaviors.

(defprotocol IReactive
  (last-value [r]
    "Returns latest value of the reactive r.")
  (available? [r]
    "Returns true if the reactive r would provide a value upon consume!.")
  (pending? [r]
    "Returns true if r contains values that wait for being consumed.")
  (completed? [r]
    "Returns true if the reactive r will neither accept nor return a new value.")
  (consume! [r]
    "Returns current value of reactive r and may turn the state into unavailable.")
  (deliver! [r value-timestamp-pair]
    "Sets/adds a pair of value and timestamp to r, returns true if a
  propagation of the value should be triggered."))

;; Engine:
;; Serves as abstraction of how the network is kept and
;; propagation/updates to it are scheduled.

(defprotocol IEngine
  (execute [e f args])
  (network [e]))

(def ^:dynamic *engine* nil)

;; Link:
;; A map connecting input and output reactives via a function.
;;   :label               Label for pretty printing
;;   :inputs              Input reactives
;;   :outputs             Output reactives
;;   :link-fn             A link function (see below) that evaluates input reactive values
;;   :error-fn            An error handler function [result ex -> Result]
;;   :complete-fn         A function [reactive -> nil] called when one of the
;;                        input reactives becomes completed
;;   :complete-on-remove  A seq of reactives to be completed when this link is removed
;;   :level               The level within the reactive network
;;                        (max level of all input reactives + 1)

;; Link function:
;;  A function [Result -> Result] that takes a Result map containing
;;  input values and returns a Result map or nil, which denotes that
;;  the function gives no clue if its invocation changed any reactive.

;; Error Handler function:
;;  A function [Result -> Result] that takes the Result containing an
;;  exception. It may return a new Result map (see below) or nil.

;; RVT:
;;  A nested pair [r [v t]] representing a value v assigned to the
;;  Reactive r at time t.
;;  Something called *rvts is a sequence of those pairs.

;; Result:
;; A map passed into / returned by a link function with the following entries
;;   :input-reactives     The links input reactives
;;   :output-reactives    The links output reactives
;;   :input-rvts          A seq of RVTs
;;   :output-rvts         A seq of RVTs
;;   :exception           Exception, or nil if output-rvts is valid
;;   :add                 A seq of links to be added to the network
;;   :remove-by           A predicate that matches links to be removed
;;                        from the network

;; Network:
;; A map containing
;;   :id                  A string containing the fqn of the agent var
;;   :links               Collection of links
;;   :reactives           Set of reactives (derived)
;;   :level-map           Map {Reactive -> topological-level} (derived)
;;   :links-map           Map {Reactive -> Seq of links} (derived)


;; ---------------------------------------------------------------------------
;; Functions to deal with RVTs

(defn ^:no-doc now
  "Returns the current epoch time in milliseconds."
  []
  (System/currentTimeMillis))


(defn value
  "Extract the value from an RVT."
  [[r [v t]]]
  v)


(defn fvalue
  "Extracts the value from the first of an RVT seq."
  [rvts]
  (-> rvts first value))


(defn values
  "Returns a vector with all extracted values from an RVT seq."
  [rvts]
  (mapv value rvts))


(defn assign-value
  "Produces a sequence with exactly one RVT pair assigned to Reactive
  r."
  ([v r]
     [[r [v (now)]]]))


(defn broadcast-value
  "Produces a RVT seq where the value v is assigned to every Reactive
  in rs."
  [v rs]
  (let [t (now)]
    (for [r rs] [r [v t]])))


(defn zip-values
  "Produces an RVT seq where values are position-wise assigned to
  reactives."
  [vs rs]
  (let [t (now)]
    (map (fn [r v] [r [v t]]) rs vs)))


(defn enqueue-values
  "Produces an RVT seq where all values in vs are assigned to the same
  Reactive r."
  [vs r]
  (let [t (now)]
    (for [v vs] [r [v t]])))


(defn- wref-wrap
  [xs]
  (mapv #(WeakReference. %) xs))

(defn- wref-unwrap
  [wrefs]
  (mapv #(.get %) wrefs))


(defn link-outputs
  [link]
  (-> link :outputs wref-unwrap))


(defn link-inputs
  [link]
  (-> link :inputs wref-unwrap))



;; ---------------------------------------------------------------------------
;; Factories


(defn default-link-fn
  "Pass thru of inputs to outputs.
  If there is more than one input reactive, zips values of all inputs
  into a vector, otherwise takes the single value.  Returns a Result
  map with the extracted value assigned to all output reactives."
  [{:keys [input-rvts input-reactives output-reactives] :as input}]
  (let [v (case (count input-reactives)
            0 nil
            1 (fvalue input-rvts)
            (values input-rvts))]
    (assoc input :output-rvts (broadcast-value v output-reactives))))


(defn make-link
  "Creates a new Link. Label is an arbitrary text, inputs and outputs
  are sequences of reactives. 
  
  The link-fn is a Link function [inputs outputs -> Result] which is
  called to produce a result from inputs (if all inputs are
  available). Defaults to default-link-fn.
  
  The error-fn is a function [Link Result -> Result] which is called when
  an exception was thrown by the Link function. Defaults to nil.

  The complete-fn is a function [Link Reactive -> Result] which is called for
  each input reactive that completes. Defaults to nil.

  The sequence complete-on-remove contains all reactives that should be
  completed when this Link is removed from the network."
  [label inputs outputs
   & {:keys [link-fn error-fn complete-fn complete-on-remove]
      :or {link-fn default-link-fn}}]
  {:pre [(seq inputs)]}
  {:label label
   :inputs (wref-wrap inputs)
   :outputs (wref-wrap outputs)
   :link-fn link-fn
   :error-fn error-fn
   :complete-fn complete-fn
   :complete-on-remove complete-on-remove
   :level 0})


(declare rebuild)


(defn make-network
  "Returns a new network."
  [id links]
  (rebuild {:id id} links))


;; ---------------------------------------------------------------------------
;; Pretty printing

(defn ^:no-doc str-react
  [r]
  (str (if (completed? r) "C " "  ") (:label r) ":" (pr-str (last-value r))))

(declare dead?)

(defn ^:no-doc str-link  
  [l]
  (str "  L" (:level l)
       " [" (s/join " " (map :label (link-inputs l)))
       "] -- " (:label l) " --> ["
       (s/join " " (mapv :label (link-outputs l)))
       "] " (cond
             (every? available? (->> l link-inputs (remove nil?))) "READY"
             (dead? l) "DEAD"
             :else "incomplete")))


(defn ^:no-doc str-rvalue
  [[r [v timestamp]]]
  (str (:label r) ": " v))


(defn ^:no-doc str-rvalues
  [[r vs]]
  (str (:label r) ": [" (->> vs (map first) (s/join ", ")) "]"))


(def ^:no-doc debug? false)

(defn ^:no-doc dump
  [& args]
  (when debug?
    (apply println args))
  (first args))


(defn ^:no-doc dump-links
  [label links]
  (if (seq links)
    (do
      (dump "-" label (apply str (repeat (- 57 (count label)) \-)))
      (dump (->> links (map str-link) (s/join "\n")))
      (dump (apply str (repeat 60 \-))))
    (dump "-" label "- No links")))


(defn ^:no-doc dump-values
  [label rvts]
  (if (seq rvts)
    (dump label (->> rvts
                     (map (fn [[r [v t]]]
                            (str (:label r) " " v)))
                     (s/join ", ")))))


;; ---------------------------------------------------------------------------
;; Getting information about the reactive graph

(defn ^:no-doc reactives-from-links
  "Returns a set of all reactives occurring as inputs our outputs in
  links."
  [links]
  (->> links
       (mapcat (fn [l] (concat (link-inputs l) (link-outputs l))))
       set))


(defn ^:no-doc reactive-links-map
  "Returns a map {Reactive -> (Seq of links)}, where the reactive is
  an input of the links it points to."
  [links]
  (->> links
       (mapcat (fn [l]
                 (for [r (link-inputs l)] [r l])))
       (reduce (fn [m [r l]]
                 (update-in m [r] conj l))
               {})))


(defn ^:no-doc reactive-followers-map
  "Returns a map {Reactive -> (Set of following reactives)}."
  [links]
  (->> links
       reactive-links-map
       (map (fn [[r links]]
              [r (->> links (mapcat link-outputs) set)]))
       (into {})))


(defn ^:no-doc reactive-level-map
  "Returns a map {Reactive/Link -> level} containing all reactives and
  links in the network, where level is an integer representing
  topological order, i.e. L(r1) < L(r2) => r1 is to be touched before r2."
  [links]
  (let [root                 (atom nil)
        rfm                  (reactive-followers-map links)
        rfm-with-root        (assoc rfm root (set (keys rfm)))
        levels               (fn levels [visited level reactive]
                               (if-not (visited reactive)
                                 (cons [reactive level]
                                       (mapcat (partial levels (conj visited reactive) (+ level 2))
                                               (rfm-with-root reactive)))))
        level-map-wo-root    (dissoc (->> (levels #{} 0 root)
                                          (reduce (fn [m [r l]]
                                                    (assoc m r (max (or (m r) 0) l)))
                                                  {}))
                                     root)
        level-map-incl-links (->> links
                                  (map (fn [l]
                                         {:pre [(->> l link-inputs (remove nil?) seq)]}
                                         [l (->> l link-inputs
                                                 (remove nil?)
                                                 (map level-map-wo-root)
                                                 (reduce max)
                                                 inc)]))
                                  (into level-map-wo-root))]
    level-map-incl-links))



(defn ^:no-doc ready?
  "Returns true for a link if
  - all inputs are available,
  - at least one output is not completed."
  [link]
  (let [inputs (link-inputs link)
        outputs (link-outputs link)]
    (and (every? available? inputs)
         (remove completed? outputs))))


(defn ^:no-doc dead?
  "Returns true for a link if has not inputs, or at least one of it's
  inputs is completed, or all outputs are completed. Empty outputs does
  not count as 'all outputs completed'."
  [link]
  (let [inputs (link-inputs link)
        outputs (link-outputs link)]
    (or (empty? inputs)
        (some nil? inputs)
        (some completed? inputs)
        (and (seq outputs) (every? completed? outputs)))))


;; ---------------------------------------------------------------------------
;; Modifying the network


(defn- rebuild
  "Takes a network and a set of links and re-calculates reactives,
  links-map and level-map. Preserves other existing entries. Returns a
  new network."
  [{:keys [id] :as n} links]
  (let [level-map     (reactive-level-map links)
        leveled-links (mapv #(assoc % :level (level-map %)) links)]
    (assoc n
      :reactives (reactives-from-links leveled-links)
      :links leveled-links
      :links-map (reactive-links-map leveled-links)
      :level-map level-map)))


(defn add-links
  "Conjoins links to the networks links. Returns a
  new, rebuilded network."
  [{:keys [links] :as n} new-links]
  (rebuild n (concat links new-links)))


(declare push!)

(defn- complete-for-links!
  "Asynchronously completes all reactives contained in
  the :complete-on-remove seq of the given links."
  [links]
  (doseq [r (->> links
                 (mapcat :complete-on-remove))]
    (push! r ::completed)))


(defn remove-links
  "Removes links matched by predicate pred and returns a new,
  rebuilded network."
  [{:keys [links] :as n} pred]
  (let [links-to-remove (remove pred links)]
    (complete-for-links! links-to-remove)
    (rebuild n links-to-remove)))


(defn update-from-results!
  "Takes a network and a seq of result maps and returns an updated
  network, with links added and removed. Completes reactives
  referenced by removed links :complete-on-remove seq."
  [{:keys [links] :as n} results]
  (let [links-to-remove (->> results
                             (map :remove-by)
                             (remove nil?)
                             (cons dead?)
                             (reduce (fn [ls pred]
                                       (->> n :links
                                            (filter pred)
                                            (into ls)))
                                     #{}))
        links-to-add    (->> results (mapcat :add) set)]
    (complete-for-links! links-to-remove)
    (if (or (seq links-to-add) (seq links-to-remove))
      
      (do (when (seq links-to-remove)
            (dump-links "REMOVE" links-to-remove))
          (when (seq links-to-add)
            (dump-links "ADD" links-to-add))
          
          (->> n :links
               (remove links-to-remove)
               (concat links-to-add)
               (rebuild n)))
      n)))


;; ---------------------------------------------------------------------------
;; Propagation within network


(defn- handle-exception!
  "Invokes the links error-fn function and returns its Result map, or
  prints stacktrace if the link has no error-fn."
  [{:keys [error-fn] :as link} {:keys [exception] :as result}]
  (when exception
    (if error-fn
      (error-fn result)
      (.printStackTrace exception))))


(defn- eval-link!
  "Evaluates one link, returning Result map, or nil if the link function
  returned nil."
  [rvt-map {:keys [link-fn level] :as link}]
  (let [inputs       (link-inputs link)
        input        {:link link
                      :input-reactives inputs 
                      :input-rvts (for [r inputs] [r (rvt-map r)])
                      :output-reactives (->> link link-outputs (remove nil?))
                      :output-rvts nil}
        result        (try (link-fn input)
                           (catch Exception ex {:exception ex}))
        error-result  (handle-exception! link (merge input result))]
    (if result
      (merge input result error-result))))


(defn- eval-complete-fns!
  "Detects all completed input reactives, calls complete-fn for each
  link and reactive and returns the results."
  [{:keys [reactives links links-map] :as n}]
  (let [results  (for [r (->> links
                              (mapcat link-inputs)
                              set
                              (filter completed?))
                       [l f] (->> r links-map
                                 (map (juxt identity :complete-fn))) :when f]
                   (f l r))]
    (remove nil? results)))


(defn- consume-values!
  "Consumes all values from reactives. 
  Returns a map {Reactive -> [value timestamp]}."
  [reactives]
  (reduce (fn [rvt-map r]
            (assoc rvt-map r (consume! r)))
          {}
          reactives))


(defn- deliver-values!
  "Updates all reactives from the reactive-values map and returns this
  map."
  [rvt-map]
  (doseq [[r vt] rvt-map]
    (when-not (completed? r)
      (deliver! r vt)))
  (map first rvt-map))


(declare propagate-downstream!)


(defn ^:no-doc propagate!
  "Executes one propagation cycle. Returns the network."
  ([network]
     (propagate! network [] []))
  ([network pending-reactives]
     (propagate! network [] pending-reactives))
  ([network pending-links pending-reactives]
     (dump "\n= PROPAGATE" (:id network) (apply str (repeat (- 47 (count (:id network))) "=")))
     (when (seq pending-reactives)
       (dump "  PENDING:"(->> pending-reactives (map :label) (s/join ", "))))
     (let [links-map       (:links-map network)
           level-map       (:level-map network)
           links           (->> pending-reactives
                                (mapcat links-map)
                                (concat pending-links)
                                (sort-by :level (comparator <))
                                distinct)
           _               (dump-links "CANDIDATES" links)
           available-links (->> links
                                (filter ready?)
                                doall)
           level           (or (-> available-links first :level) 0)
           same-level?     (fn [l] (= (:level l) level))
           current-links   (->> available-links
                                (filter same-level?))
           pending-links   (->> available-links
                                (remove same-level?))
           
           rvt-map         (->> current-links
                                (mapcat link-inputs)
                                distinct
                                consume-values!)
           _               (dump-values "INPUTS" rvt-map)
           link-results    (->> current-links
                                (map (partial eval-link! rvt-map))
                                (remove nil?))
           compl-results   (eval-complete-fns! network)
           results         (concat link-results compl-results)
           unchanged?      (empty? results)
           
           ;; apply network changes returned by link and complete functions
           network         (update-from-results! network results)
           all-rvts        (->> results (mapcat :output-rvts))
           _               (dump-values "OUTPUTS" all-rvts)
           upstream?       (fn [[r _]]
                             (let [r-level (level-map r)]
                               (or (nil? r-level) (< r-level level))))
           downstream-rvts (->> all-rvts
                                (remove upstream?)
                                (sort-by (comp level-map first) (comparator <)))
           upstream-rvts   (->> all-rvts (filter upstream?))]
       ;; push value into next cycle if reactive level is either
       ;; unknown or is lower than current level
       (doseq [[r [v t]] upstream-rvts]
         (push! r v t))
    
       (if unchanged?
         (assoc network :unchanged? true)
         (propagate-downstream! network
                                pending-links
                                downstream-rvts)))))


(defn- propagate-downstream!
  "Propagate values to reactives that are guaranteed to be downstream."
  [network pending-links downstream-rvts]
  (loop [n network
         rvts downstream-rvts] 
    (let [[rvtm remaining-rvts] (reduce (fn [[rvm remaining] [r vt]]
                                          (if (rvm r)
                                            [rvm (conj remaining [r vt])]
                                            [(assoc rvm r vt) remaining]))
                                        [{} []]
                                        rvts)]
      (if (seq rvtm)
        (recur (propagate! n pending-links (deliver-values! rvtm))
               remaining-rvts)
        (dissoc n :unchanged?)))))


(defn update-and-propagate!
  "Updates reactives with the contents of the reactive-values map,
  and runs propagation cycles as long as values are consumed. 
  Returns the network."
  [{:keys [reactives] :as network} rvt-map]
  (loop [n                 (propagate! network (deliver-values! rvt-map))
         pending-reactives (->> n :reactives (filter pending?))]
    (let [next-n      (propagate! n pending-reactives)
          progress?   (not (:unchanged? next-n))
          next-prs    (->> n :reactives (filter pending?))]
      (if (and progress? (seq next-prs))
        (recur next-n next-prs)
        n))))


(defn complete-and-propagate!
  "Takes a network and a reactive, delivers the ::completed value into
  the reactive and returns an updated network."
  [{:keys [links-map] :as n} reactive]
  (deliver! reactive [::completed (now)])
  (update-and-propagate! n nil))



(defn push!
  "Starts an update of a reactive and a propagation cycle using
  the engines execute function. Returns the value."
  ([reactive value]
     (push! *engine* reactive value))
  ([engine reactive value]
     (push! engine reactive value (now)))
  ([engine reactive value timestamp]
     (execute engine update-and-propagate! [{reactive [value timestamp]}])
     value))


(defn complete!
  "Delivers the ::completed value into a reactive and notifies the
  complete-fn handler of all links that the reactive is an input
  of. Updates the network according to results of handlers."
  ([reactive]
     (complete! *engine* reactive))
  ([engine reactive]
     (execute engine complete-and-propagate! [reactive])
     ::completed))



;; ---------------------------------------------------------------------------
;; Engine implementations and related functions


(defn add-links!
  "Adds links to the network using execute on the
  networks engine. Returns the network engine."
  [engine & links]
  (execute engine add-links [links])
  (execute engine update-and-propagate! [nil]))


(defn remove-links!
  "Removes links from the network using execute on the
  networks engine. Returns the network engine."
  [engine pred]
  (execute engine remove-links [pred])
  (execute engine update-and-propagate! [nil]))


(defn pp
  "Pretty print network in agent."
  [engine]
  (let [{:keys [links reactives values]} (network engine)]
    (println (str "Reactives\n" (s/join "\n" (map str-react reactives))
                  "\nLinks\n" (s/join "\n" (map str-link links))))))


(defmacro with-engine
  "Binds the given engine to the dynamic var *engine* and executes
  the expressions within that binding."
  [engine & exprs]
  `(binding [reactnet.core/*engine* ~engine]
     ~@exprs))



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


;; ---------------------------------------------------------------------------
;; Tools for implementing Link functions


(defn safely-apply
  "Applies f to xs, and catches exceptions.
  Returns a pair of [result exception], at least one of them being nil."
  [f xs]
  (try [(apply f xs) nil]
       (catch Exception ex (do (.printStackTrace ex) [nil ex]))))


(defn make-result-map
  "Input is a Result map as passed into a Link function. If the
  exception ex is nil produces a broadcasting output-rvts, otherwise
  adds the exception. Returns an updated Result map."
  ([input value]
     (make-result-map input value nil))
  ([{:keys [output-reactives] :as input} value ex]
     (assoc input 
       :output-rvts (if-not ex (broadcast-value value output-reactives))
       :exception ex)))


(defn make-async-link-fn
  [f result-fn]
  (fn [{:keys [input-reactives input-rvts] :as input}]
    (future (let [[v ex]      (safely-apply f (values input-rvts))
                  result-map  (result-fn input v ex)]
              ;; send changes / values to network agent
              (when (or (seq (:add result-map)) (:remove-by result-map))
                (execute *engine* update-from-results! [[result-map]]))
              (doseq [[r [v t]] (:output-rvts result-map)]
                (push! r v))))
    nil))


(defn make-sync-link-fn
  ([f]
     (make-sync-link-fn f make-result-map))
  ([f result-fn]
     (fn [{:keys [input-rvts] :as input}]
       (let [[v ex] (safely-apply f (values input-rvts))]
         (result-fn input v ex)))))

:ok
