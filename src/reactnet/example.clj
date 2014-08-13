(ns reactnet.example
  (:require [reactnet.core :as rn]
            [reactnet.reactives :as rs]
            [reactnet.netrefs :as refs]
            [clojure.java.shell :refer [sh]]
            [clojure.java.io :as io]))


;; example link factories

(defn apply-fn-link
  "Applies a function to input-reactives and passes the value to one
  output reactive."
  [f input-reactives output-reactive]
  (rn/make-link (str f) input-reactives [output-reactive]
                :link-fn
                (fn [{:keys [input-rvts output-reactives] :as input}]
                  (let [vs     (map rn/value input-rvts)
                        result (apply f vs)]
                    {:output-rvts (rn/broadcast-value result output-reactives)}))))

(defn subscribe-link
  "Subscribes a fn to one reactive."
  [f input-reactive]
  (rn/make-link "subscribe" [input-reactive] []
                :link-fn
                (fn [{:keys [input-rvts] :as input}]
                  (f (rn/fvalue input-rvts))
                  {})))


;; create a new network
(def n (refs/agent-netref
        (rn/make-network "sample1" [])))


;; define some reactives
(def x   (rs/behavior "x" 1))
(def y   (rs/behavior "y" 2))
(def x+y (rs/behavior "x+y" nil))
(def z   (rs/behavior "z" nil))

;; define a place to keep values of z
(def zs (atom []))

;; define one or more links between them
(rn/add-links! n
               (apply-fn-link + [x y] x+y)
               (apply-fn-link * [x+y x] z)
               (subscribe-link (partial swap! zs conj) z))




(Thread/sleep 50)

;; n holds the network in an agent, thus every operation is sent to
;; that agent, which means network updates / propagation happen
;; asynchronously

;; you can spy at the networks topology and reactives state
(rn/pp n)
; Reactives
;   z:24
;   x+y:6
;   x:4
;   y:2
; Links
;  L3 [x y] -- clojure.core$_PLUS_@2039adf7 --> [x+y] READY
;  L5 [x+y x] -- clojure.core$_STAR_@2acc43a8 --> [z] READY
;  L7 [z] -- subscribe --> [] READY
;= nil

;; you can also create a Graphviz output from a network
;; written to /tmp
#_ (sh "dot" "-Tpng" "-o/tmp/g.png" :in (rn/dot n))


; deref'ing zs shows that z already provided an initial value
(println @zs)
; [3]


;; push a value into the network
(rn/push! n x 4)
(Thread/sleep 50)

;; deref zs a few msecs later
@z
;= 24

(println @zs)
; [3 24]

;; the important thing to note here is:
;; although the update of x will trigger the update of x+y and x*(x+y)
;; only one update of z happens.

;; to remove all links from the network you can reset it:
#_ (rn/reset-network! n)

