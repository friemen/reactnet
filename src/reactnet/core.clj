(ns reactnet.core
  "Implementation of a propagation network."
  (:require [clojure.string :as s])
  (:import [java.lang.ref WeakReference]
           [java.util WeakHashMap]))


;; ---------------------------------------------------------------------------
;; Concepts

;; Reactive:
;; Serves as abstraction of event streams and behaviors.

(defprotocol IReactive
  (next-value [r]
    "Returns the next value-timestamp pair of the reactive r without
    consuming it.")
  (available? [r]
    "Returns true if the reactive r will provide a value upon
    next-value / consume.")
  (pending? [r]
    "Returns true if r contains values that wait for being consumed.")
  (completed? [r]
    "Returns true if the reactive r will neither accept nor return a new value.")
  (consume! [r]
    "Returns the next value-timestamp pair of the reactive r and may
    turn the state into unavailable.")
  (deliver! [r value-timestamp-pair]
    "Sets/adds a pair of value and timestamp to r, returns true if a
  propagation of the value should be triggered."))

;; Link:
;; A map connecting input and output reactives via a function.
;;   :label               Label for pretty printing
;;   :inputs              Input reactives
;;   :outputs             Output reactives, each wrapped in WeakReference
;;   :link-fn             A link function [Result -> Result] (see below)
;;   :error-fn            An error handling function [Result -> Result] (see below)
;;   :complete-fn         A function [Link Reactive -> Result] called when one of the
;;                        input reactives becomes completed
;;   :complete-on-remove  A seq of reactives to be completed when this link is removed
;;   :executor            The executor to use for invoking the link function
;;   :level               The level within the reactive network
;;                        (max level of all input reactives + 1)

;; Link function:
;;  A function [Result -> Result] that takes a Result map containing
;;  input values and returns a Result map or nil.

;; Error handling function:
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
;;   :no-consume          Signals that the input values must not get consumed
;;   :exception           Exception, or nil if output-rvts is valid
;;   :add                 A seq of links to be added to the network
;;   :remove-by           A predicate that matches links to be removed
;;                        from the network
;;   :dont-complete       A seq of reactives that must not be completed automatically

;; Network:
;; A map containing
;;   :id                  A string containing the fqn of the agent var
;;   :links               Collection of links
;;   :rid-map             WeakHashMap {Reactive -> rid}
;;                        rid = reactive id (derived)
;;   :level-map           Map {rid -> topological-level} (derived)
;;   :links-map           Map {rid -> Seq of links} (derived)
;;   :dont-complete       Map {Reactive -> c} of reactives that are not
;;                        automatically completed as long as c > 0
;;   :pending-completions A seq of reactives that will receive ::completed
;;                        as soon as they are not contained in the
;;                        :dont-complete map
;;   :rebuild?            Flag if links have been added/removed

;; Stimulus
;; A map containing data that is passed to enq/update-and-propagate! to
;; start an update/propagation cycle on a network.
;;   :results             A seq of Result maps
;;   :remove-by           A predicate matching links to remove from the network
;;   :add                 A seq of links to add to the network
;;   :rvt-map             A map {Reactive -> [v t]} of values to propagate

;; NetworkRef:
;; Serves as abstraction of how the network is stored and
;; propagation/updates to it are scheduled.

(defprotocol INetworkRef
  (enq [netref stimulus]
    "Enqueue a new update/propagation cycle that will process a
  stimulus containing a seq of result maps, remove links by predicate,
  add new links and propagate the values in the {Reactive -> [v t]}
  map.

  Returns the netref.

  An implementation should delegate to update-and-propagate!
  function.")
  (network [netref]
    "Return the network map."))



(def ^:dynamic *netref* "A reference to a default network." nil)


;; Executor
;; Used to execute link functions in another thread / asynchronously.

(defprotocol IExecutor
  (execute [e netref f]
    "Execute a no-arg function f on a different thread with the dynamic var
    *netref* bound to netref."))



;; ---------------------------------------------------------------------------
;; Misc utilities

(defn ^:no-doc dissect
  "Returns a pair of vectors, first vector contains the xs for
  which (pred x) returns true, second vector the other xs."
  [pred xs]
  (reduce (fn [[txs fxs] x]
            (if (pred x)
              [(conj txs x) fxs]
              [txs (conj fxs x)]))
          [[] []]
          xs))

;; ---------------------------------------------------------------------------
;; Functions that operate on reactives.

(defn reactive?
  "Returns true if r satisfies IReactive."
  [r]
  (satisfies? IReactive r))


;; ---------------------------------------------------------------------------
;; Functions to extract values from RVTs


(defn value
  "Returns the value from an RVT."
  [[r [v t]]]
  v)


(defn fvalue
  "Returns the value from the first item of an RVT seq."
  [rvts]
  (-> rvts first value))


(defn values
  "Returns a vector with all extracted values from an RVT seq."
  [rvts]
  (mapv value rvts))


;; ---------------------------------------------------------------------------
;; Functions to produce RVT seqs


(defn now
  "Returns the current epoch time in milliseconds."
  []
  (System/currentTimeMillis))


(defn single-value
  "Returns a sequence with exactly one RVT pair assigned to reactive
  r."
  [v r]
  {:pre [(reactive? r)]}
  [[r [v (now)]]])


(defn broadcast-value
  "Returns an RVT seq where the value v is assigned to every reactive
  in rs."
  [v rs]
  {:pre [(every? reactive? rs)]}
  (let [t (now)]
    (for [r rs] [r [v t]])))


(defn zip-values
  "Returns an RVT seq where values are position-wise assigned to
  reactives."
  [vs rs]
  {:pre [(every? reactive? rs)]}
  (let [t (now)]
    (map (fn [r v] [r [v t]]) rs vs)))


(defn enqueue-values
  "Returns an RVT seq where all values in vs are assigned to the same
  reactive r."
  [vs r]
  {:pre [(reactive? r)]}
  (let [t (now)]
    (for [v vs] [r [v t]])))



;; ---------------------------------------------------------------------------
;; Functions on links

(defn- wref-wrap
  "Wraps all xs in a WeakReference and returns a vector of those."
  [xs]
  (mapv #(WeakReference. %) xs))

(defn- wref-unwrap
  "Unwraps all WeakReferences and returns the result as vector."
  [wrefs]
  (mapv #(.get %) wrefs))


(defn link-outputs
  "Returns the output-reactives of a link, unwrapping them from WeakReferences."
  [link]
  (-> link :outputs wref-unwrap))


(defn link-inputs
  "Returns the input-reactives of a link."
  [link]
  (-> link :inputs))



;; ---------------------------------------------------------------------------
;; Factories


(defn default-link-fn
  "A link-function that implements a pass-through of inputs to outputs.
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
  "Creates and returns a new Link map. 

  Label is an arbitrary text, inputs and outputs are sequences of
  reactives.

  Output reactives are kept using WeakReferences.
  
  The link-fn is a Link function [Result -> Result] which is called to
  produce a result from inputs-rvts. Defaults to default-link-fn.
  
  The error-fn is a function [Result -> Result] which is called when
  an exception was thrown by the Link function. Defaults to nil.

  The complete-fn is a function [Link Reactive -> Result] which is called for
  each input reactive that completes. Defaults to nil.

  The sequence complete-on-remove contains all reactives that should be
  completed when this Link is removed from the network."
  [label inputs outputs
   & {:keys [link-fn error-fn complete-fn complete-on-remove executor]
      :or {link-fn default-link-fn}}]
  {:pre [(seq inputs)
         (every? reactive? (concat inputs outputs))]}
  {:label label
   :inputs inputs
   :outputs (wref-wrap outputs)
   :link-fn link-fn
   :error-fn error-fn
   :complete-fn complete-fn
   :complete-on-remove complete-on-remove
   :executor executor
   :level 0})


(declare rebuild)


(defn make-network
  "Returns a new network."
  [id links]
  (rebuild {:id id
            :dont-complete {}}
           links))


;; ---------------------------------------------------------------------------
;; Pretty printing

(defn ^:no-doc str-react
  [r]
  (str (if (completed? r) "C " "  ") (:label r) ":" (pr-str (next-value r))))

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

(defmacro ^:no-doc dump
  [& args]
  `(when debug?
     (println ~@args)))


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

(defn- reactive-rid-map
  "Returns a WeakHashMap {Reactive -> rid} of all reactives that occur
  as inputs or outputs in links. rid is an integer value."
  [links]
  
  (let [m (->> links
               (mapcat (fn [l] (concat (link-inputs l) (link-outputs l))))
               (map #(vector %2 %1) (range)))
        wm (WeakHashMap.)]
    (doseq [[r rid] m]
      (.put wm r rid))
    wm))


(defn- rid-links-map
  "Returns a map {rid -> (Seq of links)}, where the reactive is
  an input of the links it points to."
  [rid-map links]
  (->> links
       (mapcat (fn [l]
                 (for [r (link-inputs l)] [(get rid-map r) l])))
       (reduce (fn [m [rid l]]
                 (update-in m [rid] conj l))
               {})))


(defn- rid-followers-map
  "Returns a map {rid -> (Set of following reactive ids)}."
  [rid-map links]
  (->> links
       (rid-links-map rid-map)
       (map (fn [[rid links]]
              [rid (->> links
                        (mapcat link-outputs)
                        (map (partial get rid-map))
                        set)]))
       (into {})))


(defn- rid-level-map
  "Returns a map {rid/Link -> level} containing all reactive rids and
  links in the network, where level is an integer representing
  topological order, i.e. L(r1) < L(r2) => r1 is to be touched before
  r2."
  [rid-map links]
  (let [rfm                  (rid-followers-map rid-map links)
        rfm-with-root        (assoc rfm -1 (set (keys rfm)))
        levels               (fn levels [visited level rid]
                               (if-not (visited rid)
                                 (cons [rid level]
                                       (mapcat (partial levels (conj visited rid) (+ level 2))
                                               (rfm-with-root rid)))))
        level-map-wo-root    (dissoc (->> (levels #{} -1 -1)
                                          (reduce (fn [m [rid l]]
                                                    (assoc m rid (max (or (m rid) 0) l)))
                                                  {}))
                                     -1)
        level-map-incl-links (->> links
                                  (map (fn [l]
                                         [l (->> l link-inputs
                                                 (remove nil?)
                                                 (map (partial get rid-map))
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
  "Returns true for a link if it has no inputs, or at least one of it's
  inputs is completed, or all outputs are completed. Having no outputs does
  not count as 'all outputs completed'."
  [link]
  (let [inputs (link-inputs link)
        outputs (link-outputs link)]
    (or (empty? inputs)
        (some nil? inputs)
        (some completed? inputs)
        (and (seq outputs) (every? completed? (remove nil? outputs))))))


(defn ^:no-doc pending-reactives
  "Returns a seq of pending reactives."
  [{:keys [rid-map]}]
  (->> rid-map keys (remove nil?) (filter pending?)))



;; ---------------------------------------------------------------------------
;; Modifying the network


(defn- rebuild
  "Takes a network and a set of links and re-calculates rid-map,
  links-map and level-map. Preserves other existing entries. Returns an
  updated network."
  [{:keys [id] :as n} links]
  (let [rid-map       (reactive-rid-map links)
        level-map     (rid-level-map rid-map links)
        leveled-links (mapv #(assoc % :level (level-map %)) links)]
    (assoc n
      :rid-map rid-map
      :links leveled-links
      :links-map (rid-links-map rid-map leveled-links)
      :level-map level-map
      :rebuild? false)))


(defn- rebuild-if-necessary
  "Takes a network n, returns an updated network if :rebuild? is
  truthy, else returns n."
  [{:keys [links rebuild?] :as n}]
  (if rebuild?
    (rebuild n links)
    n))


(defn- apply-exec
  "Takes a network and a seq where the first item is a function f and
  the other items are arguments. Invokes (f n arg1 arg2 ...). f must
  return a network. Omits invocation of f if it is nil."
  [n [f & args]]
  (if f
    (apply (partial f n) args)
    n))


(defn- complete-pending
  "Asynchronously completes all reactives contained in
  the :pending-completions set of the network n. Excludes reactives
  contained in the :dont-complete map. Returns an updated network."
  [{:keys [pending-completions dont-complete] :as n}]
  (let [[remaining completables] (dissect dont-complete pending-completions)]
    (doseq [r completables]
      (enq *netref* {:rvt-map {r [::completed (now)]}}))
    (assoc n :pending-completions (set remaining))))


(defn- allow-completion
  "Decreases the pending evaluations counter in
  networks :dont-complete map and removes reactives if completion is
  allowed again.  Returns an updated network."
  [{:keys [dont-complete] :as n} rs]
  (->> rs
       (reduce (fn [m r]
                 (let [c (or (some-> r m dec) 0)]
                   (if (> c 0)
                     (assoc m r c)
                     (dissoc m r))))
               dont-complete)
       (assoc n :dont-complete)))


(defn- update-links
  "Removes links specified by the predicate or set and conjoins links
  to the networks links. Returns an updated network, and flags
  with :rebuild? if a rebuild is necessary."
  [{:keys [links] :as n} remove-by-pred new-links]
  (let [[links-to-remove remaining-links] (dissect remove-by-pred links)]
    (when (seq links-to-remove)
      (dump-links "REMOVE" links-to-remove))
    (when (seq new-links)
      (dump-links "ADD" new-links))
    (-> n
        (update-in [:pending-completions] concat (->> links-to-remove
                                                      (mapcat :complete-on-remove)))
        (assoc
            :links (concat remaining-links new-links)
            :rebuild? (or (seq links-to-remove) (seq new-links))))))


(defn- update-from-results
  "Takes a network and a seq of result maps and adds / removes links.
  Returns an updated network, and flags with :rebuild? if a rebuild is
  necessary."
  [{:keys [links dont-complete] :as n} results]
  (let [dont-complete   (->> results
                             (mapcat :dont-complete)
                             (reduce (fn [m r]
                                       (assoc m r (or (some-> r m inc) 1)))
                                     dont-complete))
        links-to-remove (->> results
                             (map :remove-by)
                             (remove nil?)
                             (cons dead?)
                             (reduce (fn [ls pred]
                                       (->> n :links
                                            (filter pred)
                                            (into ls)))
                                     #{}))
        links-to-add    (->> results (mapcat :add) set)]
    (-> n
        (assoc :dont-complete dont-complete)
        (update-links links-to-remove links-to-add))))


(defn- replace-link-error-fn
  "Match the first link by pred, attach the error-fn and replace the
  link. Returns an updated network."
  [{:keys [id links] :as n} pred error-fn]
  (let [[matched others] (dissect pred links)]
    (assoc n
      :links (conj others (assoc (first matched) :error-fn error-fn))
      :rebuild? (seq matched))))


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


(defn- safely-exec-link-fn
  "Execute link-fn, catch exception and return a Result map that
  merges input with link-fn result and / or error-fn result."
  [{:keys [link-fn] :as link} input]
  (let [result        (try (link-fn input)
                           (catch Exception ex {:exception ex
                                                :dont-complete nil}))
        error-result  (handle-exception! link (merge input result))]
    (merge input result error-result)))


(defn- eval-link!
  "Evaluates one link (possibly using the links executor), returning a Result map."
  [rvt-map {:keys [link-fn level executor] :as link}]
  (let [inputs       (link-inputs link)
        outputs      (->> link link-outputs (remove nil?))
        input        {:link link
                      :input-reactives inputs 
                      :input-rvts (for [r inputs] [r (rvt-map r)])
                      :output-reactives outputs
                      :output-rvts nil
                      :dont-complete (set outputs)}
        netref       *netref*]
    (if executor
      (do (execute executor
                   netref
                   (fn []
                     (let [result-map (safely-exec-link-fn link input)]
                       ;; send changes / values to netref
                       (when (or (seq (:add result-map))
                                 (:remove-by result-map)
                                 (seq (:output-rvts result-map)))
                         (enq netref {:results [result-map]})))))
          input)
      (safely-exec-link-fn link input))))


(defn- eval-complete-fns!
  "Detects all completed input reactives, calls complete-fn for each
  link and reactive and returns the results."
  [{:keys [rid-map links links-map] :as n}]
  (let [results  (for [r (->> links
                              (mapcat link-inputs)
                              (remove nil?)
                              set
                              (filter completed?))
                       [l f] (->> r (get rid-map) links-map
                                 (map (juxt identity :complete-fn))) :when f]
                   (f l r))]
    (remove nil? results)))


(defn- next-values
  "Peeks all values from reactives, without consuming them. 
  Returns a map {Reactive -> [value timestamp]}."
  [reactives]
  (reduce (fn [rvt-map r]
            (assoc rvt-map r (next-value r)))
          {}
          reactives))


(defn- consume-values!
  "Consumes all values from reactives. 
  Returns a map {Reactive -> [value timestamp]}."
  [reactives]
  (reduce (fn [rvt-map r]
            (assoc rvt-map r (consume! r)))
          {}
          reactives))


(defn- deliver-values!
  "Updates all reactives from the reactive-values map and returns a
  seq of pending reactives."
  [rvt-map]
  (doseq [[r vt] rvt-map]
    (if (completed? r)
      (dump "WARNING: trying to deliver" vt "into completed" r)
      (deliver! r vt)))
  (->> rvt-map (map first) (filter pending?)))


(declare propagate-downstream!)


(defn- propagate!
  "Executes one propagation cycle. Returns the network."
  ([network]
     (propagate! network [] []))
  ([network pending-reactives]
     (propagate! network [] pending-reactives))
  ([{:keys [rid-map links-map level-map pending-completions dont-complete] :as network}
    pending-links pending-reactives]
     (dump "\n= PROPAGATE" (:id network) (apply str (repeat (- 47 (count (:id network))) "=")))
     (when (seq pending-completions)
       (dump "  PENDING COMPLETIONS:" (->> pending-completions (map :label) (s/join ", "))))
     (when (seq dont-complete)
       (dump "  PENDING EVALS:" (->> dont-complete (map (fn [[r c]] (str (:label r) " " c))) (s/join ", "))))
     (when (seq pending-reactives)
       (dump "  PENDING REACTIVES:" (->> pending-reactives (map :label) (s/join ", "))))
     (let [links           (->> pending-reactives
                                (map (partial get rid-map))
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
           [current-links
            pending-links] (dissect same-level? available-links)
           
           rvt-map         (->> current-links
                                (mapcat link-inputs)
                                distinct
                                next-values)
           _               (dump-values "INPUTS" rvt-map)
           link-results    (->> current-links
                                (map (partial eval-link! rvt-map)))
           consumed-rvts   (->> link-results
                                (remove :no-consume)
                                (mapcat :input-reactives)
                                set (map consume!) doall)
           compl-results   (eval-complete-fns! network)
           results         (concat link-results compl-results)
           unchanged?      (->> results (remove :no-consume) empty?)
           
           ;; apply network changes returned by link and complete functions
           network         (-> network
                               (update-from-results results)
                               complete-pending
                               rebuild-if-necessary) 
           
           all-rvts        (->> results (mapcat :output-rvts))
           _               (dump-values "OUTPUTS" all-rvts)
           upstream?       (fn [[r _]]
                             (let [r-level (level-map (get rid-map r))]
                               (or (nil? r-level) (< r-level level))))
           downstream-rvts (->> all-rvts
                                (remove upstream?)
                                (sort-by #(level-map (get rid-map (first %))) (comparator <)))
           upstream-rvts   (->> all-rvts (filter upstream?))]
       ;; push value into next cycle if reactive level is either
       ;; unknown or is lower than current level
       (doseq [[r [v t]] upstream-rvts]
         (enq *netref* {:rvt-map {r [v t]}}))
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
    (let [[rvt-map remaining-rvts] (reduce (fn [[rvm remaining] [r vt]]
                                             (if (rvm r)
                                               [rvm (conj remaining [r vt])]
                                               [(assoc rvm r vt) remaining]))
                                           [{} []]
                                           rvts)]
      (if (seq rvt-map)
        (recur (propagate! (allow-completion n (keys rvt-map))
                           pending-links
                           (deliver-values! rvt-map))
               remaining-rvts)
        (dissoc n :unchanged?)))))


(defn update-and-propagate!
  "Updates network with the contents of the stimulus map,
  delivers any values and runs propagation cycles as link-functions
  return non-empty results.  Returns the network."
  [network {:keys [exec results add remove-by rvt-map]}]
  (loop [n   (-> network
                 (apply-exec exec)
                 (update-from-results results)
                 (update-links (or remove-by #{}) add)
                 (rebuild-if-necessary)
                 (allow-completion (->> rvt-map
                                        (filter (fn [[r [v t]]]
                                                  (not= ::completed v)))
                                        (map first)))
                 (complete-pending)
                 (propagate! (deliver-values! rvt-map)))   
         prs (pending-reactives n)]
    (let [next-n      (propagate! n prs)
          progress?   (not (:unchanged? next-n))
          next-prs    (pending-reactives next-n)]
      (if (and progress? (seq next-prs))
        (recur next-n next-prs)
        next-n))))


;; ---------------------------------------------------------------------------
;; Netref related functions


(defn push!
  "Delivers a value v to the reactive r and starts a propagation
  cycle. Returns the value v."
  ([r v]
     (push! *netref* r v))
  ([netref r v]
     (push! netref r v (now)))
  ([netref r v t]
     (enq netref {:rvt-map {r [v t]}})
     v))


(defn complete!
  "Delivers the ::completed value into a reactive r and notifies the
  complete-fn handler of all links which have r as their
  input. Updates the network according to results of
  handlers. Returns ::completed."
  ([r]
     (complete! *netref* r))
  ([netref r]
     (enq netref {:rvt-map {r [::completed (now)]}})
     ::completed))


(defn add-links!
  "Adds links to the network. Returns the network ref."
  [netref & links]
  (enq netref {:add links}))


(defn remove-links!
  "Removes links from the network. Returns the network ref."
  [netref pred]
  (enq netref {:remove-by pred}))


(defn on-error
  "Installs error-fn in the link that has as only output the reactive
  r."
  [netref r error-fn]
  (enq netref {:exec [(comp rebuild-if-necessary replace-link-error-fn)
                      (fn [l]
                        (= (link-outputs l) [r]))
                      error-fn]})
  r)


(defn reset-network!
  "Removes all links and clears any other data from the network. 
  Returns :reset."
  [netref]
  (enq netref {:exec [(fn [n]
                        (rebuild (assoc n
                                   :dont-complete {}
                                   :pending-completions [])
                                 []))]})
  :reset)


(defn pp
  "Pretty print network in netref."
  ([]
     (pp *netref*))
  ([netref]
     (let [{:keys [links rid-map]} (network netref)]
       (println (str "Reactives\n" (s/join "\n" (->> rid-map
                                                     keys
                                                     (map str-react)))
                     "\nLinks\n" (s/join "\n" (map str-link links)))))))


(defn dot
  "Returns a GraphViz dot representation of the network as string."
  ([]
     (dot *netref*))
  ([netref]
     (let [r-style "shape=box, regular=1, style=filled, fillcolor=white"
           l-style "shape=oval, width=0.5, style=filled, fillcolor=grey"
           {:keys [id links rid-map]} (network netref)
           id-str (fn [x]
                    (if (reactive? x)
                      (str "\"r:" (:label x) "\"")
                      (str "\"l:" (:label x) "\"")))
           node-str (fn [style x]
                      (str (id-str x) " [label=\"" (:label x) "\", " style "];\n"))]
       (str "digraph " id " {\n"
            (->> (keys rid-map)
                 (map (partial node-str r-style))
                 sort
                 (apply str))
            (->> links
                 (map (partial node-str l-style))
                 sort
                 (apply str))
            (->> links
                 (mapcat (fn [l]
                           (concat (map vector (repeat l) (link-outputs l))
                                   (map vector (link-inputs l) (repeat l)))))
                 (map (fn [[f t]]
                        (str (id-str f) " -> " (id-str t) ";\n")))
                 sort
                 (apply str))
            "}\n"))))


(defmacro with-netref
  "Binds the given netref to the dynamic var *netref* and executes
  the expressions within that binding."
  [netref & exprs]
  `(binding [reactnet.core/*netref* ~netref]
     ~@exprs))


:ok
