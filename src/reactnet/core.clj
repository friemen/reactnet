(ns reactnet.core
  (:require [clojure.string :as s]))

;; TODOs
;; - Add a test to show that one input value can be consumed from many links
;; - Think about preserving somehow the timestamp when applying a link function:
;;   Use the max timestamp of all input values.
;; - Create unit test for cyclic deps
;; - Handle the initial state of the network
;; - Add pause! and resume! for the network
;; - Graphviz visualization of the network
;; - Support core.async
;; - Support interceptor?
;; - Make scheduler available in different ns, support at and at-fixed-rate 


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
  (network-id [r]
    "Returns a string containing the fully qualified name of a network
  agent var.")
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


;; Link:
;; A map connecting input and output reactives via a function.
;;  :label               Label for pretty printing
;;  :inputs              Input reactives
;;  :outputs             Output reactives
;;  :eval-fn             A link function (see below) that evaluates input reactive values
;;  :error-fn            An error handler function [result ex -> Result]
;;  :complete-fn         A function [reactive -> nil] called when one of the
;;                       input reactives becomes completed
;;  :complete-on-remove  A seq of reactives to be completed when this link is removed
;;  :level               The level within the reactive network
;;                       (max level of all input reactives + 1)

;; Link function:
;; A function [inputs outputs -> Result] that takes two seqs of input
;; and output reactives and returns a Result map (see below) or nil,
;; which denotes that the function gives no clue if its invocation
;; changed any reactive.

;; Error Handler function:
;; A function [Link Result -> Result] that takes the Link and the
;; Result map that the link function returned. It may return a new
;; Result map (see below) or nil.

;; Result:
;; A map returned by a link function with the following entries
;;  :input-values     A map {reactive -> value} containing the input values
;;  :output-values    A map {reactive -> value} containing the values for
;;                    each output reactive, or a vector containing such
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

(defn ^:no-doc now
  []
  (System/currentTimeMillis))


(defn- default-link-fn
  "If more than one input, zips values of all inputs into a vector,
  otherwise take the single value.  Returns a Result map with the
  extracted value assigned to all output reactives."
  [rvt-map inputs outputs]
  (let [v (case (count inputs)
            0 nil
            1 (-> inputs first rvt-map first)
            (mapv rvt-map inputs))]
    {:output-values (into {} (for [o outputs] [o v]))}))


(defn make-link
  "Creates a new Link. Label is an arbitrary text, inputs and outputs
  are sequences of reactives. 
  
  The eval-fn is a Link function [inputs outputs -> Result] which is
  called to produce a result from inputs (if all inputs are
  available). Defaults to default-link-fn.
  
  The error-fn is a function [Link Result -> Result] which is called when
  an exception was thrown by the Link function. Defaults to nil.

  The complete-fn is a function [Reactive -> Result] which is called for
  each input reactive that completes. Defaults to nil.

  The sequence complete-on-remove contains all reactives that should be
  completed when this Link is removed from the network."
  [label inputs outputs
   & {:keys [eval-fn error-fn complete-fn complete-on-remove]
      :or {eval-fn default-link-fn}}]
  {:pre [(seq inputs)]}
  {:label label
   :inputs inputs
   :outputs outputs
   :eval-fn eval-fn
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


(defn ^:no-doc str-link  
  [l]
  (str "  L" (:level l)
       " [" (s/join " " (map :label (:inputs l)))
       "] -- " (:label l) " --> ["
       (s/join " " (mapv :label (:outputs l)))
       "] " (if (every? available? (:inputs l))
              "READY" "incomplete")))


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
  [links]
  (dump (apply str (repeat 60 \-)))
  (dump (->> links (map str-link) (s/join "\n")))
  (dump (apply str (repeat 60 \-))))


(defn ^:no-doc dump-values
  [label rvs]
  (if (seq rvs)
    (dump label (->> rvs
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
       (mapcat (fn [l] (concat (:inputs l) (:outputs l))))
       set))


(defn ^:no-doc reactive-links-map
  "Returns a map {Reactive -> (Seq of links)}, where the reactive is
  an input of the links it points to."
  [links]
  (->> links
       (mapcat (fn [{:keys [inputs] :as l}]
                 (for [r inputs] [r l])))
       (reduce (fn [m [r l]]
                 (update-in m [r] conj l))
               {})))


(defn ^:no-doc reactive-followers-map
  "Returns a map {Reactive -> (Set of following reactives)}."
  [links]
  (->> links
       reactive-links-map
       (map (fn [[r links]]
              [r (->> links (mapcat :outputs) set)]))
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
                                         {:pre [(-> l :inputs seq)]}
                                         [l (->> (:inputs l)
                                                 (map level-map-wo-root)
                                                 (reduce max)
                                                 inc)]))
                                  (into level-map-wo-root))]
    level-map-incl-links))



