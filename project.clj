(def ks-version "1.0.0")
(def tk-version "1.0.1")

(defproject puppetlabs/trapperkeeper-rpc "1.0.0"
  :description "RPC server/client library for Trapperkeeper services"
  :url "https://github.com/puppetlabs/trapperkeeper-rpc"
  :pedantic? :abort
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [puppetlabs/trapperkeeper ~tk-version]
                 [puppetlabs/trapperkeeper-webserver-jetty9 "0.9.0"]
                 [puppetlabs/certificate-authority "0.6.0" :exclusions [clj-time]]
                 [com.cognitect/transit-clj "0.8.259" :exclusions [com.fasterxml.jackson.core/jackson-core]]
                 [compojure "1.2.0" :exclusions [org.clojure/tools.macro
                                                 clj-time/clj-time
                                                 commons-io]]
                 [puppetlabs/http-client "0.4.0"
                  :exclusions [commons-codec puppetlabs/certificate-authority]]
                 [clj-stacktrace "0.2.7"]]

  :lein-release {:scm         :git
                 :deploy-via  :lein-deploy}

  :source-paths ["src/clj"]
  :java-source-paths ["src/java"]

  :profiles {:dev {:dependencies [[puppetlabs/kitchensink ~ks-version :classifier "test" :exclusions [clj-time]]
                                  [puppetlabs/trapperkeeper ~tk-version :classifier "test"]]}
             :testutils {:source-paths ^:replace ["test"]}}

  :deploy-repositories [["releases" {:url "https://clojars.org/repo"
                                     :username :env/clojars_jenkins_username
                                     :password :env/clojars_jenkins_password
                                     :sign-releases false}]]

  :plugins [[lein-release "1.0.5"]])
