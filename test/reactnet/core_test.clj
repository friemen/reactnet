(ns reactnet.core-test
  (:require [clojure.test :refer :all]
            [reactnet.core :as rn]
            [reactnet.netrefs :as refs]
            [reactnet.reactives :refer [behavior eventstream seqstream]]))


;; ---------------------------------------------------------------------------
;; Supportive functions


(defmacro link
  [f inputs outputs]
  `(rn/make-link (str '~f) ~inputs ~outputs
                :link-fn (rn/make-sync-link-fn ~f)))

(defmacro with-network
  [links & exprs]
  `(rn/with-netref (refs/atom-netref (rn/make-network "" ~links))
     ~@exprs))

(defn push!
  [& rvs]
  (let [rv-pairs (->> rvs
                      (partition-by (partial satisfies? rn/IReactive))
                      (partition 2)
                      (map (partial apply concat))
                      (mapcat (fn [[r & values]]
                                (for [v values] [r v]))))]
    (doseq [[r v] rv-pairs]
      (rn/push! rn/*netref* r v))))


;; ---------------------------------------------------------------------------
;; Unit tests

(deftest z=x+y-test
  (let [x (behavior "x" 1)
        y (behavior "y" 2)
        z (behavior "z" 0)]
    (with-network [(link + [x y] [z])]
      (push! x 2 y 3)
      (are [rs vs] (= (mapv deref rs) vs)
           [x y z] [2 3 5])
      (is (= 3 (-> rn/*netref* rn/network :rid-map count))))))


(deftest x*<x+2>-test
  (let [r    (atom [])
        x    (behavior "x" 2)
        x+2  (behavior "x+2" nil)
        z    (behavior "z" 0)]
    (with-network [(link (partial + 2) [x] [x+2])
                   (link * [x+2 x] [z])
                   (link (partial swap! r conj) [z] [])]
      (apply push! (cons x (range 1 11)))
      (is (= @r [3 8 15 24 35 48 63 80 99 120])))))


(deftest incomplete-inputs-test
  (let [e1   (eventstream "e1")
        e2   (eventstream "e2")
        r    (eventstream "r")
        sums (atom [])]
    (with-network [(link + [e1 e2] [r])
                   (link (partial swap! sums conj) [r] [])]
      (push! e1 1)
      (is (nil? @r))
      (push! e2 1)
      (is (= 2 @r)))))


(deftest weakref-test
  (with-network []
    (let [e1 (eventstream "e1")
          e2 (eventstream "e2")]
      (rn/add-links! rn/*netref* (rn/make-link "e1->e2" [e1] [e2]))
      (is (= 1 (-> rn/*netref* rn/network :links count))))
    (System/gc)
    (rn/update rn/*netref* rn/update-and-propagate! [nil])
    (is (= 0 (-> rn/*netref* rn/network :links count)))))


(deftest no-consume-test
  (let [e1        (eventstream "e1")
        e2        (eventstream "e2")
        consume?  (atom false)
        r         (atom [])]
    (with-network [(rn/make-link "forward" [e1] [e2]
                                 :link-fn
                                 (fn [{:keys [input-rvts]}]
                                   (if @consume?
                                     {:output-rvts (rn/single-value (rn/fvalue input-rvts) e2)}
                                     {:no-consume true})))
                   (link (partial swap! r conj) [e2] [])]
      (push! e1 1)
      (is (rn/pending? e1))
      (is (= [] @r))
      (reset! consume? true)
      (push! e1 2)
      (is (= [1 2] @r)))))


(deftest cycle-test
  (let [r  (atom [])
        e1 (eventstream "e1")]
    (rn/with-netref
      (refs/agent-netref
       (rn/make-network "test" [(rn/make-link "inc-e1" [e1] [e1]
                                              :link-fn (fn [{:keys [input-rvts] :as input}]
                                                         (let [v (inc (rn/fvalue input-rvts))]
                                                           (if (<= v 3)
                                                             (rn/make-result-map input v)
                                                             {:remove-by #(= [e1] (rn/link-outputs %))}))))
                                (link (partial swap! r conj) [e1] [])]))
      (push! e1 1)
      (Thread/sleep 200)
      (is (= [1 2 3] @r)))))


(deftest queuing-test
  (let [e1 (eventstream "e1")
        e2 (eventstream "e2")
        e3 (eventstream "e3")]
    (with-network [(link identity [e1] [e2])
                   (link identity [e2] [e3])]
      (push! e1 :foo :bar :baz)
      (is (= [:foo :bar :baz]
             (->> e3 :a deref :queue seq (mapv first))))
      (are [rs vs] (= (mapv deref rs) vs)
           [e1 e2 e3] [:baz :baz :foo]))))


(deftest consume-queued-values-test
  (let [r   (atom [])
        e1  (eventstream "e1")]
    (with-network []
      (push! e1 :foo :bar :baz)
      (is (= [] @r))
      (is (rn/pending? e1))
      (rn/add-links! rn/*netref* (link (partial swap! r conj) [e1] []))
      (is (= [:foo :bar :baz] @r))
      (is (not (rn/pending? e1))))))


(deftest merge-test
  (let [r   (atom [])
        e1  (eventstream "e1")
        e2  (eventstream "e2")
        m   (eventstream "m")]
    (with-network [(link identity [e1] [m])
                   (link identity [e2] [m])
                   (link (partial swap! r conj) [m] [])]
      (push! e1 :foo e2 :bar)
      (is (= [:foo :bar] @r)))))


(deftest many-output-values-test
  (let [r       (atom [])
        e1      (eventstream "e1")
        e2      (eventstream "e2")
        e3      (eventstream "e3")
        e4      (eventstream "e4")
        numbers (seqstream (range))]
    (with-network [(rn/make-link "items" [e1] [e2]
                                   :link-fn
                                   (fn [{:keys [input-rvts output-reactives] :as input}]
                                     (let [vs (:items (rn/fvalue input-rvts))]
                                       {:output-rvts (rn/enqueue-values vs (first output-reactives))})))
                    (link name [e2] [e3])
                    (link vector [numbers e3] [e4])
                    (link (partial swap! r conj) [e4] [])]
      (push! e1 {:items [:foo :bar :baz]})
      (is (= [[0 "foo"] [1 "bar"] [2 "baz"]] @r)))))


(deftest add-remove-links-test
  (let [e1 (eventstream "e1")
        e2 (eventstream "e2")
        e3 (eventstream "e3")
        l2 (link identity [e2] [e3])
        l1 (rn/make-link "add-remove" [e1] [e2]
                           :link-fn
                           (fn [_]
                             {:add [l2]
                              :remove-by #(= (rn/link-outputs %) [e2])}))]
    (with-network [l1]
      (push! e1 :foo)
      (is (= 1 (-> rn/*netref* rn/network :links count)))
      (is (= (:label l2) (-> rn/*netref* rn/network :links first :label))))))


(deftest dead-link-remove-test
  (let [e1      (eventstream "e1")
        e2      (eventstream "e2")]
    (with-network [(link identity [e1] [e2])]
      (push! e1 ::rn/completed)
      (is (= 0 (-> rn/*netref* rn/network :links count))))))


(deftest complete-fn-test
  (let [e1 (eventstream "e1")
        e2 (eventstream "e2")
        e3 (eventstream "e3")]
    (with-network [(assoc (link identity [e1] [e2])
                     :complete-fn (fn [_ r] {:add [(link identity [e2] [e3])]}))]
      (push! e1 ::rn/completed)
      (is (= 0 (->> rn/*netref* rn/network :links (filter #(= (rn/link-outputs %) [e2])) count)))
      (is (= 1 (->> rn/*netref* rn/network :links (filter #(= (rn/link-outputs %) [e3])) count))))))


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
                                   (rn/make-link (str "merge" id) [filtered-r] [o])])))
                      (cons (rn/make-link "subscriber" [o] []
                                         :link-fn (fn [{:keys [input-rvts] :as input}]
                                                    (swap! results conj (rn/fvalue input-rvts))
                                                    {}))))
        values   (repeatedly 1000 #(rand-int (* distance c)))]
    (with-network links
      (is (= 51 (-> rn/*netref* rn/network :links count)))
      (apply push! (cons i values))
      (is (= 51 (-> rn/*netref* rn/network :links count))))
    (is (= values @results))))


(deftest complete-on-remove-test
  (let [e1 (eventstream "e1")
        e2 (eventstream "e2")
        e3 (eventstream "e3")
        e4 (eventstream "e4")
        r  (atom [])]
    (rn/with-netref
      (refs/agent-netref
       (rn/make-network "test" [(rn/make-link "e1,e2->e3" [e1 e2] [e3]
                                              :link-fn (fn [{:keys [input-rvts] :as input}]
                                                         (let [v (reduce + (rn/values input-rvts))]
                                                           (rn/make-result-map input v)) )
                                              :complete-on-remove [e3])
                                (rn/make-link "e3->e4" [e3] [e4]
                                              :complete-on-remove [e4])
                                (link (partial swap! r conj) [e4] [])]))
      (push! e1 1 e2 2)
      (Thread/sleep 200)
      (is (= [3] @r))
      (push! e1 ::rn/completed)
      (Thread/sleep 200)
      (is (rn/completed? e1))
      (is (not (rn/completed? e2)))
      (is (rn/completed? e3))
      (is (rn/completed? e4)))))


(deftest no-premature-completion-test
  (let [future-link-fn (fn [{:keys [input-rvts output-reactives]}]
                         (future (Thread/sleep 100)
                                 (let [v (rn/fvalue input-rvts)]
                                   (doseq [o output-reactives]
                                     (push! o v))))
                         nil)
        e1 (eventstream "e1")
        e2 (eventstream "e2")
        e3 (eventstream "e3")
        e4 (eventstream "e4")
        r  (atom [])]
    (rn/with-netref
      (refs/agent-netref
       (rn/make-network "test" [(rn/make-link "e1->e2" [e1] [e2] :link-fn future-link-fn :complete-on-remove [e2])
                                (rn/make-link "e2->e3" [e2] [e3] :link-fn future-link-fn :complete-on-remove [e3])
                                (rn/make-link "e3->e4" [e3] [e4] :link-fn future-link-fn :complete-on-remove [e4])
                                (link (partial swap! r conj) [e4] [])]))
      (push! e1 42)
      (Thread/sleep 150)
      (push! e1 ::rn/completed)
      (is (not (rn/completed? e4)))
      (Thread/sleep 200)
      (is (= [42] @r))
      (is (rn/completed? e4)))))


(deftest flatmap-test
  (let [f        (fn [c]
                   (let [e (eventstream (str "E" c))]
                     (dotimes [i c] (rn/deliver! e [i (rn/now)]))
                     (rn/deliver! e [::rn/completed (rn/now)])
                     e))
        e1       (eventstream "e1")
        e2       (eventstream "e2")
        r        (atom [])
        ranges   (range 2 5)
        expected (mapcat #(range %) ranges)]
    (with-network [(let [q-atom (atom {:queue []
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
                                                                    {:remove-by #(= [r] (rn/link-inputs %))})))]}
                                      state)
                                    state))
                         enqueue (fn [state r]
                                   (switch (update-in state [:queue] conj r)))]
                     (rn/make-link "e1->e2" [e1] [e2]
                                   :link-fn (fn [{:keys [input-rvts] :as input}]
                                              (swap! q-atom enqueue (f (rn/fvalue input-rvts))))))
                   (link (partial swap! r conj) [e2] [])]
      (apply push! (cons e1 ranges))
      (is (= expected @r))
      (is (= 3 (-> rn/*netref* rn/network :links count))))))