(defn ^:no-doc ready?
  "Returns true for a link if
  - all inputs are available,
  - at least one output is not completed."
  [{:keys [inputs outputs]}]
  (and (every? available? inputs)
       (remove completed? outputs)))


(defn ^:no-doc dead?
  "Returns true for a link if at least one of it's inputs is completed
  or all outputs are completed. Empty outputs does not count as 'all
  outputs completed'."
  [{:keys [inputs outputs]}]
  (or (empty? inputs)
      (some completed? inputs)
      (and (seq outputs) (every? completed? outputs))))


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


(defn- complete-for-links!
  "Completes all reactives contained in the :complete-on-remove seq of
  the given links."
  [links]
  (doseq [r (->> links
                 (mapcat :complete-on-remove))]
    (deliver! r [::completed (now)])))


(defn remove-links
  "Removes links matched by predicate pred and returns a new,
  rebuilded network."
  [{:keys [links] :as n} pred]
  (let [links-to-remove (remove pred links)]
    (complete-for-links! links-to-remove)
    (rebuild n links-to-remove)))


(defn update-from-results!
  "Takes a network and a seq of result maps and returns an updated
  network, with links added and removed. Detects dead links and
  removes them as well. Completes reactives referenced by
  removed links :complete-on-remove seq."
  [{:keys [links] :as n} results]
  (let [dead-links      (->> links
                             (filter dead?))
        links-to-remove (->> results
                             (map :remove-by)
                             (remove nil?)
                             (reduce (fn [ls pred]
                                       (->> n :links
                                            (filter pred)
                                            (into ls)))
                                     (set dead-links)))
        links-to-add    (->> results (mapcat :add) set)]
    (complete-for-links! links-to-remove)
    (if (or (seq links-to-add) (seq links-to-remove))
      (->> n :links
           (remove links-to-remove)
           (concat links-to-add)
           (rebuild n))
      n)))


(defn- remove-completed!
  "Detects all completed input reactives, calls complete-fn for each
  link and returns a network updated with the results of the
  complete-fn invocations."
  [{:keys [reactives links links-map] :as n}]
  (let [results  (for [r (->> links
                              (mapcat :inputs)
                              set
                              (filter completed?))
                       f (->> r links-map
                              (map :complete-fn)) :when f]
                   (f r))]
    (update-from-results! n results)))



;; ---------------------------------------------------------------------------
;; Propagation within network


(defn- handle-exception!
  "Invokes the links error-fn function and returns its Result map, or
  prints stacktrace if the link has no error-fn."
  [{:keys [error-fn] :as link} {:keys [exception] :as result}]
  (when exception
    (if error-fn
      (error-fn link result)
      (.printStackTrace exception))))


(defn- deliver-values!
  "Updates all reactives from the reactive-values map and returns this map."
  [rvt-map]
  (doseq [[r vt] rvt-map]
    (when-not (completed? r)
      (deliver! r vt)))
  (map first rvt-map))


