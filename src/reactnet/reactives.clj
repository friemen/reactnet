(ns reactnet.reactives
  "Default IReactive implementations: Behavior, Eventstream and Seqstream."
  (:require [reactnet.core :as rn])
  (:import [reactnet.core IReactive]))

;; ---------------------------------------------------------------------------
;; A Behavior implementation of the IReactive protocol


(defrecord Behavior [label a new? live?]
  IReactive
  (next-value [this]
    @a)
  (available? [r]
    true)
  (pending? [r]
    @new?)
  (completed? [r]
    (not @live?))
  (consume! [this]
    (reset! new? false)
    (rn/dump "CONSUME!" (first @a) "<-" (:label this))
    @a)
  (deliver! [this [value timestamp]]
    (when-not @live?
      (throw (IllegalStateException. (str "Behavior '" label "' is completed"))))
    (if (= :reactnet.core/completed value)
      (reset! live? false)
      (when (not= (first @a) value)
        (rn/dump "DELIVER!" (:label this) "<-" value)
        (reset! a [value timestamp])
        (reset! new? true))))
  clojure.lang.IDeref
  (deref [this]
    (first @a)))

(prefer-method print-method java.util.Map clojure.lang.IDeref)
(prefer-method print-method clojure.lang.IRecord clojure.lang.IDeref)


;; ---------------------------------------------------------------------------
;; A buffered Eventstream implementation of the IReactive protocol


(defrecord Eventstream [label a n]
  IReactive
  (next-value [this]
    (-> a deref :queue first))
  (available? [this]
    (->> a deref :queue seq))
  (pending? [this]
    (rn/available? this))
  (completed? [this]
    (and (:completed @a) (empty? (:queue @a))))
  (consume! [this]
    (:last-occ (swap! a (fn [{:keys [queue] :as a}]
                          (when (empty? queue)
                            (throw (IllegalStateException. (str "Eventstream '" label "' is empty"))))
                          (rn/dump "CONSUME!" (ffirst queue) "<-" (:label this))
                          (assoc a
                            :last-occ (first queue)
                            :queue (pop queue))))))
  (deliver! [this value-timestamp]
    (let [will-complete (= (first value-timestamp) :reactnet.core/completed)]
      (seq (:queue (swap! a (fn [{:keys [completed queue] :as a}]
                              (if completed
                                a
                                (if will-complete
                                  (do (rn/dump "WILL COMPLETE" (:label this))
                                      (assoc a :completed true))
                                  (if (<= n (count queue))
                                    (throw (IllegalStateException. (str "Cannot add more than " n " items to stream '" label "'")))
                                    (do (rn/dump "DELIVER!" (:label this) "<-" (first value-timestamp))
                                        (assoc a :queue (conj queue value-timestamp))))))))))))
  clojure.lang.IDeref
  (deref [this]
    (let [{:keys [queue last-occ]} @a]
      (or (first last-occ) (ffirst queue)))))



;; ---------------------------------------------------------------------------
;; An IReactive eventstream-like implementation based on a sequence

(defrecord Seqstream [seq-val-atom eventstream?]
  IReactive
  (next-value [this]
    (-> seq-val-atom deref :seq first (vector (rn/now))))
  (available? [this]
    (-> seq-val-atom deref :seq seq))
  (pending? [this]
    (rn/available? this))
  (completed? [this]
    (-> seq-val-atom deref :seq nil?))
  (consume! [this]
    (:last-occ (swap! seq-val-atom (fn [{:keys [seq]}]
                                     {:seq (next seq)
                                      :last-occ [(first seq) (rn/now)]}))))
  (deliver! [r value-timestamp-pair]
    (throw (UnsupportedOperationException. "Unable to deliver a value to a seq")))
  clojure.lang.IDeref
  (deref [this]
    (-> seq-val-atom deref :seq first)))



;; ---------------------------------------------------------------------------
;; An IReactive behavior-like implementation based on a function

(defrecord Fnbehavior [f]
  IReactive
  (next-value [this]
    [(f) (rn/now)])
  (available? [this]
    true)
  (pending? [this]
    false)
  (completed? [this]
    false)
  (consume! [this]
    [(f) (rn/now)])
  (deliver! [r value-timestamp-pair]
    (throw (UnsupportedOperationException. "Unable to deliver a value to a function")))
  clojure.lang.IDeref
  (deref [this]
    (f)))


;; ---------------------------------------------------------------------------
;; Factories

(def max-queue-size 1000)

(defn behavior
  [label value]
  (Behavior. label
             (atom [value (rn/now)])
             (atom true)
             (atom true)))


(defn eventstream
  [label]
  (Eventstream. label
                (atom {:queue (clojure.lang.PersistentQueue/EMPTY)
                       :last-value nil
                       :completed false})
                max-queue-size))


(defn seqstream
  [xs]
  (Seqstream. (atom {:seq (seq xs)}) true))


(defn fnbehavior
  [f]
  (Fnbehavior. f))
