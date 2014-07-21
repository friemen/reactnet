(ns reactnet.core-test
  (:require [clojure.test :refer :all]
            [reactnet.core :as r])
  (:import [reactnet.core SeqStream Behavior Eventstream]))



(defn seqstream
  [xs]
  (SeqStream. "" (atom {:seq (seq xs)}) true))


(defn behavior
  [label value]
  (Behavior. ""
             label
             (atom [value (r/now)])
             (atom true)))


(defn eventstream
  [label]
  (Eventstream. ""
                label
                (atom {:queue (clojure.lang.PersistentQueue/EMPTY)
                       :last-value nil
                       :completed false})
                10))


(defmacro link
  [f inputs outputs]
  `(r/make-link (str '~f) ~inputs ~outputs
                :eval-fn (r/make-sync-link-fn ~f)))


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
    (r/update-and-propagate! n (merge (rv x 2) (rv y 3)))
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
         (reduce r/update-and-propagate! n))
    (is (= @results [3 8 15 24 35 48 63 80 99 120]))))


(deftest incomplete-inputs-test
  (let [e1 (eventstream "e1")
        e2 (eventstream "e2")
        r  (eventstream "r")
        sums (atom [])
        n (network (link + [e1 e2] [r])
                   (link (partial swap! sums conj) [r] []))]
    (r/update-and-propagate! n (rv e1 1))
    (is (nil? @r))
    (r/update-and-propagate! n (rv e2 1))
    (is (= 2 @r))))


(deftest queuing-test
  (let [e1 (eventstream "e1")
        e2 (eventstream "e2")
        e3 (eventstream "e3")
        n (network (link identity [e1] [e2])
                   (link identity [e2] [e3]))]
    (->> [[e1 :foo] [e1 :bar] [e1 :baz]]
         (map (partial apply rv))
         (reduce r/update-and-propagate! n))
    (is (= [:foo :bar :baz]
           (->> e3 :a deref :queue seq (mapv first))))
    (are [rs vs] (= (mapv deref rs) vs)
         [e1 e2 e3] [:baz :baz :foo])))


(deftest consume-queued-values-test
  (let [results (atom [])
        pass? (atom false)
        e1 (eventstream "e1")
        n (network (r/make-link "collect" [e1] []
                                :eval-fn
                                (fn [inputs outputs]
                                  (when @pass?
                                    (swap! results conj (-> inputs first r/consume!))
                                    {}))))]
    (->> [[e1 :foo] [e1 :bar]]
         (map (partial apply rv))
         (reduce r/update-and-propagate! n))
    (is (= [] @results))
    (is (r/pending? e1))
    (reset! pass? true)
    (r/update-and-propagate! n (rv e1 :baz))
    (is (= [:foo :bar :baz] @results))
    (is (not (r/pending? e1)))))


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
         (reduce r/update-and-propagate! n))
    (is (= [:foo :bar] @results))))


(deftest many-output-values-test
  (let [results (atom [])
        e1 (eventstream "e1")
        e2 (eventstream "e2")
        e3 (eventstream "e3")
        e4 (eventstream "e4")
        numbers (seqstream (range))
        n (network (r/make-link "items" [e1] [e2]
                                :eval-fn
                                (fn [inputs outputs]
                                  {:output-values (mapv
                                                   (partial hash-map (first outputs))
                                                   (-> inputs first r/consume! :items))}))
                   (link name [e2] [e3])
                   (link vector [numbers e3] [e4])
                   (link (partial swap! results conj) [e4] []))]
    (r/update-and-propagate! n (rv e1 {:items [:foo :bar :baz]}))
    (is (= [[0 "foo"] [1 "bar"] [2 "baz"]] @results))))


(deftest add-remove-links-test
  (let [e1 (eventstream "e1")
        e2 (eventstream "e2")
        e3 (eventstream "e3")
        l2 (link identity [e2] [e3])
        l1 (r/make-link "add-remove" [e1] [e2]
                        :eval-fn
                        (fn [inputs outputs]
                          {:add [l2]
                           :remove-by #(= (:outputs %) [e2])}))
        n (network l1)
        n-after (r/update-and-propagate! n (rv e1 :foo))]
    (is (= 1 (-> n-after :links count)))
    (is (= (:label l2) (-> n-after :links first :label)))))


(deftest dead-link-remove-test
  (let [e1 (eventstream "e1")
        e2 (eventstream "e2")
        n (network (link identity [e1] [e2]))
        n-after (r/complete-and-update! n e1)]
    (is (= 0 (-> n-after :links count)))))


(deftest complete-fn-test
  (let [e1 (eventstream "e1")
        e2 (eventstream "e2")
        e3 (eventstream "e3")
        n (network (assoc (link identity [e1] [e2])
                     :complete-fn (fn [r] {:add [(link identity [e2] [e3])]})))
        n-after (r/complete-and-update! n e1)]
    (is (= 0 (->> n-after :links (filter #(= (:outputs %) [e2])) count)))
    (is (= 1 (->> n-after :links (filter #(= (:outputs %) [e3])) count)))))