(defn- eval-link!
  "Evaluates one link, returning Result map, or nil if the link function
  returned nil."
  [rvt-map {:keys [eval-fn inputs outputs level] :as link}]
  (let [result        (try (eval-fn rvt-map inputs outputs)
                           (catch Exception ex {:exception ex}))
        inputs        {:input-values (->> inputs
                                          (map #(vector % (last-value %)))
                                          (into {}))}
        error-result  (handle-exception! link result)]
    (if result
      (merge inputs result error-result))))



(defn- sequential-values
  "Takes a sequence of Result maps and returns a seq of [r [v t]]
  pairs."
  [results]
  (let [timestamp (now)]
    (->> results
         (map :output-values)
         (remove nil?)
         (mapcat (fn [ov]
                   (if-not (sequential? ov)
                     (seq ov)
                     (mapcat seq ov))))
         (map (fn [[r v]] [r [v timestamp]])))))


(defn- consume-missing!
  ([reactives]
     (consume-missing! {} reactives))
  ([rvt-map reactives]
     (reduce (fn [rvt-map r]
               (if (rvt-map r)
                 rvt-map
                 (assoc rvt-map r (consume! r))))
             rvt-map
             reactives)))


(declare push! propagate-downstream!)


(defn ^:no-doc propagate!
  "Executes one propagation cycle.
  Returns the network."
  ([network]
     (propagate! network {} [] []))
  ([network pending-reactives]
     (propagate! network {} [] pending-reactives))
  ([network rvt-map pending-links pending-reactives]
     (dump "\n= PROPAGATE" (apply str (repeat 49 "=")))
     (let [network         (remove-completed! network)
           links-map       (:links-map network)
           level-map       (:level-map network)
           links           (->> pending-reactives
                                (mapcat links-map)
                                (concat pending-links)
                                (sort-by :level (comparator <))
                                distinct)
           _               (dump-links links)
           available-links (->> links
                                (filter ready?)
                                doall)
           level           (-> available-links first :level)
           same-level?     (fn [l] (= (:level l) level))
           current-links   (->> available-links
                                (filter same-level?))
           pending-links   (->> available-links
                                (remove same-level?))
           rvt-map         (->> current-links
                                (mapcat :inputs)
                                distinct
                                (consume-missing! rvt-map))
           results         (->> current-links
                                (map (partial eval-link! rvt-map))
                                (remove nil?))
           unchanged?      (empty? results)
           ;; process Result maps 
           ;; *-rvs is a sequence of reactive value pairs [r [v t]]
           all-rvs         (sequential-values results)
           _               (dump-values "VALUES" all-rvs)
           upstream?       (fn [[r _]]
                             (let [r-level (level-map r)]
                               (or (nil? r-level) (< r-level level))))
           downstream-rvs  (->> all-rvs
                                (remove upstream?)
                                (sort-by (comp level-map first) (comparator <)))
           upstream-rvs    (->> all-rvs (filter upstream?))
           ;; apply network changes returned by link function invocations
           network         (update-from-results! network results)]
       ;; push value into next cycle if reactive level is either
       ;; unknown or is lower than current level
       (doseq [[r [v t]] upstream-rvs]
         (push! r v t))
    
       (if unchanged?
         (assoc network :unchanged? true)
         (propagate-downstream! network
                                rvt-map
                                pending-links
                                downstream-rvs)))))


(defn- propagate-downstream!
  "Propagate values to reactives that are guaranteed to be downstream."
  [network rvt-map pending-links downstream-rvs]
  (loop [n network
         rvs downstream-rvs] 
    (let [[rvm remaining-rvs] (reduce (fn [[rvm remaining] [r vt]]
                                        (if (rvm r)
                                          [rvm (conj remaining [r vt])]
                                          [(assoc rvm r vt) remaining]))
                                      [{} []]
                                      rvs)]
      (if (seq rvm)
        (recur (propagate! n rvt-map pending-links (deliver-values! rvm))
               remaining-rvs)
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





;; ---------------------------------------------------------------------------
;; Agent related functions


(defmacro defnetwork
  "Interns a symbol pointing to an agent with a new, empty network in
  the current namespace. Use subsequent calls to add-links! in order
  to add/rebuild the network."
  [symbol]
  `(def ~symbol (agent (make-network ~(str *ns* "/" symbol) [])
                       :error-handler ~(fn [_ ex] (.printStackTrace ex)))))

(defn network-by-id
  [id]
  (let [[ns-name sym-name] (s/split id #"/")]
    (some-> ns-name symbol the-ns ns-publics (get (symbol sym-name)) var-get)))


#_ (def network-by-id
  "Returns the agent from the fully qualified name of the var holding
  the agent."
  (memoize network-by-id'))


(defn- sleep-if-necessary
  "Puts the current thread to sleep if the queue of pending agent
  computations exceeds max-items."
  [n-agent max-items millis]
  (when (< max-items (.getQueueCount n-agent))
    (Thread/sleep millis)))


(defn push!
  "Asynchronously starts an update of a reactive and a propagation
  cycle using network agent's send-off.  Returns the value."
  ([reactive value]
     (push! reactive value (now)))
  ([reactive value timestamp]
     (let [n-agent (-> reactive network-id network-by-id)]
       (sleep-if-necessary n-agent 1000 100)
       (send-off n-agent
                 update-and-propagate!
                 {reactive [value timestamp]}))
     value))


(defn complete!
  "Asynchronously delivers the ::completed value into a reactive and
  notifies the complete-fn handler of all links that the reactive is an
  input of. Updates the network according to results of handlers."
  [reactive]
  (let [n-agent (-> reactive network-id network-by-id)]
    (sleep-if-necessary n-agent 1000 100)
    (send-off n-agent
              complete-and-propagate!
              reactive))
  ::completed)


(defn add-links!
  "Asynchronously adds links to the network using send-off on the
  networks agent. Returns the network agent."
  [n-agent & links]
  (send-off n-agent add-links links))


(defn pp
  "Pretty print network in agent."
  [n-agent]
  {:pre [(instance? clojure.lang.IRef n-agent)]}
  (let [{:keys [links reactives values]} @n-agent]
    (println (str "Reactives\n" (s/join "\n" (map str-react reactives))
                  "\nLinks\n" (s/join "\n" (map str-link links))))))


:ok
