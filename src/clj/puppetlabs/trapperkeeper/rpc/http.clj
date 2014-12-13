(ns puppetlabs.trapperkeeper.rpc.http
  (:require [puppetlabs.trapperkeeper.rpc.core :refer [extract-body call-local-svc-fn]]
            [slingshot.slingshot :refer [try+]]
            [cheshire.core :as json]
            [clj-stacktrace.repl :refer [pst-str]]
            [compojure.core :refer [routes POST]]
            [ring.util.response :refer [response header]]))

(defn build-response [data]
  (-> data
      json/encode
      response
      (header "content-type" "application/json;charset=utf-8")))

(defn rpc-route [settings get-service]
  (routes
   (POST "/call" [:as r]
         ;; TODO handle authentication -- use a cert whitelist?
         (let [{:keys [svc-id fn-name args]} (extract-body r)]
           (try+

            (->> (call-local-svc-fn settings get-service (keyword svc-id) fn-name args)
                 (hash-map :result)
                 build-response)

            (catch [:type :puppetlabs.trapperkeeper.rpc.core/no-such-svc] {:keys [svc-id]}
                (build-response {:error :no-such-svc
                                :msg (format "The specified service does not exist in this application: %s" svc-id)}))

            (catch [:type :puppetlabs.trapperkeeper.rpc.core/no-such-fn] {:keys [svc-id fn-name]}
                (build-response {:error :no-such-fn
                                 :msg (format "The specified function %s does not exist in service %s" fn-name svc-id)}))

            (catch [:type :puppetlabs.trapperkeeper.rpc.core/bad-cfg] {:keys [msg]}
              (build-response {:error :bad-cfg
                               :msg msg}))

            (catch Throwable e
              (build-response {:error :exception
                               :msg (format "The function %s/%s threw an exception: %s" svc-id fn-name (.toString e))
                               :stacktrace (pst-str e)})))))))
