(ns reactnet.core
  "Implementation of a propagation network."
  (:require [clojure.string :as s])
  (:import [java.lang.ref WeakReference]
           [java.util WeakHashMap]))

;; TODO
;; Fix on-error
;; add-link, use reduction to update level-map
;; consume! shall return true/false to signal completion
;; completion might happen through direct delivery (behavior!)
;;  --> an occasional dead? check is therefore still useful / required
;; improve monitoring
;; - dynamic monitor creation
;; - use own ns
;; - use a :type key to distingusih between value and time monitor
;; - add avg, min, max to time monitor
;; - put a monitors map into a netref

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
;;   :next-rid            Atom containing the next rid to assign
;;   :level-map           Map {rid -> topological-level} (derived)
;;   :links-map           Map {rid -> Seq of links} (derived)
;;   :dont-complete       Map {Reactive -> c} of reactives that are not
;;                        automatically completed as long as c > 0
;;   :pending-completions A seq of reactives that will receive ::completed
;;                        as soon as they are not contained in the
;;                        :dont-complete map

;; Stimulus
;; A map containing data that is passed to enq/update-and-propagate! to
;; start an update/propagation cycle on a network.
;;   :results             A seq of Result maps
;;   :exec                An fn [network & args -> network]
;;   :remove-by           A predicate matching links to remove from the network
;;   :add                 A seq of links to add to the network
;;   :rvt-map             A map {Reactive -> [v t]} of values to propagate
;;   :allow-complete      A set of reactives for which to decrease the
;;                        dont-complete counter

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
  (scheduler [netref]
    "Return the scheduler.")
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


(defn- any-pred?
  "Returns true if x matches any of the given predicates."
  [preds x]
  (when (seq preds)
    (or ((first preds) x)
        (recur (next preds) x))))


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
   :executor executor})


(declare rebuild)

(defn make-network
  "Returns a new network."
  [id links]
  (rebuild {:id id
            :dont-complete {}
            :pending-completions nil} links))



;; ---------------------------------------------------------------------------
;; Profiling

(defn ^:no-doc prof-update-time
  [a start stop]
  (swap! a (fn [{:keys [n nanos] :as m}]
             (let [nanos (+ (or nanos 0) (- stop start))
                   millis (int (/ nanos 1e6))]
               (assoc m
                 :n (inc (or n 0))
                 :millis millis
                 :nanos nanos)))))


(defn ^:no-doc prof-update-value
  [a v]
  (swap! a (fn [{:keys [n sum max min avg] :as m}]
             (let [new-n   (inc (or n 0))
                   new-sum (+ (or sum 0) v)]
               (assoc m
                 :n new-n
                 :sum new-sum
                 :max (clojure.core/max v (or max Long/MIN_VALUE))
                 :min (clojure.core/min v (or min Long/MAX_VALUE))
                 :avg (float (/ new-sum new-n)))))))


