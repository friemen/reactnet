(ns reactnet.reactor-test
  (:require [clojure.test :refer :all]
            [reactnet.reactor :as r]
            [reactnet.netrefs :as refs]
            [reactnet.scheduler :as sched]
            [reactnet.core :as rn :refer [push! complete! pp]]))


;; ---------------------------------------------------------------------------
;; Support functions

(defn with-clean-network
  [f]
  (rn/with-netref (refs/agent-netref (rn/make-network "unittests" []))
    (f))
  (sched/cancel-all r/scheduler))

(use-fixtures :each with-clean-network)

(defn wait
  ([]
     (wait 200))
  ([millis]
     (Thread/sleep millis)))

(defn push-and-wait!
  [& rvs]
  (doseq [[r v] (partition 2 rvs)]
    (assert (rn/reactive? r))
    (push! r v))
  (wait))


;; ---------------------------------------------------------------------------
;; Tests of special reactive factories


(deftest fnbehavior-seqstream-test
  (let [r       (atom [])
        seconds (r/fnbehavior #(long (/ (System/currentTimeMillis) 1000)))
        numbers (r/seqstream (range 10))
        c       (->> (r/map vector seconds numbers) (r/swap! r conj))]
    (wait)
    (is (= (range 10) (->> r deref (map second))))))


(deftest just-test
  (testing "Just one value"
    (let [r  (atom [])
          j  (r/just 42)]
      (is (rn/pending? j))
      (r/swap! r conj j)
      (wait)
      (is (= [42] @r))
      (is (rn/completed? j))))
  (testing "A function invocation"
    (let [r  (atom [])
          e  (->> (constantly :foo) r/just (r/swap! r conj))]
      (wait)
      (is (= [:foo] @r)))))


(deftest sample-test
  (testing "Sample constant value"
    (let [r   (atom [])
          s   (->> :foo (r/sample 100) (r/swap! r conj))]
      (wait 500)
      (is (<= 4 (count @r)))
      (is (= [:foo :foo :foo :foo] (take 4 @r)))))
  (testing "Sample by invoking a function"
    (let [r   (atom [])
          s   (->> #(count @r) (r/sample 100) (r/swap! r conj))]
      (wait 500)
      (is (<= 4 (count @r)))
      (is (= [0 1 2 3] (take 4 @r)))))
  (testing "Sample from a ref"
    (let [r   (atom [])
          a   (atom 0)
          s   (->> a (r/sample 100) (r/swap! r conj))]
      (wait 150)
      (reset! a 1)
      (wait 400)
      (is (<= 4 (count @r)))
      (is (= [0 0 1 1] (take 4 @r))))))


(deftest timer-test
  (let [r   (atom [])
        t   (->> (r/timer 200) (r/swap! r conj))]
    (wait 1000)
    (is (= [1 2 3 4] (take 4 @r)))))


;; ---------------------------------------------------------------------------
;; Tests of combinators

(deftest amb-test
  (let [r   (atom [])
        e1  (r/eventstream "e1")
        e2  (r/eventstream "e2")
        c   (->> (r/amb e1 e2) (r/swap! r conj))]
    (push-and-wait! e2 :foo e1 :bar e2 :baz)
    (is (= [:foo :baz] @r))
    (complete! e2)
    (wait)
    (is (rn/completed? c))))


(deftest any-test
  (testing "The first true results in true."
    (let [r   (atom [])
          e1  (r/eventstream "e1")
          c   (->> e1 r/any (r/swap! r conj))]
      (apply push-and-wait! (interleave (repeat e1) [false true]))
      (is (= [true] @r))))
  (testing "False is emitted only after completion."
    (let [r   (atom [])
          e1  (r/eventstream "e1")
          c   (->> e1 r/any (r/swap! r conj))]
      (apply push-and-wait! (interleave (repeat e1) [false false]))
      (is (= [] @r))
      (complete! e1)
      (wait)
      (is (= [false] @r)))))


(deftest buffer-test
  (testing "Buffer by number of items"
    (let [r   (atom [])
          e1  (r/eventstream "e1")
          c   (->> e1 (r/buffer-c 2) (r/swap! r conj))]
      (apply push-and-wait! (interleave (repeat e1) [1 2 3 4 5 6 7]))
      (is (= [[1 2] [3 4] [5 6]] @r))
      (complete! e1)
      (wait)
      (is (= [[1 2] [3 4] [5 6] [7]] @r))))
  (testing "Buffer by time"
    (let [r   (atom [])
          e1  (r/eventstream "e1")
          c   (->> e1 (r/buffer-t 500) (r/swap! r conj))]
      (push-and-wait! e1 42)
      (is (= [] @r))
      (wait 500)
      (is (= [[42]] @r))))
  (testing "Buffer by time and number of items"
    (let [r   (atom [])
          e1  (r/eventstream "e1")
          c   (->> e1 (r/buffer 3 500) (r/swap! r conj))]
      (push-and-wait! e1 42 e1 43 e1 44 e1 45)
      (is (= [[42 43 44]] @r))
      (wait 500)
      (is (= [[42 43 44] [45]] @r))
      (push! e1 46)
      (complete! e1)
      (wait)
      (is (= [[42 43 44] [45] [46]] @r)))))


(deftest changes-test
  (let [r   (atom [])
        b   (r/behavior "b" nil)
        c   (->> b r/changes (r/swap! r conj))]
    (push-and-wait! b 1 b 1 b 42)
    (is (= [[nil 1] [1 42]] @r))))


(deftest concat-test
  (let [r   (atom [])
        e1  (r/eventstream "e1")
        e2  (r/eventstream "e2")
        s   (r/seqstream [:foo :bar :baz])
        c   (->> e1 (r/concat s e1 e2) (r/swap! r conj))]
    (push-and-wait! e2 1 e2 2 e2 3
                e1 "FOO" e1 "BAR" e1 ::rn/completed)
    (is (= [:foo :bar :baz "FOO" "BAR" 1 2 3] @r))))


(deftest count-test
  (let [r   (atom [])
        e1  (r/eventstream "e1")
        c   (->> e1 r/count (r/swap! r conj))]
    (push-and-wait! e1 :foo e1 :bar e1 :baz)
    (is (= [1 2 3] @r))
    (complete! e1)
    (wait)
    (is (rn/completed? c))))


(deftest debounce-test
  (let [r   (atom [])
        e1  (r/eventstream "e1")
        c   (->> e1 (r/debounce 250) (r/swap! r conj))]
    (push-and-wait! e1 :foo e1 :bar e1 :baz)
    (is (= [] @r))
    (wait 100)
    (is (= [:baz] @r))
    (rn/push! e1 :foo)
    (wait 300)
    (is (= [:baz :foo] @r))))


(deftest delay-test
  (let [r   (atom [])
        e1  (r/eventstream "e1")
        c   (->> e1 (r/delay 500) (r/swap! r conj))]
    (push-and-wait! e1 :foo)
    (is (= [] @r))
    (wait 500)
    (is (= [:foo] @r))))


(deftest distinct-test
  (let [r   (atom [])
        e1  (r/eventstream "e1")
        c   (->> e1 r/distinct (r/swap! r conj))]
    (push-and-wait! e1 :foo e1 :bar e1 :foo e1 :baz e1 :bar)
    (is (= [:foo :bar :baz] @r))))


(deftest drop-test
  (let [r   (atom [])
        e1  (r/eventstream "e1")
        c   (->> e1 (r/drop 2) (r/swap! r conj))]
    (push-and-wait! e1 :foo e1 :bar e1 :baz)
    (is (not (rn/pending? e1)))
    (is (= [:baz] @r))
    (complete! e1)
    (wait)
    (is (rn/completed? c))))


(deftest drop-last-test
  (let [r   (atom [])
        e1  (r/eventstream "e1")
        c   (->> e1 (r/drop-last 2) (r/swap! r conj))]
    (apply push-and-wait! (interleave (repeat e1) (range 6)))
    (is (= [0 1 2 3] @r))
    (push-and-wait! e1 6 e1 ::rn/completed)
    (is (= [0 1 2 3 4] @r))
    (is (rn/completed? c))))


(deftest drop-while-test
  (let [r   (atom [])
        e1  (r/eventstream "e1")
        c   (->> e1 (r/drop-while (partial >= 5)) (r/swap! r conj))]
    (apply push-and-wait! (interleave (repeat e1) (range 10)))
    (is (= [6 7 8 9] @r))))


(deftest every-test
  (testing "Direct completion emits true."
    (let [r   (atom [])
          e1  (r/eventstream "e1")
          c   (->> e1 r/every (r/swap! r conj))]
      (complete! e1)
      (wait)
      (is (= [true] @r))))
  (testing "The first False results in False."
    (let [r   (atom [])
          e1  (r/eventstream "e1")
          c   (->> e1 r/every (r/swap! r conj))]
      (apply push-and-wait! (interleave (repeat e1) [true false]))
      (is (= [false] @r))))
  (testing "True is emitted only after completion."
    (let [r   (atom [])
          e1  (r/eventstream "e1")
          c   (->> e1 r/every (r/swap! r conj))]
      (apply push-and-wait! (interleave (repeat e1) [true true]))
      (is (= [] @r))
      (complete! e1)
      (wait)
      (is (= [true] @r)))))


(deftest filter-test
  (let [r        (atom [])
        values   (range 10)
        expected (filter odd? values)
        e1       (r/eventstream "e1")
        c        (->> e1 (r/filter odd?) (r/swap! r conj))]
    (apply push-and-wait! (interleave (repeat e1) values))
    (is (= [1 3 5 7 9] @r))))


(deftest flatmap-test
  (let [r       (atom [])
        values  (range 5)
        f       (fn [x] (->> x r/just (r/map (partial * 2)) (r/map (partial + 1))))
        e1      (r/eventstream "e1")
        c       (->> e1 (r/flatmap f) (r/scan + 0) (r/swap! r conj))]
    (apply push-and-wait! (interleave (repeat e1) values))
    (is (= [1 4 9 16 25] @r))))


(deftest into-test
  (let [e  (r/eventstream "e")
        b1 (r/behavior "b1")
        b2 (r/behavior "b2")]
    (->> e (r/into b1 b2))
    (push-and-wait! e 1)
    (is (and (= @b1 1) (= @b2 1)))))


(deftest map-test
  (testing "Two eventstreams"
    (let [r   (atom [])
          e1  (r/eventstream "e1")
          e2  (r/eventstream "e2")
          c   (->> (r/map + e1 e2) (r/swap! r conj))]
      (push-and-wait! e1 1 e1 2 e2 1 e2 2)
      (is (= [2 4] @r))))
  (testing "Two behaviors"
    (let [r   (atom [])
          b1  (r/behavior "b1" 0)
          b2  (r/behavior "b2" 0)
          c   (->> (r/map + b1 b2) (r/swap! r conj))]
      (push-and-wait! b1 1 b1 2 b2 1 b2 2)
      (is (= [0 1 2 3 4] @r))))
  (testing "One eventstream, one behavior"
    (let [r   (atom [])
          e   (r/eventstream "e")
          b   (r/behavior "b" 0)
          c   (->> (r/map + e b) (r/swap! r conj))]
      (push-and-wait! b 1 b 2 e 1 e 2)
      (is (= [3 4] @r)))))


(deftest mapcat-test
  (let [r   (atom [])
        e1  (r/eventstream "e1")
        c   (->> e1 (r/mapcat :items) (r/swap! r conj))]
    (push-and-wait! e1 {:name "Me" :items ["foo" "bar" "baz"]})
    (is (= ["foo" "bar" "baz"] @r))))


(deftest merge-test
  (let [r        (atom [])
        streams  (for [i (range 5)]
                   (r/eventstream (str "e" i)))
        expected (repeatedly 400 #(rand-int 100))
        c        (->> streams (apply r/merge) (r/swap! r conj))]
    (doseq [x expected]
      (push! (rand-nth streams) x))
    (wait 1500)
    (is (= expected @r))))


(deftest reduce-test
  (let [r      (atom [])
        values (range 1 5)
        e1     (r/eventstream "e1")
        c      (->> e1 (r/reduce + 0) (r/swap! r conj))]
    (apply push-and-wait! (interleave (repeat e1) values))
    (is (= [] @r))
    (complete! e1)
    (wait)
    (is (= [10] @r))))


(deftest remove-test
  (let [r        (atom [])
        values   (range 10)
        expected (remove odd? values)
        e1       (r/eventstream "e1")
        c        (->> e1 (r/remove odd?) (r/swap! r conj))]
    (apply push-and-wait! (interleave (repeat e1) values))
    (is (= [0 2 4 6 8] @r))))


(deftest scan-test
  (let [r      (atom [])
        values (range 1 5)
        e1     (r/eventstream "e1")
        c      (->> e1 (r/scan + 0) (r/swap! r conj))]
    (apply push-and-wait! (interleave (repeat e1) values))
    (is (= [1 3 6 10] @r))))


(deftest snapshot-test
  (let [r   (atom [])
        b   (r/behavior "source" 42)
        e1  (r/eventstream "e1")
        c   (->> e1 (r/snapshot b) (r/swap! r conj))]
    (push-and-wait! e1 1 b 43 e1 2 e1 3)
    (is (= [42 43 43] @r))))


(deftest switch-test
  (let [r   (atom [])
        e1  (r/eventstream "e1")
        e2  (r/eventstream "e2")
        sw  (r/eventstream "streams")
        c   (->> sw r/switch (r/swap! r conj))]
    (push-and-wait! e1 "A" e1 "B" e2 "C" sw e2 sw e1)
    (is (= ["C" "A" "B"] @r))))


(deftest take-test
  (let [r   (atom [])
        e1  (r/eventstream "e1")
        c   (->> e1 (r/take 2) (r/swap! r conj))]
    (push-and-wait! e1 :foo e1 :bar e1 :baz)
    (is (= [:foo :bar] @r))
    (is (rn/completed? c))
    (is (rn/pending? e1))))


(deftest take-last-test
  (let [r   (atom [])
        e1  (r/eventstream "e1")
        c   (->> e1 (r/take-last 3) (r/swap! r conj))]
    (apply push-and-wait! (interleave (repeat e1) (range 5)))
    (is (= [] @r))
    (complete! e1)
    (wait)
    (is (= [2 3 4] @r))))


(deftest take-while-test
  (let [r   (atom [])
        e1  (r/eventstream "e1")
        c   (->> e1 (r/take-while (partial >= 5)) (r/swap! r conj))]
    (apply push-and-wait! (interleave (repeat e1) (range 10)))
    (is (= [0 1 2 3 4 5] @r))))


(deftest throttle-test
  (testing "Sending all at once"
    (let [r   (atom [])
          e1  (r/eventstream "e1")
          c   (->> e1 (r/throttle identity 500 10) (r/swap! r conj))]
      (push-and-wait! e1 :foo e1 :bar e1 :baz)
      (is (= [] @r))
      (is (not (rn/pending? e1)))
      (wait 500)
      (is (= [[:foo :bar :baz]] @r))))
  (testing "Too many items for the throttle queue to hold"
    (let [r   (atom [])
          e1  (r/eventstream "e1")
          c   (->> e1 (r/throttle identity 300 5) (r/swap! r conj))]
      (apply push-and-wait! (interleave (repeat e1) (range 7)))
      (is (= [] @r))
      (is (rn/pending? e1))
      (wait 200)
      (is (= [[0 1 2 3 4]] @r))
      (wait 400)
      (is (= [[0 1 2 3 4] [5 6]] @r)))))


;; ---------------------------------------------------------------------------
;; Tests for expression lifting


(deftest lift-fn-test
  (let [x (r/behavior "x" 2)
        y (r/behavior "y" 2)
        z (r/lift (* 3 x y))]
    (wait)
    (is (= @z 12))
    (push-and-wait! x 3 y 1)
    (is (= @z 9))))


(deftest lift-if-test
  (let [x (r/behavior "x" 2)
        z (r/lift (if (> x 2) "x > 2" "x <= 2"))]
    (wait)
    (is (= @z "x <= 2"))
    (push-and-wait! x 3)
    (is (= @z "x > 2"))))


(deftest lift-let-test
  (let [x (r/behavior "x" 2)
        z (r/lift (let [y (+ x 2)] (+ x y)))]
    (wait)
    (is (= @z 6))
    (push-and-wait! x 3)
    (is (= @z 8))))


(deftest lift-cond-test
  (let [x (r/behavior "x" 2)
        z (r/lift (cond
                   (<= x 1) (+ 9 x)
                   (<= x 4) (+ 4 x)
                   :else    x))]
    (wait)
    (is (= @z 6))
    (push-and-wait! x 4)
    (is (= @z 8))
    (push-and-wait! x 10)
    (is (= @z 10))))


(deftest lift-and-test
  (let [x (r/behavior "x" true)
        y (r/behavior "y" false)
        z (r/lift (and x y))]
    (wait)
    (is (not @z))
    (push-and-wait! y 1)
    (is @z)
    (push-and-wait! x nil)
    (is (not @z))))


(deftest lift-or-test
  (let [x (r/behavior "x" true)
        y (r/behavior "y" false)
        z (r/lift (or x y))]
    (wait)
    (is @z)
    (push-and-wait! x nil)
    (is (not @z))
    (push-and-wait! y 1)
    (is @z)))
