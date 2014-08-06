(ns reactnet.scheduler
  "Scheduling functions based on JDKs ScheduledThreadPoolExecutor."
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
  "Schedules a no-arg function to be run immediately (of after an
  initial-millis delay) and then every rate-millis milliseconds. Returns a task."
  ([s rate-millis f]
     (interval s 0 rate-millis f))
  ([s initial-millis rate-millis f]
     (.scheduleAtFixedRate s f initial-millis rate-millis TimeUnit/MILLISECONDS)))


(defn tasks
  "Returns a vector of all active/pending tasks."
  [s]
  (-> s .getQueue vec))


(defn cancel
  "Cancels a running task."
  [t]
  (.cancel t true))


(defn pending?
  "Returns true if the task is active/pending."
  [t]
  (not (or (.isCancelled t)
           (.isDone t))))


(defn cancel-all
  "Cancels all active/queued tasks."
  [s]
  (doseq [t (tasks s)]
    (cancel t)))


(defn shutdown!
  "Shuts the scheduler down. Afterwards no tasks can be scheduled again."
  [s]
  (.shutdown s))
