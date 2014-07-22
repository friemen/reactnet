(ns reactnet.core-test
  (:require [clojure.test :refer :all]
            [reactnet.core :as r]
            [reactnet.reactor :as ru])
  (:import [reactnet.reactor SeqStream Behavior Eventstream]))



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
                1000))


(defmacro link
  [f inputs outputs]
  `(r/make-link (str '~f) ~inputs ~outputs
                :eval-fn (ru/make-sync-link-fn ~f)))


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
        n-after (r/complete-and-propagate! n e1)]
    (is (= 0 (-> n-after :links count)))))


(deftest complete-fn-test
  (let [e1 (eventstream "e1")
        e2 (eventstream "e2")
        e3 (eventstream "e3")
        n (network (assoc (link identity [e1] [e2])
                     :complete-fn (fn [r] {:add [(link identity [e2] [e3])]})))
        n-after (r/complete-and-propagate! n e1)]
    (is (= 0 (->> n-after :links (filter #(= (:outputs %) [e2])) count)))
    (is (= 1 (->> n-after :links (filter #(= (:outputs %) [e3])) count)))))


(deftest large-mult-filter-merge-test
  (let [c        25
        distance 4
        results  (atom [])
        i        (eventstream "i")
        o        (eventstream "o")
        inputs   (repeatedly c #(eventstream "input"))
        links    (->> (map vector (range c) inputs)
                      (mapcat (fn [[x input-r]]
                                (let [from (* x distance)
                                      to (+ from distance)
                                      id (str "[" from " <= x < " to "]")
                                      filtered-r (eventstream "filtered")]
                                  [(r/make-link id [input-r] [filtered-r]
                                                :eval-fn (fn [inputs outputs]
                                                           (let [x (-> inputs first r/consume!)]
                                                             (if (and (<= from x) (< x to))
                                                               {:output-values {(first outputs) x}}
                                                               {}))))
                                   (r/make-link "merge" [filtered-r] [o])])))
                      (cons (r/make-link "mult" [i] inputs))
                      (cons (r/make-link "subscriber" [o] []
                                         :eval-fn (fn [inputs outputs]
                                                    (swap! results conj (-> inputs first r/consume!))
                                                    {}))))
        n        (apply network links)
        values   (repeatedly 1000 #(rand-int (* distance c)))]
    (reduce r/update-and-propagate! n (map #(hash-map i [% (r/now)]) values))
    (is (= values @results))))


(deftest complete-on-remove-test
  (let [e1 (eventstream "e1")
        e2 (eventstream "e2")
        e3 (eventstream "e3")
        e4 (eventstream "e4")
        results (atom [])
        n (network (r/make-link "e1,e2->e3" [e1 e2] [e3]
                                :eval-fn (fn [inputs outputs]
                                           (let [v (->> inputs (map r/consume!) (reduce +))]
                                             {:output-values {(first outputs) v}}) )
                                :complete-on-remove [e3])
                   (r/make-link "e3->e4" [e3] [e4]
                                :complete-on-remove [e4])
                   (link (partial swap! results conj) [e4] []))]
    (r/update-and-propagate! n {e1 [1 (r/now)] e2 [2 (r/now)]})
    (is (= [3] @results))
    (r/complete-and-propagate! n e1)
    (is (r/completed? e1))
    (is (not (r/completed? e2)))
    (is (r/completed? e3))
    (is (r/completed? e4))))


(deftest flatmap-test
  (let [f (fn [c] (let [e (eventstream (str "E" c))]
                    (dotimes [i c] (r/deliver! e [i (r/now)]))
                    (r/deliver! e [::r/completed (r/now)])
                    e))
        e1 (eventstream "e1")
        e2 (eventstream "e2")
        results (atom [])
        n (network (let [state (atom {:queue []
                                      :active nil})
                         switch (fn switch [{:keys [queue active] :as state}]
                                  (if-let [r (first queue)]
                                    (if (or (nil? active) (r/completed? active))
                                      {:queue (vec (rest queue))
                                       :active r
                                       :add [(r/make-link "" [r] [e2]
                                                          :completed-fn
                                                          (fn [r]
                                                            (merge (swap! state switch)
                                                                   {:remove-by #(= [r] (:inputs %))})))]}
                                      state)
                                    state))
                         enqueue (fn [state r]
                                   (switch (update-in state [:queue] conj r)))]
                     (r/make-link "e1->e2" [e1] [e2]
                                  :eval-fn (fn [inputs outputs]
                                             (swap! state enqueue (-> inputs first r/consume! f)))))
                   (link (partial swap! results conj) [e2] []))
        ranges (range 2 100)
        expected (mapcat #(range %) ranges)
        n-after (->> ranges
                     (map (partial rv e1))
                     (reduce r/update-and-propagate! n))]
    (is (= expected @results))
    (is (= 3 (-> n-after :links count)))))
