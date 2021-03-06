# reactnet

Consistent value propagation through a network of reactives.

[![Build Status](https://travis-ci.org/friemen/reactnet.png?branch=master)](https://travis-ci.org/friemen/reactnet)

[![Clojars Project](http://clojars.org/reactnet/latest-version.svg)](http://clojars.org/reactnet)

[API docs](https://friemen.github.com/reactnet)

The goal is a core for a push-based reactive library that is designed
to avoid inconsistencies and bouncing effects and gracefully handles
infinite loops caused by cyclic dependencies.

It will be the foundation of a [reactor](https://github.com/friemen/reactor)
re-implementation.

Key ideas of reactnet are:
* The network is formed solely by links that point to reactives. There
  is no Observer or callback mechanism involved.
* Updates to the network (add / remove links) or propagations of
  events are enqueued and processed sequentially in a separate
  thread.
* To avoid blocking the propagation thread, link functions can be
  invoked asynchronously.
* Value propagation is done along the topological order of reactives
  and links.
* There can be many independent networks, processed by independant
  threads.

Limitations:
* Since value propagations and network changes are done sequentially
  in one thread this may become a bottleneck, in other words:
  throughput per network instance will always be limited to what one
  thread can handle. As one mitigation it helps to use separated
  networks, the other is to use executors for link-functions
  that use different threads.
* As callbacks are not used, reactnet must search for values that it must
  propagate. A high number of reactives (> 1000) will lead to low
  throughput. Again separated networks are a remedy.
  

## Introduction

This library is a low-level tool for creating combinators that support
[reactive programming](http://en.wikipedia.org/wiki/Reactive_programming).

To illustrate how reactnet can be used we take three steps:
* Provide functions that conveniently create links.
* Define the network itself, consisting of reactives and links between them.
* Use the network by pushing values to it.

You find the code of the following three sections in the
[example namespace](https://github.com/friemen/reactnet/blob/master/src/reactnet/example.clj).

### Define link factories
A *network* is solely defined by a set of links that refer to reactives.

A *reactive* is a thing that takes/returns values, *eventstreams* and
*behaviors* are concrete implementations of the `IReactive` protocol.

A *link* connects input with output reactives through a
*link-function*, in addition it carries an error handler, a handler
called upon completion of any input reactive and some other
settings.

Defining links is the crucial part. This is what well-known
combinators like `map`, `filter`, `switch` etc. are all about: they
add or remove links to a network.

Here's a first example. 

```clojure
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
```

The function above returns a new link, that applies a function f to
values found in input reactives. The result of the function
application is returned as value of the output reactives. This is - in
essence - already a *function lifting* implementation. It takes an
ordinary function and lifts it to work on reactives instead of plain
values.

Here's a second example, showing how a subscription to a single
reactive is implemented. The function f is applied to the first value
of the inputs. Any result is ignored.

```clojure
(defn subscribe-link
  "Subscribes a fn to one reactive."
  [f input-reactive]
  (rn/make-link "subscribe" [input-reactive] []
                :link-fn
                (fn [{:keys [input-rvts] :as input}]
                  (f (rn/fvalue input-rvts))
                  {})))
```

As you can see a link-function receives a map and returns a map, which
in both cases is a Result map. For details about its purpose and
contents please see the Concepts section below.

To make the network act dynamically with respect to the values flowing
through it, the link-functions must also be able to add or remove
links. (The code above does not demonstrate this.)


### Define a network

To actually create a network the following suffices:
```clojure
(def n (refs/agent-netref
        (rn/make-network "sample1" [])))
```

The var `n` now contains a thin wrapper around a Clojure agent. This
is necessary to support other execution models, for example based on
an atom (for unit testing) or on core.async channels and go-blocks.

Please note that an agent-based network reference executes propagation
and updates asynchronously (on a different thread). Therefore changes
do not become immediately visible on the thread that triggered an
update. The benefit is more consistency, because all propagations and
updates are processed sequentially in the order they were
enqueued. Another benefit is that the network propagation can deal
with cycles in the dependency graph. Since links and reactives are
ordered topologically the algorithm can sort upstream updates out and
push them into the queue, postponing them for a later propagation
cycle.

Although propapation is confined to one thread it is possible to use
link-functions that work asynchronously on different threads to avoid
blocking the propagation. Their results are simply enqueued as if they
were external stimuli. This means we can make trade-offs between
consistency and responsiveness at the granularity of specific links.


Let's define some reactives. *Behaviors* in the sense of reactnet are
like variables that know if their value has recently changed. They
can always provide the current value. This is the main difference to
*Eventstreams* which can be seen as sequences of value/timestamp
pairs. Once a value is consumed it's gone.

For now we stick to Behaviors. Here we create four of them, which are not
connected to any network and independent of each other:

```clojure
(def x   (rs/behavior "x" 1))
(def y   (rs/behavior "y" 2))
(def x+y (rs/behavior "x+y" nil))
(def z   (rs/behavior "z" nil))
```

To collect updates to z we use a Clojure atom:

```clojure
(def zs (atom []))
```

So far, none of these things is connected to a network. We change
this by adding links that reference input and output reactives.

```clojure
(rn/add-links! n
               (apply-fn-link + [x y] x+y)
               (apply-fn-link * [x+y x] z)
               (subscribe-link (partial swap! zs conj) z))
```

Ignoring `zs` we just created a network looking like this:

![x(x+y)](images/xx+y.png)

We can inspect it using `(pp netref)` ...

```clojure
(rn/pp n)
; Reactives
;   x+y:3
;   x:1
;   z:3
;   y:2
; Links
;  L2 [x y] -- clojure.core$_PLUS_@2039adf7 --> [x+y] READY
;  L4 [x+y x] -- clojure.core$_STAR_@2acc43a8 --> [z] READY
;  L6 [z] -- subscribe --> [] READY
;= nil
```

... or we can create a Graphviz dot representation of the network
using `(dot netref)` and use `clojure.java.shell/sh` to produce
an image

```clojure
(sh "dot" "-Tpng" "-o/tmp/g.png" :in (rn/dot n))
```

... like this

![dot output](images/dot.png)

... and `zs` already contains the first update to `z`.

```clojure
@zs
;= [3]
```


### Use the network

Let's update `x`

```clojure
(rn/push! n x 4)
```

If we deref `zs` then we find `[3 24]`. As you can see, although
changes to `x` cause two links to be re-evaluated (the `+` and the
`*`), only one update of the result `zs` happens.

This property is critical in case updates to a behavior cause
non-idempotent side-effects (e.g. sending a mail or adding a database
record).

Finally, to reset the network we can use `(reset-network! netref)`

```clojure
(rn/reset-network! n)
;= :reset
```

### And now?

Obviously, the API shown so far is too clumsy to be used to formulate
complex reactive solutions. Instead, we want to be able to write
expressions like

```clojure
(->> mouse
     (r/filter (fn [{:keys [trigger]}]
                 (= :clicked trigger)))
     (r/map vector things)
     (r/map react)
     (r/into things))
```

Here's how a `map` as used above could be implemented:

```clojure
(defn map
  [f & reactives]
  (let [new-r (eventstream "map")]
    (add-links! *netref* (make-link "map" reactives []
                                    :link-fn
                                    (fn [{:keys [input-rvts]}]
                                      (let [v (apply f (values input-rvts))]
                                        {:output-rvts (single-value v new-r)}))
                                    :complete-on-remove [new-r]))))
```

The following sections will explain how to use reactnet to create
functions like these.


## Concepts

Before explaining how to provide functionality that is nice to use you
will need to grasp some terminology. The following concepts form the
foundation of this library.

### Reactive

A reactive is something that takes and provides values, basically a
generalization of the concepts *Behavior* and
*Events(tream)*. Behaviors in the sense of reactnet are like
variables that know if their value has recently changed. They can
always provide the current value. This is the main difference to
Eventstreams which can be seen as sequences of value/timestamp
pairs. The following protocol shows the functions that the
propagation algorithm interacts with:

```clojure
(defprotocol IReactive
  (next-value [r]
    "Returns the next value-timestamp pair of the reactive r without
    consuming it.")
  (available? [r]
    "Returns true if the reactive r would provide a value upon
    consume!.")
  (pending? [r]
    "Returns true if r contains values that wait for being consumed.")
  (completed? [r]
    "Returns true if the reactive r will neither accept nor return a new value.")
  (consume! [r]
    "Returns the next value-timestamp pair of the reactive r and may
    turn the state into unavailable.")
  (deliver! [r value-timestamp-pair]
    "Sets/adds a pair of value and timestamp to r, returns true if a
  propagation of the value should be triggered."))
```

### Link
A map connecting input and output reactives via a function.
```
  :label               Label for pretty printing
  :inputs              Input reactives
  :outputs             Output reactives, each wrapped in WeakReference
  :link-fn             A link-function [Result -> Result] (see below)
  :error-fn            An error handling function [Result -> Result] (see below)
  :complete-fn         A function [Link Reactive -> Result] called when one of the
                       input reactives becomes completed
  :complete-on-remove  A seq of reactives to be completed when this link is removed
  :executor            The executor to use for invoking the link function (see below)
```
Reactives are known to the network solely by links referencing them.

A link is *ready* when all input reactives are available, i.e. they
are able to provide a next value.

A link is *dead* when at least one input reactive is completed, or all
output reactives are completed or nil. An empty outputs seq does not
count as "all completed or nil".


### Link function
 A function [Result -> Result] that takes a Result map containing
 input values and returns a Result map or nil.

### Error handling function
 A function [Result -> Result] that takes the Result containing an
 `:exception` entry. It may return a new Result map (see below) or nil.

### Complete function
 A function [Link Reactive -> Result] that is called for each input
 reactive whose completion is detected. It may return a Result map
 (see below) or nil.

### RVT
 A nested pair `[r [v t]]` representing a value `v` assigned to the
 Reactive `r` at time `t`.

### Result
A map passed into / returned by the link-fn, error-fn and complete-fn
with the following entries

```
  :input-reactives     The links input reactives
  :output-reactives    The links output reactives
  :input-rvts          A seq of RVTs
  :output-rvts         A seq of RVTs
  :no-consume          True if the preceding link evaluation
                       does not cause consumption of the input
  :exception           Exception, or nil if output-rvts is valid
  :add                 A seq of links to be added to the network
  :remove-by           An unary predicate that matches links to
                       be removed from the network
  :dont-complete       A seq of reactives for which to increase the
	                   alive counter
  :allow-complete      A seq of reactives for which to decrease the
                       alive counter
```

This map is the primary means for data exchange between functions
attached to links and the propagation / update algorithm.


### Network
A map containing the following entries

```
  :id                  A string that identifies the network for logging purposes
  :links               Collection of links
  :rid-map             WeakHashMap {Reactive -> rid}
                       rid = reactive id (derived)
  :level-map           Map {rid -> topological-level} (derived)
  :links-map           Map {rid -> Seq of links} (derived)
  :alive-map           Map {rid -> c} of reactives, where c is an integer
                       which is increased upon dont-complete and
                       decreased upon allow-complete. If c becomes 0
                       the corresponding reactive is auto completed
  :next-rid            Atom containing the next rid to assign
  :removes             An integer counting how many link removals happened
                       in order to decide when to rebuild the level-map
```

`rid` is a reactive identifier, an integer which is unique within a network.

### Stimulus
A map containing data that is passed to enq/update-and-propagate to
start an update/propagation cycle of a network.

```
  :exec                A vector containing a function [network & args -> network]
                       and additional args
  :results             A seq of Result maps
  :rvt-map             A map {Reactive -> [v t]} of values to propagate
```


### Network Reference
Serves as abstraction of how the network is stored and
propagation/updates to it are enqueued.

```clojure
(defprotocol INetworkRef
  (enq [netref stimulus]
    "Enqueue a new update/propagation cycle that will process a seq of
    result maps, remove links, which match the remove-by predicate, add
    new links and propagate the values in the {Reactive -> [v t]} map.
    An implementation should delegate to update-and-propagate
    function.")
  (scheduler [netref]
    "Return the scheduler.")
  (network [netref]
    "Return the network map."))
```

### Executor
Used to execute link functions in another thread / asynchronously.
Implementations are expected to bind the dynamic \*netref\* var to
the value of the netref arg.

```clojure
(defprotocol IExecutor
  (execute [e netref f]
    "Execute a no-arg function f in the context of the network
    reference netref."))
```

## Creating links

A network is made up of links. Most combinators are
essentially factories that create new reactives and link them via
specific functionality to other reactives. For any link you must
decide

* On which input and output reactives does it operate?
* How are output values computed from input values? Put it into
  the link-function of type [Result -> Result].
* What should happen in case the link-function throws an exception?
  The answer is encoded into the error handler, again of type
  [Result -> Result].
* What happens if one of the input reactives becomes completed? This
  is implemented as complete function of type
  [Link Reactive -> Result].
* Which reactives shall be set to completed when the link is removed
  from the network?

All of this information can be passed to
`(make-link label inputs outputs & {:keys [link-fn error-fn complete-fn complete-on-remove]})`
which in turn creates a Link map.

A link connects input with output reactives, consequently you pass
these to the `make-link` function.

In addition you can specify which reactives shall be completed when
this link is removed from the network by listing them in the value for
`:complete-on-remove`.  Since links are automatically removed when
they are considered dead this will lead to automatic completion.


Creating the three functions boils down to proper handling of the
Result map.

### Link function

The link-function is called in a propagation cycle if the link is
ready and the topological level of the cycle matches the links level.

Upon invocation the link-function will receive the following entries
in a map:

```
  :input-reactives     The links input reactives
  :output-reactives    The links output reactives
  :input-rvts          A seq of RVTs
```

To extract values from the RVT seq `:input-rvts` there are two
helpers:
* `(fvalue rvts)` returns the value of the first RVT from a seq of RVTs.
* `(values rvts)` returns a seq of values from a seq of RVTs.


The link-function can add the following entries

```
  :output-rvts         A seq of RVTs that will get delivered 
  :no-consume          True if the preceding link evaluation
                       does not cause consumption of the input
  :exception           Exception, or nil if output-rvts is valid
  :add                 A seq of links to be added to the network
  :remove-by           An unary predicate that matches links to be
                       removed from the network
  :dont-complete       A seq of reactives for which to increase the
	                   alive counter
  :allow-complete      A seq of reactives for which to decrease the
                       alive counter
```

Essentially the link-function tells the algorithm
* which values to deliver to other reactives (`:output-rvts`),
* which new links to add to the network (`:add`),
* which links to remove from the network (`:remove-by`).

To help produce the `:output-rvts` value there are four
functions for convenience:
* `(single-value v r)` produces a seq with one RVT using the current
  time as timestamp.
* `(broadcast-value v rs)` produces a seq of RVTs, one for each
  reactive in rs, each with the same value and timestamp.
* `(zip-values vs rs)` produces a seq of RVTs, where reactives and
  values are position-wise combined.
* `(enqueue-values vs r)` produces a seq of RVTs, all containing the
  same reactive r where each RVT carries on of the values of vs.


The `:no-consume` entry helps to avoid unwanted consumption of
values. Before invoking the link-function the algorithm asks for new
values by invoking `next-value` on each input reactive. Only after
evaluation the values are actually consumed by invoking `consume!` on
each reactive. The value of a reactive is NOT consumed if all links
depending on it return a truthy value for `:no-consume`. This enables
link-functions to reject a value after they examined it, an
implementation like `take-while` is a good example where this is
required.


### Error handler

If the link-function throws an exception or returns a Result
containing an `:exception` entry the error handler is invoked with the
`:exception` entry.

The error handler can use the same entries as the link-function. After
the error handler has been invoked the `:exception` entry has no
further effect. Instead of directly returning values the error handler
can schedule a task or push values via the network refs `enq`
function.


### Complete function

The complete function is invoked whenever the completion of an input
reactive is detected. It is supposed to return a Result map where it
can set any of the following entries:

```
  :output-rvts         A seq of RVTs
  :add                 A seq of links to be added to the network
  :remove-by           An unary predicate that matches links to be
                       removed from the network
```


## Examples

TODO Show some implementations of reactor

* derive-new
* Asynchronous function invocation with a scheduler: delay
* Asynchronous function invocation with an executor: map
* Stateful links: distinct


## How it works

TODO Give some more background on

* Topological levels
* Monitoring
* WeakHashMap / WeakReferences for outputs


### The propagation / network update algorithm

The `propagate` function is the heart of the algorithm, and it works
recursively. The maximum recursion depth corresponds to the
topological height of the network. It is invoked in the form
of `(propagate network pending-links [pending-reactives completed-reactives])`.
The arguments are:
* the network,
* any links from a prior call to it that weren't evaluated so far,
* any reactives that are known to have pending values,
* and reactives that are known to be completed.

Steps:

1. Collect links that must be evaluated because they're either pending
   or have an input reactive that is contained in the
   pending-reactives.
2. Select only those links that are on the minimum topological level
   and are actually ready to be evaluated.
3. Get next values from input reactives of the links that are going to
   be evaluated.
4. Evaluate all links on the same topological level, and collect
   results.
5. Consume all values unless a `:no-consume` entry is found in result.
6. Look for completed reactives, invoke corresponding link
   complete-fns, and collect the results.
7. Update the network from the results, which basically means add /
   remove links and complete reactives. An update causes
   re-calculation of the topological level assignment of links and
   reactives.
8. Push all upstream values contained in results, so they get enqueued
   for another propagation cycle, they will not be delivered/handled in
   this cycle.
9. Deliver downstream values contained in results to reactives and
   recursively invoke `propagate` for all values.

After `propagate` exits the outermost invocation a loop starts that
checks if there are still pending reactives. If yes, another
propagation cycle is started right-away. However, it is possible that
no link is ready to be evaluated, leading to no new results. In this
case the loop is terminated.


### Automatic link removal and completion

A link with at least one completed input or only completed outputs is
useless. It's called *dead* and will automatically be removed by the
following mechanism. Links point via `:complete-on-remove` key to
those reactives that will not receive any further input if the link is
dead. However, this doesn't mean that these reactives are immediately
allowed to go into the completed state since asynchronous / delayed
computations started from within link-functions could try to push
values to these reactives.

Therefore the network maintains a map in the `:alive-map` entry which
assigns a counter to a each reactive. This counter is
initially 1. Automatic completion decreases it by 1. If a link result
contains the reactive in a `:dont-complete` entry the counter is
increased. If a link result contains the reactive in the
`:allow-complete` entry the counter is decreased. If the counter
reaches 0 the `::completed` value is delivered to the reactives.

The `:dont-complete` and `:allow-complete` entries are used by link
functions and the `eval-link` function in case of asynchronous
execution.


### Debugging / logging

There is a namespace to support the collection of log statements for
debugging the propagation. Here's how to use it

```clojure
(require '[reactnet.debug :as dbg])
(dbg/on)
;; propagation actions are from now on logged to @dbg/log-agent
;; its contents is a vector with maps
;;
;; to dump the contents to console use
(dbg/to-console (dbg/lines))
;;
;; dbg/lines also accepts a predicate that can be used to filter the entries
;; aternatively you can use dbg/to-file if you want inspect the output
;; with an external editor
;;
(dbg/clear)
;; removes all log entries
;;
(dbg/off)
;; turns debug logging off
```


### Backpressure

The network can accumulate RVTs/updates in two places:

* The `(enq netref stimulus)` function is by default implemented using
  an agent's `send-off`, which enqueues computations in an unbounded
  queue.
* IReactive implementations like an eventstream that receive values
  via `(deliver! r value-timestamp-pair)` usually have to enqueue
  those to keep them for links which may not be ready for some
  period of time.

New RVTs originate from two sources:

* External stimuli via `push!` invocations are created by scheduled
  sampling, by incoming requests, or by events as triggered from a
  keyboard or mouse.
* Link functions themselves can produce an arbitrary number of values
  within the network, for example a mapcat/flatmap may collect values
  by calling external services. If these link functions were executed
  asynchronously the results are enqueued using `enq`. In case of
  synchronous execution the values are passed via `deliver!` to the
  target reactives.

To produce backpressure one needs to either block the thread creating
external stimuli or, alternatively, a `push!` invocation can throw an
exception.

In any case, it's important to never delay or block the thread
processing the propagation/update cycle as this would apparently
worsen congestion within the network.

Here's the approach of reactnet:

The goal is to avoid bad surprises and give external systems /
operations a chance to take note of a problem.

An IReactive implementation must not accumulate values
indefinitely. It has either a queue of limited size, throwing an
exception when this is exhausted, or it omits existing or new
values. An eventstream would typically be implemented using a queue
with a max size, whereas a behavior overwrites any existing value
whenever a new arrives.

In addition, the queue behind `enq` is monitored, and `enq` throws an
exception when the limit is reached. This way, new stimuli that
add values to already pending reactives will not get accepted until
the workload on the network falls below the maximum limit.

Whenever `deliver!` throws an exception, the results are enqueued as
stimuli via `enq`. If this also fails the propagation cycle will
terminate with an exception, which will lead to an agent in an
erroneous state.

## References

E.Amsden - [A Survey of Functional Reactive Programming](http://www.cs.rit.edu/~eca7215/frp-independent-study/Survey.pdf)

A.Courtney - [Frappe: Functional Reactive Programming in Java](http://haskell.cs.yale.edu/wp-content/uploads/2011/02/frappe-padl01.pdf)

A.Courtney, C.Elliot - [Genuinely Functional User Interfaces](http://haskell.cs.yale.edu/wp-content/uploads/2011/02/genuinely-functional-guis.pdf)

E.Czaplicki - [ELM: Concurrent FRP for Functional GUIs](http://elm-lang.org/papers/concurrent-frp.pdf)

C.Elliot, P.Hudak - [Functional Reactive Animation](http://conal.net/papers/icfp97/icfp97.pdf)

C.Elliot - [Push-pull functional reactive programming](http://conal.net/papers/push-pull-frp/push-pull-frp.pdf)

I.Maier, T.Rompf, M.Odersky - [Deprecating the Observer Pattern](http://lamp.epfl.ch/~imaier/pub/DeprecatingObserversTR2010.pdf)

L.Meyerovich - [Flapjax: Functional Reactive Web Programming](http://www.cs.brown.edu/research/pubs/theses/ugrad/2007/lmeyerov.pdf)


# License

Copyright 2014 F.Riemenschneider

Distributed under the Eclipse Public License 1.0.
