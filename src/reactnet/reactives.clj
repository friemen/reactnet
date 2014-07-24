(ns reactnet.reactives
  (:require [reactnet.core :as rn])
  (:import [reactnet.core IReactive]))

;; ---------------------------------------------------------------------------
;; A Behavior implementation of the IReactive protocol


(defrecord Behavior [n-id label a new?]
  IReactive
  (network-id [this]
    n-id)
  (last-value [this]
    (first @a))
  (available? [r]
    true)
  (pending? [r]
    @new?)
  (completed? [r]
    (= ::reactnet.core/completed (first @a)))
  (consume! [this]
    (reset! new? false)
    (rn/dump "CONSUME!" (first @a) "<-" (:label this))
    @a)
  (deliver! [this [value timestamp]]
    (when (not= (first @a) value)
      (rn/dump "DELIVER!" (:label this) "<-" value)
      (reset! a [value timestamp])
      (reset! new? true)))
  clojure.lang.IDeref
  (deref [this]
    (first @a)))

(prefer-method print-method java.util.Map clojure.lang.IDeref)
(prefer-method print-method clojure.lang.IRecord clojure.lang.IDeref)


;; ---------------------------------------------------------------------------
;; A buffered Eventstream implementation of the IReactive protocol


(defrecord Eventstream [n-id label a n]
  IReactive
  (network-id [this]
    n-id)
  (last-value [this]
    (-> a deref :last-occ first))
  (available? [this]
    (seq (:queue @a)))
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
;; An IReactive implementation based on a sequence

(defrecord SeqStream [n-id seq-val-atom eventstream?]
  IReactive
  (network-id [this]
    n-id)
  (last-value [this]
    (-> seq-val-atom deref :last-occ first))
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
    (throw (UnsupportedOperationException. "Unable to deliver a value to a seq"))))

