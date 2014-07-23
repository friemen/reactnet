(ns reactnet.reactor-test
  (:require [clojure.test :refer :all]
            [reactnet.reactor :as r]
            [reactnet.core :as rn :refer [defnetwork push! complete! pp]]))

(defnetwork n)

;; ---------------------------------------------------------------------------
;; Support functions

(defn with-clean-network
  [f]
  (send-off n (fn [n] (rn/make-network (:id n) [])))
  (f)
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
;; Tests


(deftest sample-test
  (testing "Sample constant value"
    (let [r   (atom [])
          s   (->> :foo (r/rsample n 100) (r/swap-conj! r))]
      (wait 500)
      (is (<= 4 (count @r)))
      (is (= [:foo :foo :foo :foo] (take 4 @r)))))
  (testing "Sample by invoking a function"
    (let [r   (atom [])
          s   (->> #(count @r) (r/rsample n 100) (r/swap-conj! r))]
      (wait 500)
      (is (<= 4 (count @r)))
      (is (= [0 1 2 3] (take 4 @r)))))
  (testing "Sample from a ref"
    (let [r   (atom [])
          a   (atom 0)
          s   (->> a (r/rsample n 100) (r/swap-conj! r))]
      (wait 150)
      (reset! a 1)
      (wait 400)
      (is (<= 4 (count @r)))
      (is (= [0 0 1 1] (take 4 @r))))))


(deftest reduce-test
  (let [values (range 1 50)
        e1     (r/eventstream n "e1")
        b      (->> e1 (r/rreduce + 0))]
    (is (r/behavior? b))
    (apply push-and-wait! (interleave (repeat e1) values))
    (is (= @b (reduce + values)))))


(deftest merge-test
  (let [r        (atom [])
        streams  (for [i (range 5)]
                   (r/eventstream n (str "e" i)))
        expected (repeatedly 400 #(rand-int 100))
        c        (->> streams (apply r/rmerge) (r/swap-conj! r))]
    (doseq [x expected]
      (push! (rand-nth streams) x))
    (wait 1500)
    (is (= expected @r))))


(deftest concat-test
  (let [r   (atom [])
        e1  (r/eventstream n "e1")
        e2  (r/eventstream n "e2")
        s   (r/seqstream n [:foo :bar :baz])
        c   (->> e1 (r/rconcat s e1 e2) (r/swap-conj! r))]
    (push-and-wait! e2 1 e2 2 e2 3
                e1 "FOO" e1 "BAR" e1 ::rn/completed)
    (is (= [:foo :bar :baz "FOO" "BAR" 1 2 3] @r))))


(deftest mapcat-test
  (let [r   (atom [])
        e1  (r/eventstream n "e1")
        c   (->> e1 (r/rmapcat :items) (r/swap-conj! r))]
    (push-and-wait! e1 {:name "Me" :items ["foo" "bar" "baz"]})
    (is (= ["foo" "bar" "baz"] @r))))


(deftest switch-test
  (let [r   (atom [])
        e1  (r/eventstream n "e1")
        e2  (r/eventstream n "e2")
        sw  (r/eventstream n "streams")
        c   (->> sw r/rswitch (r/swap-conj! r))]
    (push-and-wait! e1 "A" e1 "B" e2 "C" sw e2 sw e1)
    (is (= ["C" "A" "B"] @r))))


(deftest delay-test
  (let [r   (atom [])
        e1  (r/eventstream n "e1")
        c   (->> e1 (r/rdelay 500) (r/swap-conj! r))]
    (push-and-wait! e1 :foo)
    (is (= [] @r))
    (wait 500)
    (is (= [:foo] @r))))


(deftest throttle-test
  (let [r   (atom [])
        e1  (r/eventstream n "e1")
        c   (->> e1 (r/rthrottle last 500 10) (r/swap-conj! r))]
    (push-and-wait! e1 :foo e1 :bar e1 :baz)
    (is (= [] @r))
    (wait 500)
    (is (= [:baz] @r))))
