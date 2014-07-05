# reactnet

Serialized value propagation through a network of reactives.

Obviously this will limit throughput, but has the benefit of lower
probability for so-called glitches.

Goal is a core for a reactive library that is designed to avoid
inconsistencies and handles infinite loops caused by cyclic
dependencies gracefully.

Can't tell at the moment how far this approach will bring me...

## Usage

This is under construction. What is shown below is not meant as an API.

Define the map of reactives:

```clojure
(use 'reactnet.core)
(def rs {:x (react "x" 0)
         :y (react "y" 2)
         :x+y (react "x+y" 0)
         :z (react "z" 0)
         :zs (react "zs" [])})
```

Specify a network by listing all its links:

```clojure
(def n (agent (make-network (make-link "+" (map* +) [(:x rs) (:y rs)] [(:x+y rs)])
                            (make-link "*" (map* *) [(:x rs) (:x+y rs)] [(:z rs)])
                            (make-link "reduce-conj" (reduce* conj) [(:z rs)] [(:zs rs)]))
              :error-handler (fn [_ ex] (.printStackTrace ex))))
```

This network represents the expression `z = x(x+y)`, in addition all updates to `z`
are stored in a vector held by `zs`.

The `push!` function sends an external stimulus to the agent representing the network:

```clojure
(doseq [x (range 10)]
  (push! n (:x rs) x))
;= nil
; x:0 <- 1
; x+y:0 <- 3
; z:0 <- 3
; zs:[] <- [3]
; x:1 <- 2
; x+y:3 <- 4
; z:3 <- 8
; zs:[3] <- [3 8]
; x:2 <- 3
; x+y:4 <- 5
; z:8 <- 15
; zs:[3 8] <- [3 8 15]
; x:3 <- 4
; x+y:5 <- 6
; z:15 <- 24
; zs:[3 8 15] <- [3 8 15 24]
; x:4 <- 5
; x+y:6 <- 7
; z:24 <- 35
; zs:[3 8 15 24] <- [3 8 15 24 35]
; x:5 <- 6
; x+y:7 <- 8
; z:35 <- 48
; zs:[3 8 15 24 35] <- [3 8 15 24 35 48]
; x:6 <- 7
; x+y:8 <- 9
; z:48 <- 63
; zs:[3 8 15 24 35 48] <- [3 8 15 24 35 48 63]
; x:7 <- 8
; x+y:9 <- 10
; z:63 <- 80
; zs:[3 8 15 24 35 48 63] <- [3 8 15 24 35 48 63 80]
; x:8 <- 9
; x+y:10 <- 11
; z:80 <- 99
; zs:[3 8 15 24 35 48 63 80] <- [3 8 15 24 35 48 63 80 99]
``` 

As you can see, although changes to `x` cause two links to be
re-evaluated (the `+` and the `*`), only one update of the reactives
`z` and `zs` happens.

This property is critical for example in case updates to `z` cause
side-effects.


## License

Copyright 2014 F.Riemenschneider

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
