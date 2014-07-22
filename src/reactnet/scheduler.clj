(ns reactnet.scheduler
  "Thin wrapper around ScheduledThreadPoolExecutor"
  (:import [java.util.concurrent ScheduledThreadPoolExecutor TimeUnit]))


(defn scheduler
  "Creates a new scheduler with the specified number of worker threads."
  [threadpool-size]
  (ScheduledThreadPoolExecutor. threadpool-size))

(defn once
  "Schedules a no-arg function to be run after millis milliseconds.
  Returns a task."
  [s millis f]
  (.schedule s f millis TimeUnit/MILLISECONDS))

(defn interval
  "Schedules a no-arg function to be run immediately and then every
  millis milliseconds. Return a task."
  [s millis f]
  (.scheduleAtFixedRate s f 0 millis TimeUnit/MILLISECONDS))

(defn tasks
  "Returns a vector of all active/queued tasks."
  [s]
  (-> s .getQueue vec))

(defn cancel
  "Cancels a running task."
  [t]
  (.cancel t true))

(defn cancel-all
  "Cancels all active/queued tasks."
  [s]
  (doseq [t (tasks s)]
    (cancel t)))

(defn shutdown!
  "Shuts the scheduler down. Afterwards no tasks can be scheduled again."
  [s]
  (.shutdown s))