;; TODO attach them to the netref
(def monitors {'update-from-results (atom nil)
               'add-link (atom nil)
               'remove-links (atom nil)
               'propagate1 (atom nil)
               'propagate2 (atom nil)
               'propagate3 (atom nil)
               'propagate4 (atom nil)
               'propagate5 (atom nil)
               'propagate6 (atom nil)
               'propagate7 (atom nil)
               'propagate8 (atom nil)
               'propagate9 (atom nil)
               'propagatea (atom nil)
               'propagateb (atom nil)
               'propagatec (atom nil)
               'propagated (atom nil)
               'propagatee (atom nil)
               'propagatef (atom nil)
               'propagateg (atom nil)
               'propagateh (atom nil)
               'pending-reactives (atom nil)
               'update-and-propagate! (atom nil)
               'eval-link! (atom nil)
               'eval-complete-fns! (atom nil)
               'test (atom nil)})


(def ^:no-doc profile? true)

(defmacro ^:no-doc prof-time
  [key & exprs]
  `(if profile?
     (let [start# (System/nanoTime)
           result# (do ~@exprs)
           stop# (System/nanoTime)]
       (prof-update-time (get monitors ~key) start# stop#)
       result#)
     (do ~@exprs)))


(defmacro ^:no-doc prof-value
  [key v]
  `(when profile?
     (prof-update-value (get monitors ~key) ~v)))


(defn ^:no-doc print-monitors
  []
  (doseq [[s a] monitors]
    (println s @a)))


(defn ^:no-doc reset-monitors
  []
  (doseq [[k a] monitors]
    (reset! a nil)))


;; ---------------------------------------------------------------------------
;; Pretty printing

(defn ^:no-doc str-react
  [r]
  (str (if (completed? r) "C " "  ") (:label r) ":" (pr-str (next-value r))))

(declare dead?)

(defn ^:no-doc str-link  
  [l]
  (str " [" (s/join " " (map :label (link-inputs l)))
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
  [{:keys [rid-map links-map]}]
  (prof-time
   'pending-reactives
   (->> rid-map
        keys
        (remove nil?)
        (filter pending?)
        doall)))



;; ---------------------------------------------------------------------------
;; Modifying the network


(defn- update-rid-map
  "Adds input and output reactives to rid-map.
  Returns an updated network."
  [{:keys [next-rid rid-map] :as n} link]
  (doseq [r (concat (link-outputs link)
                    (link-inputs link))]
    (when-not (.get rid-map r)
      (.put rid-map r (swap! next-rid inc))))
  n)


(defn- adjust-downstream-levels
  "Takes reactive ids and does a breadth first tree walk to update level-map.
  Returns a level-map."
  [rid-map links-map level-map rids level]
  (loop [lm      level-map
         crs     rids
         lv      level
         visited #{}]
    (if-let [rids (seq (remove visited crs))]
      (let [ls (mapcat links-map rids)]
        (recur (merge lm
                      (into {} (map vector rids (repeat lv)))
                      (into {} (map vector ls (repeat (inc lv)))))
               (->> ls
                    (mapcat link-outputs)
                    (map (partial get rid-map)))
               (+ lv 2)
               (into visited rids)))
      lm)))


(defn- add-link
  "Adds a link to the network. Returns an updated network."
  [{:keys [rid-map links links-map level-map] :as n} l]
  (prof-time
   'add-link
   (update-rid-map n l)
   (let [input-rids  (->> l link-inputs (map (partial get rid-map)))
         output-rids (->> l link-outputs (map (partial get rid-map)))
         level-map   (->> input-rids
                          (map level-map)
                          (map #(vector %1 (or %2 1)) input-rids)
                          (into level-map))
         link-level  (->> input-rids
                          (map level-map)
                          (apply max)
                          inc)
         links-map  (reduce (fn [m i]
                              (if-let [lset (get m i)]
                                (assoc m i (conj lset l))
                                (assoc m i #{l})))
                            links-map
                            input-rids)]
     (assoc n
       :links (conj links l)
       :links-map links-map
       :level-map (adjust-downstream-levels rid-map
                                            links-map
                                            (assoc level-map l link-level)
                                            output-rids
                                            (inc link-level))))))


(defn- rebuild
  "Takes a network and a set of links and re-calculates rid-map,
  links-map and level-map. Preserves other existing entries. Returns an
  updated network."
  [{:keys [id] :as n} links]
  (reduce add-link (assoc n
                     :rid-map (WeakHashMap.)
                     :pending-removes 0
                     :links []
                     :links-map {}
                     :level-map {}
                     :next-rid (atom 1))
          links))


(defn- remove-links
  "Removes links specified by predicate or set.
  Returns an updated network."
  [{:keys [rid-map links links-map level-map pending-completions pending-removes] :as n} pred]
  (prof-time
   'remove-links
   (let [[links-to-remove
          remaining-links] (dissect pred links)]
     (when (seq links-to-remove)
       (dump-links "REMOVE" links-to-remove))
     
     (assoc (if (> (or pending-removes 0) 100)
              (rebuild n remaining-links)
              (let [input-rids       (->> links-to-remove
                                          (mapcat link-inputs)
                                          (map (partial get rid-map)))
                    links-map        (reduce (fn [lm i]
                                               (let [lset (apply disj (get lm i) links-to-remove)]
                                                 (if (empty? lset)
                                                   (dissoc lm i)
                                                   (assoc lm i lset))))
                                             links-map
                                             input-rids)
                    level-map        (reduce dissoc level-map links-to-remove) ]
                (assoc n
                  :links remaining-links
                  :pending-removes (+ (count links-to-remove) (or pending-removes 0))
                  :links-map links-map
                  :level-map level-map)))
       :pending-completions (concat pending-completions
                                    (->> links-to-remove
                                         (mapcat :complete-on-remove)))))))


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
  "Removes links specified by the predicate or set and conjoins
  new-links to the networks links. Returns an updated network."
  [n remove-by-pred new-links]
  (when (seq new-links)
    (dump-links "ADD" new-links))
  (reduce add-link (remove-links n remove-by-pred) new-links))


(defn- update-from-results
  "Takes a network and a seq of result maps and adds / removes links.
  Returns an updated network."
  [{:keys [links rid-map links-map dont-complete] :as n} completed-rs results]
  (prof-time
   'update-from-results
   (let [dont-complete   (->> results
                              (mapcat :dont-complete)
                              (reduce (fn [m r]
                                        (assoc m r (or (some-> r m inc) 1)))
                                      dont-complete))
         _ (comment
           links-to-remove (->> completed-rs
                                (map (partial get rid-map))
                                (mapcat links-map)
                                set)
           remove-by-preds  (->> results
                                 (map :remove-by)
                                 (remove nil?))
           remove-by        (if (empty? links-to-remove)
                              remove-by-preds
                              (conj remove-by-preds links-to-remove)))
         #_ (println (count completed-rs))
         links-to-remove  (->> results
                                 (map :remove-by)
                                 (remove nil?)
                                 (cons dead?)
                                 (reduce (fn [ls pred]
                                           (->> links
                                                (filter pred)
                                                (into ls)))
                                         #{}))
         links-to-add    (->> results (mapcat :add) set)]
     (-> n
         (assoc :dont-complete dont-complete)
         (update-links links-to-remove links-to-add)
         #_ (update-links (partial any-pred? remove-by) links-to-add)))))


(defn- replace-link-error-fn
  "Match the first link by pred, attach the error-fn and replace the
  link. Returns an updated network."
  [{:keys [id links] :as n} pred error-fn]
  (let [[matched others] (dissect pred links)]
    (assoc n
      :links (conj others (assoc (first matched) :error-fn error-fn))
      ;; TODO replace link everywhere in links AND level-map, uh!
      )))


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
  (prof-time
   'eval-link!
   (let [inputs  (link-inputs link)
         outputs (->> link link-outputs (remove nil?))
         input   {:link link
                  :input-reactives inputs 
                  :input-rvts (for [r inputs] [r (rvt-map r)])
                  :output-reactives outputs
                  :output-rvts nil}
         netref  *netref*]
     (if executor
       (do (execute executor
                    netref
                    (fn []
                      (let [result-map (safely-exec-link-fn link input)]
                        ;; send changes / values to netref
                        (enq netref {:results [result-map]
                                     :allow-complete (set outputs)}))))
           (assoc input :dont-complete (set outputs)))
       (safely-exec-link-fn link input)))))


(defn- eval-complete-fns!
  "Detects all completed input reactives, calls complete-fn for each
  link and reactive and returns the results."
  [{:keys [rid-map links links-map] :as n}]
  (prof-time
   'eval-complete-fns!
   (let [completed-rs (->> links
                           (filter :complete-fn)
                           (mapcat link-inputs)
                           (remove nil?)
                           (filter completed?)
                           set)
         results  (for [r completed-rs
                        [l f] (->> r (get rid-map) links-map
                                   (map (juxt identity :complete-fn))) :when f]
                    (f l r))]
     (remove nil? results))))


(defn- next-values
  "Peeks all values from reactives, without consuming them. 
  Returns a map {Reactive -> [value timestamp]}."
  [reactives]
  (reduce (fn [rvt-map r]
            (assoc rvt-map r (next-value r)))
          {}
          reactives))


(defn- consume-values!
  "Consumes all values from reactives contained in evaluation results. 
  Returns a seq of completed reactives."
  [results]
  (let [reactives (->> results
                       (remove :no-consume)
                       (mapcat :input-reactives)
                       set)]
    (doseq [r reactives]
      (consume! r))
    (filter completed? reactives)))


(defn- deliver-values!
  "Updates all reactives from the reactive-values map and returns a
  seq of pending reactives."
  [rvt-map]
  (doseq [[r vt] rvt-map]
    (if (completed? r)
      (dump "WARNING: trying to deliver" vt "into completed" r)
      (try (deliver! r vt)
           (catch IllegalStateException ex
             (enq *netref* {:rvt-map {r vt}})))))
  (->> rvt-map (map first) (filter pending?)))



(defn- eval-pending-reactives
  [{:keys [rid-map links-map level-map] :as n} pending-links pending-reactives]
  (let [links           (->> pending-reactives
                             (map (partial get rid-map))
                             (mapcat links-map)
                             (concat pending-links)
                             (sort-by level-map (comparator <))
                             distinct)
        _               (dump-links "CANDIDATES" links)
        available-links (->> links
                             (filter ready?)
                             doall)
        level           (or (-> available-links first level-map) 0)
        same-level?     (fn [l] (= (level-map l) level))
        [current-links
         pending-links] (dissect same-level? available-links)
        rvt-map         (->> current-links
                             (mapcat link-inputs)
                             distinct
                             next-values)
        _               (dump-values "INPUTS" rvt-map)
        link-results    (->> current-links
                             (map (partial eval-link! rvt-map)))]
    [level pending-links link-results]))



(declare propagate-downstream!)


(defn- propagate!
  "Executes one propagation cycle. Returns the network."
  ([network]
     (propagate! network [] []))
  ([network pending-reactives]
     (propagate! network [] pending-reactives))
  ([{:keys [rid-map links-map level-map pending-completions dont-complete] :as network}
    pending-links pending-reactives]
     (do
       (dump "\n= PROPAGATE" (:id network) (apply str (repeat (- 47 (count (:id network))) "=")))
       (when (seq pending-completions)
         (dump "  PENDING COMPLETIONS:" (->> pending-completions (map :label) (s/join ", "))))
       (when (seq dont-complete)
         (dump "  PENDING EVALS:" (->> dont-complete (map (fn [[r c]] (str (:label r) " " c))) (s/join ", "))))
       (when (seq pending-reactives)
         (dump "  PENDING REACTIVES:" (->> pending-reactives (map :label) (s/join ", ")))))
     (let [#_ network         #_ (if (empty? pending-reactives)
                             (remove-links network dead?)
                             network)
           [level
            pending-links
            link-results]  (eval-pending-reactives network pending-links pending-reactives)

           
           completed-rs    (concat (consume-values! link-results) (filter completed? pending-reactives))
           compl-results   (eval-complete-fns! network)
           results         (concat link-results compl-results)

           
           unchanged?      (->> results (remove :no-consume) empty?)
           
           ;; apply network changes returned by link and complete functions
           network         (-> network
                               (update-from-results completed-rs results)
                               complete-pending)
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
        (recur (propagate! n pending-links (deliver-values! rvt-map))
               remaining-rvts)
        (dissoc n :unchanged?)))))


(defn update-and-propagate!
  "Updates network with the contents of the stimulus map,
  delivers any values and runs propagation cycles as link-functions
  return non-empty results.  Returns the network."
  [network {:keys [exec results add remove-by rvt-map allow-complete]}]
  (prof-time
   'update-and-propagate!
   (loop [n   (-> network
                  (apply-exec exec)
                  (update-from-results nil results)
                  (update-links (or remove-by #{}) add)
                  (allow-completion allow-complete)
                  (complete-pending)
                  (propagate! add (deliver-values! rvt-map))
                  (propagate-downstream! nil (mapcat :output-rvts results)))   
          prs (pending-reactives n)]
     (let [next-n      (propagate! n prs)
           progress?   (not (:unchanged? next-n))
           next-prs    (pending-reactives next-n)]
       (if (and progress? (seq next-prs))
         (recur next-n next-prs)
         next-n)))))


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
  (enq netref {:exec [replace-link-error-fn
                      (fn [l]
                        (= (link-outputs l) [r]))
                      error-fn]})
  r)


(defn reset-network!
  "Removes all links and clears any other data from the network. 
  Returns :reset."
  [netref]
  (enq netref {:exec [(fn [n]
                        (make-network (:id n) []))]})
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
