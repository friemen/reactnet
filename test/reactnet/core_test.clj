(ns reactnet.core-test
  (:require [clojure.test :refer :all]
            [reactnet.core :as r])
  (:import [reactnet.core SeqStream React]))



(defn seqstream
  [xs]
  (SeqStream. "" (atom {:seq (seq xs)}) true))


(defn behavior
  [label value]
  (React. "" label (atom [value (r/now)]) false (atom false) (atom true)))


(defn eventstream
  [label]
  (React. "" label (atom [nil (r/now)]) true (atom false) (atom false)))


(defmacro link
  [f inputs outputs]
  `(r/make-link (str '~f) (r/make-sync-link-fn ~f) ~inputs ~outputs))


(defn network
  [& links]
  (r/make-network "" links))


(defn rv
  [r v]
  {r [v (r/now)]})


(deftest z=x+y-test
  (let [x (behavior "x" 1)
        y (behavior "y" 2)
        z (behavior "z" 0)
        n (network (link + [x y] [z]))]
    (r/propagate! n (merge (rv x 2) (rv y 3)))
    (are [rs vs] (= (mapv deref rs) vs)
         [x y z] [2 3 5])))


(deftest x*<x+2>-test
  (let [results (atom [])
        x (behavior "x" 2)
        x+2 (behavior "x+2" nil)
        z (behavior "z" 0)
        n (network (link (partial + 2) [x] [x+2])
                   (link * [x+2 x] [z])
                   (link (partial swap! results conj) [z] []))]
    (->> (range 1 11)
         (map (partial rv x))
         (reduce r/propagate! n))
    (is (= @results [3 8 15 24 35 48 63 80 99 120]))))


(deftest incomplete-inputs-test
  (let [e1 (eventstream "e1")
        e2 (eventstream "e2")
        r  (eventstream "r")
        sums (atom [])
        n (network (link + [e1 e2] [r])
                   (link (partial swap! sums conj) [r] []))]
    (r/propagate! n (rv e1 1))
    (is (nil? @r))
    (r/propagate! n (rv e2 1))
    (is (= 2 @r))))


(deftest pending-values-test
  (let [e1 (eventstream "e1")
        e2 (eventstream "e2")
        e3 (eventstream "e3")
        n (network (link identity [e1] [e2])
                   (link identity [e2] [e3]))
        n-after (->> [[e1 :foo] [e1 :bar] [e1 :baz]]
                     (map (partial apply rv))
                     (reduce r/propagate! n))
        pending-values (reduce (fn [m [r [v t]]]
                                 (update-in m [r] (comp vec conj) v))
                               {}
                               (:values n-after))]
    (is (= {e3 [:bar :baz]} pending-values))
    (are [rs vs] (= (mapv deref rs) vs)
         [e1 e2 e3] [:baz :baz :foo])))


(deftest merge-test
  (let [results (atom [])
        e1 (eventstream "e1")
        e2 (eventstream "e2")
        m (eventstream "m")
        n (network (link identity [e1] [m])
                   (link identity [e2] [m])
                   (link (partial swap! results conj) [m] []))]
    (->> [[e1 :foo] [e2 :bar]]
         (map (partial apply rv))
         (reduce r/propagate! n))
    (is (= [:foo :bar] @results))))




(deftest mapcat-test
  (let [results (atom [])
        e1 (eventstream "e1")
        e2 (eventstream "e2")
        e3 (eventstream "e3")
        e4 (eventstream "e4")
        numbers (seqstream (range))
        n (network (r/make-link "items"
                                (fn [inputs outputs]
                                  {:output-values (mapv
                                                   (partial hash-map (first outputs))
                                                   (-> inputs first r/consume! :items))})
                                [e1] [e2])
                   (link name [e2] [e3])
                   (link vector [numbers e3] [e4])
                   (link (partial swap! results conj) [e4] []))]
    (r/propagate! n (rv e1 {:items [:foo :bar :baz]}))
    (is (= [[0 "foo"] [1 "bar"] [2 "baz"]] @results))))

