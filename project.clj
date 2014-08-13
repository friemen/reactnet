(defproject reactnet "0.1.0-SNAPSHOT"
  :description "Consistent value propagation through a reactive network"
  :url "https://github.com/friemen/reactnet"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.5.1"]]
  :plugins [[codox "0.8.10"]]
  :codox {:defaults {}
          :sources ["src"]
          :exclude [reactnet.example reactnet.ex-swing reactnet.ex-services]
          :src-dir-uri "https://github.com/friemen/reactnet/blob/master/"
          :src-linenum-anchor-prefix "L"})
