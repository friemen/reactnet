(ns reactnet.core-test
  (:require [clojure.test :refer :all]
            [reactnet.core :as r])
  (:import [reactnet.core React]))



(defn behavior
  [label value]
  (React. "" label (atom [value (r/now)]) false (atom false) (atom true) (atom nil)))


(defn eventstream
  [label]
  (React. "" label (atom [nil (r/now)]) true (atom false) (atom false) (atom nil)))


(defmacro link
  [f inputs outputs]
  `(r/make-link (str '~f) (r/make-sync-link-fn ~f) ~inputs ~outputs))


(defn network
  [& links]
  (r/make-network "" links))


(defn values
  [r-v-pairs]
  (map (fn [[r v]] {r [v (r/now)]}) r-v-pairs))

(deftest z=x+y-test
  (let [x (behavior "x" 1)
        y (behavior "y" 2)
        z (behavior "z" 0)
        n (network (link + [x y] [z]))]
    (r/propagate! n {x [2 (r/now)]
                     y [3 (r/now)]})
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
         (map (partial vector x))
         values
         (reduce r/propagate! n))
    (is (= @results [3 8 15 24 35 48 63 80 99 120]))))


(deftest pending-values-test
  (let [e1 (eventstream "e1")
        e2 (eventstream "e2")
        e3 (eventstream "e3")
        n (network (link identity [e1] [e2])
                   (link identity [e2] [e3]))
        n-after (->> [[e1 :foo] [e1 :bar] [e1 :baz]]
                     values
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
         values
         (reduce r/propagate! n))
    (is (= [:foo :bar] @results))))
