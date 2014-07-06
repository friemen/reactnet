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

Define some reactives and their combinations:

```clojure
(use 'reactnet.core)
(defnetwork n)
(def x (behavior n "x" 0))
(def y (behavior n "y" 2))
(def x+y (rmap + x y))
(def zs (->> (rmap * x x+y)
             (rreduce conj [])))
```

This network collects in `zs` evaluation results of `x(x+y)`. Whenever
`x` or `y` changes a new value is stored in `zs`.

The `push!` function sends an external stimulus to the agent representing the network:

```clojure
(doseq [i (range 10)]
  (push! n x i))
;= nil
; x:0 <- 1
; map: <- 3
; map: <- 3
; reduce: <- [3]
; x:1 <- 2
; map:3 <- 4
; map:3 <- 8
; reduce:[3] <- [3 8]
; x:2 <- 3
; map:4 <- 5
; map:8 <- 15
; reduce:[3 8] <- [3 8 15]
; x:3 <- 4
; map:5 <- 6
; map:15 <- 24
; reduce:[3 8 15] <- [3 8 15 24]
; x:4 <- 5
; map:6 <- 7
; map:24 <- 35
; reduce:[3 8 15 24] <- [3 8 15 24 35]
; x:5 <- 6
; map:7 <- 8
; map:35 <- 48
; reduce:[3 8 15 24 35] <- [3 8 15 24 35 48]
; x:6 <- 7
; map:8 <- 9
; map:48 <- 63
; reduce:[3 8 15 24 35 48] <- [3 8 15 24 35 48 63]
; x:7 <- 8
; map:9 <- 10
; map:63 <- 80
; reduce:[3 8 15 24 35 48 63] <- [3 8 15 24 35 48 63 80]
; x:8 <- 9
; map:10 <- 11
; map:80 <- 99
; reduce:[3 8 15 24 35 48 63 80] <- [3 8 15 24 35 48 63 80 99]
``` 

As you can see, although changes to `x` cause two links to be
re-evaluated (the `+` and the `*`), only one update of the reactives
`z` and `zs` happens.

This property is critical for example in case updates to a behavior
cause side-effects.


## License

Copyright 2014 F.Riemenschneider

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
