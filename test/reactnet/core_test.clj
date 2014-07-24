(ns reactnet.core-test
  (:require [clojure.test :refer :all]
            [reactnet.core :as rn]
            [reactnet.reactives])
  (:import [reactnet.reactives SeqStream Behavior Eventstream]))



(defn seqstream
  [xs]
  (SeqStream. "" (atom {:seq (seq xs)}) true))


(defn behavior
  [label value]
  (Behavior. ""
             label
             (atom [value (rn/now)])
             (atom true)))


(defn eventstream
  [label]
  (Eventstream. ""
                label
                (atom {:queue (clojure.lang.PersistentQueue/EMPTY)
                       :last-value nil
                       :completed false})
                1000))


(defmacro link
  [f inputs outputs]
  `(rn/make-link (str '~f) ~inputs ~outputs
                :link-fn (rn/make-sync-link-fn ~f)))


(defn network
  [& links]
  (rn/make-network "" links))


(defn rv
  [r v]
  {r [v (rn/now)]})


(deftest z=x+y-test
  (let [x (behavior "x" 1)
        y (behavior "y" 2)
        z (behavior "z" 0)
        n (network (link + [x y] [z]))]
    (rn/update-and-propagate! n (merge (rv x 2) (rv y 3)))
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
         (reduce rn/update-and-propagate! n))
    (is (= @results [3 8 15 24 35 48 63 80 99 120]))))


(deftest incomplete-inputs-test
  (let [e1 (eventstream "e1")
        e2 (eventstream "e2")
        r  (eventstream "r")
        sums (atom [])
        n (network (link + [e1 e2] [r])
                   (link (partial swap! sums conj) [r] []))]
    (rn/update-and-propagate! n (rv e1 1))
    (is (nil? @r))
    (rn/update-and-propagate! n (rv e2 1))
    (is (= 2 @r))))


(deftest queuing-test
  (let [e1 (eventstream "e1")
        e2 (eventstream "e2")
        e3 (eventstream "e3")
        n (network (link identity [e1] [e2])
                   (link identity [e2] [e3]))]
    (->> [[e1 :foo] [e1 :bar] [e1 :baz]]
         (map (partial apply rv))
         (reduce rn/update-and-propagate! n))
    (is (= [:foo :bar :baz]
           (->> e3 :a deref :queue seq (mapv first))))
    (are [rs vs] (= (mapv deref rs) vs)
         [e1 e2 e3] [:baz :baz :foo])))


(deftest consume-queued-values-test
  (let [results (atom [])
        e1 (eventstream "e1")
        n (network)]
    (->> [[e1 :foo] [e1 :bar]]
         (map (partial apply rv))
         (reduce rn/update-and-propagate! n))
    (is (= [] @results))
    (is (rn/pending? e1))
    (rn/update-and-propagate!
     (rn/add-links n [(link (partial swap! results conj) [e1] [])])
     (rv e1 :baz))
    (is (= [:foo :bar :baz] @results))
    (is (not (rn/pending? e1)))))


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
         (reduce rn/update-and-propagate! n))
    (is (= [:foo :bar] @results))))


(deftest many-output-values-test
  (let [results (atom [])
        e1 (eventstream "e1")
        e2 (eventstream "e2")
        e3 (eventstream "e3")
        e4 (eventstream "e4")
        numbers (seqstream (range))
        n (network (rn/make-link "items" [e1] [e2]
                                :link-fn
                                (fn [{:keys [input-rvts output-reactives] :as input}]
                                  (let [vs (:items (rn/fvalue input-rvts))]
                                    {:output-rvts (rn/enqueue-values vs (first output-reactives))})))
                   (link name [e2] [e3])
                   (link vector [numbers e3] [e4])
                   (link (partial swap! results conj) [e4] []))]
    (rn/update-and-propagate! n (rv e1 {:items [:foo :bar :baz]}))
    (is (= [[0 "foo"] [1 "bar"] [2 "baz"]] @results))))


(deftest add-remove-links-test
  (let [e1 (eventstream "e1")
        e2 (eventstream "e2")
        e3 (eventstream "e3")
        l2 (link identity [e2] [e3])
        l1 (rn/make-link "add-remove" [e1] [e2]
                        :link-fn
                        (fn [_]
                          {:add [l2]
                           :remove-by #(= (:outputs %) [e2])}))
        n (network l1)
        n-after (rn/update-and-propagate! n (rv e1 :foo))]
    (is (= 1 (-> n-after :links count)))
    (is (= (:label l2) (-> n-after :links first :label)))))


(deftest dead-link-remove-test
  (let [e1 (eventstream "e1")
        e2 (eventstream "e2")
        n (network (link identity [e1] [e2]))
        n-after (rn/complete-and-propagate! n e1)]
    (is (= 0 (-> n-after :links count)))))


(deftest complete-fn-test
  (let [e1 (eventstream "e1")
        e2 (eventstream "e2")
        e3 (eventstream "e3")
        n (network (assoc (link identity [e1] [e2])
                     :complete-fn (fn [_ r] {:add [(link identity [e2] [e3])]})))
        n-after (rn/complete-and-propagate! n e1)]
    (is (= 0 (->> n-after :links (filter #(= (:outputs %) [e2])) count)))
    (is (= 1 (->> n-after :links (filter #(= (:outputs %) [e3])) count)))))


(deftest large-mult-filter-merge-test
  (let [c        25
        distance 4
        results  (atom [])
        i        (eventstream "i")
        o        (eventstream "o")
        links    (->> (range c)
                      (mapcat (fn [x]
                                (let [from (* x distance)
                                      to (+ from distance)
                                      id (str "[" from " <= x < " to "]")
                                      filtered-r (eventstream "filtered")]
                                  [(rn/make-link id [i] [filtered-r]
                                                :link-fn (fn [{:keys [input-rvts] :as input}]
                                                           (let [x (rn/fvalue input-rvts)]
                                                             (if (and (<= from x) (< x to))
                                                               (rn/make-result-map input x)
                                                               {}))))
                                   (rn/make-link "merge" [filtered-r] [o])])))
                      (cons (rn/make-link "subscriber" [o] []
                                         :link-fn (fn [{:keys [input-rvts] :as input}]
                                                    (swap! results conj (rn/fvalue input-rvts))
                                                    {}))))
        n        (apply network links)
        values   (repeatedly 1000 #(rand-int (* distance c)))]
    (reduce rn/update-and-propagate! n (map #(hash-map i [% (rn/now)]) values))
    (is (= values @results))))


(deftest complete-on-remove-test
  (let [e1 (eventstream "e1")
        e2 (eventstream "e2")
        e3 (eventstream "e3")
        e4 (eventstream "e4")
        results (atom [])
        n (network (rn/make-link "e1,e2->e3" [e1 e2] [e3]
                                :link-fn (fn [{:keys [input-rvts] :as input}]
                                           (let [v (reduce + (rn/values input-rvts))]
                                             (rn/make-result-map input v)) )
                                :complete-on-remove [e3])
                   (rn/make-link "e3->e4" [e3] [e4]
                                :complete-on-remove [e4])
                   (link (partial swap! results conj) [e4] []))]
    (alter-var-root #'reactnet.core/network-by-id (fn [_] (constantly (agent n))))
    (rn/update-and-propagate! n {e1 [1 (rn/now)] e2 [2 (rn/now)]})
    (is (= [3] @results))
    (rn/complete-and-propagate! n e1)
    (Thread/sleep 300)
    (is (rn/completed? e1))
    (is (not (rn/completed? e2)))
    (is (rn/completed? e3))
    (is (rn/completed? e4))))


(deftest flatmap-test
  (let [f (fn [c] (let [e (eventstream (str "E" c))]
                    (dotimes [i c] (rn/deliver! e [i (rn/now)]))
                    (rn/deliver! e [::rn/completed (rn/now)])
                    e))
        e1 (eventstream "e1")
        e2 (eventstream "e2")
        results (atom [])
        n (network (let [q-atom (atom {:queue []
                                       :active nil})
                         switch (fn switch [{:keys [queue active] :as state}]
                                  (if-let [r (first queue)]
                                    (if (or (nil? active) (rn/completed? active))
                                      {:queue (vec (rest queue))
                                       :active r
                                       :add [(rn/make-link "" [r] [e2]
                                                          :complete-fn
                                                          (fn [_ r]
                                                            (merge (swap! q-atom switch)
                                                                   {:remove-by #(= [r] (:inputs %))})))]}
                                      state)
                                    state))
                         enqueue (fn [state r]
                                   (switch (update-in state [:queue] conj r)))]
                     (rn/make-link "e1->e2" [e1] [e2]
                                  :link-fn (fn [{:keys [input-rvts] :as input}]
                                             (swap! q-atom enqueue (f (rn/fvalue input-rvts))))))
                   (link (partial swap! results conj) [e2] []))
        ranges (range 2 3)
        expected (mapcat #(range %) ranges)
        n-after (->> ranges
                     (map (partial rv e1))
                     (reduce rn/update-and-propagate! n))]
    (is (= expected @results))
    (is (= 3 (-> n-after :links count)))))
