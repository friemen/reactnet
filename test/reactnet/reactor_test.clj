(ns reactnet.reactor-test
  (:require [clojure.test :refer :all]
            [reactnet.reactor :as r]
            [reactnet.core :as rn :refer [defnetwork push! complete! pp]]))

(defnetwork n)

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
