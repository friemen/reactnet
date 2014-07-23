(ns reactnet.reactor-test
  (:require [clojure.test :refer :all]
            [reactnet.reactor :as r]
            [reactnet.core :as rn :refer [defnetwork push! complete! pp]]))

(defnetwork n)


(deftest concat-test
  (let [r   (atom [])
        e1  (r/eventstream n "e1")
        e2  (r/eventstream n "e2")
        s   (r/seqstream n [:foo :bar :baz])
        c   (->> e1 (r/rconcat s e1 e2) (r/swap-conj! r))]
    (doto e2
      (push! 1) (push! 2) (push! 3))
    (doto e1
      (push! "FOO") (push! "BAR"))
    (complete! e1)
    (Thread/sleep 500)
    (is (= [:foo :bar :baz "FOO" "BAR" 1 2 3] @r))))


(deftest mapcat-test
  (let [r    (atom [])
        e1   (r/eventstream n "e1")
        c    (->> e1 (r/rmapcat :items) (r/swap-conj! r))]
    (push! e1 {:name "Me" :items ["foo" "bar" "baz"]})
    (Thread/sleep 500)
    (is (= ["foo" "bar" "baz"] @r))))


(deftest switch-test
  (send-off n (fn [n] (rn/make-network (:id n) [])))
  (let [r   (atom [])
        e1  (r/eventstream n "e1")
        e2  (r/eventstream n "e2")
        sw  (r/eventstream n "streams")
        c   (->> sw r/rswitch (r/swap-conj! r))]
    (push! e1 "A")
    (push! e1 "B")
    (push! e2 1)
    (push! sw e2)
    (push! sw e1)
    (Thread/sleep 500)
    (is (= [1 "A" "B"] @r))))
