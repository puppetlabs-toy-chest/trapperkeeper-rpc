(ns puppetlabs.trapperkeeper.rpc.http
  (:require [puppetlabs.trapperkeeper.rpc.core :refer [call-local-svc-fn settings->authorizers]]
            [puppetlabs.trapperkeeper.rpc.wire :refer [build-response parse-request json-wire transit-wire]]
            [puppetlabs.kitchensink.core :refer [cn-whitelist->authorizer]]
            [puppetlabs.certificate-authority.core :refer [get-cn-from-x500-principal]]
            [slingshot.slingshot :refer [try+]]
            [clj-stacktrace.repl :refer [pst-str]]
            [compojure.core :refer [routes POST]]
            [ring.util.response :refer [response header status]]))

;; TODO copypasta'd here until ring-middleware gets a release.
(defn cert->principal
  "Given an ssl cert, extract and return its X500Principal."
  [cert]
  (.getSubjectX500Principal cert))

;; TODO copypasta'd here until ring-middleware gets a release.
(defn wrap-with-certificate-cn
  "Ring middleware that will annotate the request with an
  :ssl-client-cn key representing the CN contained in the client
  certificate of the request. If no client certificate is present,
  the key's value is set to nil."
  [handler]
  (fn [{:keys [ssl-client-cert] :as req}]
    (let [cn  (some-> ssl-client-cert
                      cert->principal
                      get-cn-from-x500-principal)
          req (assoc req :ssl-client-cn cn)]

      (handler req))))

(defn rpc-route [settings get-service]
  (let [authorizers (settings->authorizers settings)]
    (-> (routes
         (POST "/call" [:as r]
               (let [wire (condp = (:wire-format settings) :json json-wire :transit transit-wire json-wire)
                     {:keys [svc-id fn-name args]} (parse-request wire r)
                     build-response (partial build-response wire)
                     whitelisted? (authorizers svc-id)]

                 (if-not (whitelisted? r)
                   (build-response {:error :permission-denied})
                   (try+

                    (->> (call-local-svc-fn settings get-service svc-id fn-name args)
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

                    (catch Exception e
                      (build-response {:error :exception
                                       :msg (format "The function %s/%s threw an exception: %s" svc-id fn-name (.toString e))
                                       :stacktrace (pst-str e)})))))))
        wrap-with-certificate-cn)))
