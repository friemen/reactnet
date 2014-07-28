(ns reactnet.reactor-test
  (:require [clojure.test :refer :all]
            [reactnet.reactor :as r]
            [reactnet.engines :as re]
            [reactnet.core :as rn :refer [push! complete! pp]]))


;; ---------------------------------------------------------------------------
;; Support functions

(defn with-clean-network
  [f]
  (rn/with-engine (re/agent-engine (rn/make-network "unittests" []))
    (f))
  (r/halt!))

(use-fixtures :each with-clean-network)

(defn wait
  ([]
     (wait 200))
  ([millis]
     (Thread/sleep millis)))

(defn push-and-wait!
  [& rvs]
  (doseq [[r v] (partition 2 rvs)]
    (push! r v))
  (wait))


;; ---------------------------------------------------------------------------
;; Tests of special reactive factories

(deftest sample-test
  (testing "Sample constant value"
    (let [r   (atom [])
          s   (->> :foo (r/sample 100) (r/swap-conj! r))]
      (wait 500)
      (is (<= 4 (count @r)))
      (is (= [:foo :foo :foo :foo] (take 4 @r)))))
  (testing "Sample by invoking a function"
    (let [r   (atom [])
          s   (->> #(count @r) (r/sample 100) (r/swap-conj! r))]
      (wait 500)
      (is (<= 4 (count @r)))
      (is (= [0 1 2 3] (take 4 @r)))))
  (testing "Sample from a ref"
    (let [r   (atom [])
          a   (atom 0)
          s   (->> a (r/sample 100) (r/swap-conj! r))]
      (wait 150)
      (reset! a 1)
      (wait 400)
      (is (<= 4 (count @r)))
      (is (= [0 0 1 1] (take 4 @r))))))


(deftest timer-test
  (let [r   (atom [])
        t   (->> (r/timer 200) (r/swap-conj! r))]
    (wait 1000)
    (is (= [1 2 3 4] (take 4 @r)))))


(deftest just-test
  (let [r  (atom [])
        j  (r/just 42)]
    (is (rn/pending? j))
    (r/swap-conj! r j)
    (wait)
    (is (= [42] @r))
    (is (rn/completed? j))))


;; ---------------------------------------------------------------------------
;; Tests of combinators

(deftest amb-test
  (let [r   (atom [])
        e1  (r/eventstream "e1")
        e2  (r/eventstream "e2")
        c   (->> (r/amb e1 e2) (r/swap-conj! r))]
    (push-and-wait! e2 :foo e1 :bar e2 :baz)
    (is (= [:foo :baz] @r))
    (complete! e2)
    (wait)
    (is (rn/completed? c))))


(deftest any-test
  (testing "The first true results in true."
    (let [r   (atom [])
          e1  (r/eventstream "e1")
          c   (->> e1 r/any (r/swap-conj! r))]
      (apply push-and-wait! (interleave (repeat e1) [false true]))
      (is (= [true] @r))))
  (testing "False is emitted only after completion."
    (let [r   (atom [])
          e1  (r/eventstream "e1")
          c   (->> e1 r/any (r/swap-conj! r))]
      (apply push-and-wait! (interleave (repeat e1) [false false]))
      (is (= [] @r))
      (complete! e1)
      (wait)
      (is (= [false] @r)))))


(deftest buffer-test
  (testing "Buffer by number of items"
    (let [r   (atom [])
          e1  (r/eventstream "e1")
          c   (->> e1 (r/buffer-c 2) (r/swap-conj! r))]
      (apply push-and-wait! (interleave (repeat e1) [1 2 3 4 5 6 7]))
      (is (= [[1 2] [3 4] [5 6]] @r))
      (complete! e1)
      (wait)
      (is (= [[1 2] [3 4] [5 6] [7]] @r))))
  (testing "Buffer by time"
    (let [r   (atom [])
          e1  (r/eventstream "e1")
          c   (->> e1 (r/buffer-t 500) (r/swap-conj! r))]
      (push-and-wait! e1 42)
      (is (= [] @r))
      (wait 500)
      (is (= [[42]] @r))))
  (testing "Buffer by time and number of items"
    (let [r   (atom [])
          e1  (r/eventstream "e1")
          c   (->> e1 (r/buffer 3 500) (r/swap-conj! r))]
      (push-and-wait! e1 42 e1 43 e1 44 e1 45)
      (is (= [[42 43 44]] @r))
      (wait 500)
      (is (= [[42 43 44] [45]] @r))
      (push! e1 46)
      (complete! e1)
      (wait)
      (is (= [[42 43 44] [45] [46]] @r)))))


(deftest concat-test
  (let [r   (atom [])
        e1  (r/eventstream "e1")
        e2  (r/eventstream "e2")
        s   (r/seqstream [:foo :bar :baz])
        c   (->> e1 (r/concat s e1 e2) (r/swap-conj! r))]
    (push-and-wait! e2 1 e2 2 e2 3
                e1 "FOO" e1 "BAR" e1 ::rn/completed)
    (is (= [:foo :bar :baz "FOO" "BAR" 1 2 3] @r))))


(deftest count-test
  (let [r   (atom [])
        e1  (r/eventstream "e1")
        c   (->> e1 r/count (r/swap-conj! r))]
    (push-and-wait! e1 :foo e1 :bar e1 :baz)
    (is (= [1 2 3] @r))
    (complete! e1)
    (wait)
    (is (rn/completed? c))))


(deftest debounce-test
  (let [r   (atom [])
        e1  (r/eventstream "e1")
        c   (->> e1 (r/debounce 250) (r/swap-conj! r))]
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
        c   (->> e1 (r/delay 500) (r/swap-conj! r))]
    (push-and-wait! e1 :foo)
    (is (= [] @r))
    (wait 500)
    (is (= [:foo] @r))))


(deftest every-test
  (testing "Direct completion emits true."
    (let [r   (atom [])
          e1  (r/eventstream "e1")
          c   (->> e1 r/every (r/swap-conj! r))]
      (complete! e1)
      (wait)
      (is (= [true] @r))))
  (testing "The first False results in False."
    (let [r   (atom [])
          e1  (r/eventstream "e1")
          c   (->> e1 r/every (r/swap-conj! r))]
      (apply push-and-wait! (interleave (repeat e1) [true false]))
      (is (= [false] @r))))
  (testing "True is emitted only after completion."
    (let [r   (atom [])
          e1  (r/eventstream "e1")
          c   (->> e1 r/every (r/swap-conj! r))]
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
        c        (->> e1 (r/filter odd?) (r/swap-conj! r))]
    (apply push-and-wait! (interleave (repeat e1) values))
    (is (= [1 3 5 7 9] @r))))


(deftest map-test
  (testing "Two eventstreams"
    (let [r   (atom [])
          e1  (r/eventstream "e1")
          e2  (r/eventstream "e2")
          c   (->> (r/map + e1 e2) (r/swap-conj! r))]
      (push-and-wait! e1 1 e1 2 e2 1 e2 2)
      (is (= [2 4] @r))))
  (testing "Two behaviors"
    (let [r   (atom [])
          b1  (r/behavior "b1" 0)
          b2  (r/behavior "b2" 0)
          c   (->> (r/map + b1 b2) (r/swap-conj! r))]
      (push-and-wait! b1 1 b1 2 b2 1 b2 2)
      (is (= [0 1 2 3 4] @r))))
  (testing "One eventstream, one behavior"
    (let [r   (atom [])
          e   (r/eventstream "e")
          b   (r/behavior "b" 0)
          c   (->> (r/map + e b) (r/swap-conj! r))]
      (push-and-wait! b 1 b 2 e 1 e 2)
      (is (= [3 4] @r)))))


(deftest mapcat-test
  (let [r   (atom [])
        e1  (r/eventstream "e1")
        c   (->> e1 (r/mapcat :items) (r/swap-conj! r))]
    (push-and-wait! e1 {:name "Me" :items ["foo" "bar" "baz"]})
    (is (= ["foo" "bar" "baz"] @r))))


(deftest merge-test
  (let [r        (atom [])
        streams  (for [i (range 5)]
                   (r/eventstream (str "e" i)))
        expected (repeatedly 400 #(rand-int 100))
        c        (->> streams (apply r/merge) (r/swap-conj! r))]
    (doseq [x expected]
      (push! (rand-nth streams) x))
    (wait 1500)
    (is (= expected @r))))


(deftest reduce-test
  (let [r      (atom [])
        values (range 1 5)
        e1     (r/eventstream "e1")
        c      (->> e1 (r/reduce + 0) (r/swap-conj! r))]
    (apply push-and-wait! (interleave (repeat e1) values))
    (complete! e1)
    (is (= [] @r))
    (wait)
    (is (= [10] @r))))


(deftest scan-test
  (let [r      (atom [])
        values (range 1 5)
        e1     (r/eventstream "e1")
        c      (->> e1 (r/scan + 0) (r/swap-conj! r))]
    (apply push-and-wait! (interleave (repeat e1) values))
    (is (= [1 3 6 10] @r))))


(deftest switch-test
  (let [r   (atom [])
        e1  (r/eventstream "e1")
        e2  (r/eventstream "e2")
        sw  (r/eventstream "streams")
        c   (->> sw r/switch (r/swap-conj! r))]
    (push-and-wait! e1 "A" e1 "B" e2 "C" sw e2 sw e1)
    (is (= ["C" "A" "B"] @r))))


(deftest take-test
  (let [r   (atom [])
        e1  (r/eventstream "e1")
        c   (->> e1 (r/take 2) (r/swap-conj! r))]
    (push-and-wait! e1 :foo e1 :bar e1 :baz)
    (is (= [:foo :bar] @r))
    (is (rn/completed? c))
    (is (rn/pending? e1))))


(deftest throttle-test
  (let [r   (atom [])
        e1  (r/eventstream "e1")
        c   (->> e1 (r/throttle last 500 10) (r/swap-conj! r))]
    (push-and-wait! e1 :foo e1 :bar e1 :baz)
    (is (= [] @r))
    (wait 500)
    (is (= [:baz] @r))))
