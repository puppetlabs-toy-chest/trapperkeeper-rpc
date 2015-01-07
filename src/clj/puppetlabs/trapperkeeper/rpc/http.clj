(ns puppetlabs.trapperkeeper.rpc.http
  (:require [puppetlabs.trapperkeeper.rpc.core :refer [call-local-svc-fn settings->authorizers]]
            [puppetlabs.trapperkeeper.rpc.wire :refer [build-response parse-request json-http-helper msgpack-http-helper]]
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

(defn- pick-http-helper [rpc-settings]
  (condp = (:wire-format rpc-settings)
    :msgpack msgpack-http-helper
    :json json-http-helper
    msgpack-http-helper))

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

(defn rpc-route [rpc-settings get-service]
  (let [authorizers (settings->authorizers rpc-settings)]
    (-> (routes
         (POST "/call" [:as r]
               (let [http-helper (pick-http-helper rpc-settings)
                     {:keys [svc-id fn-name args]} (parse-request http-helper r)
                     build-response (partial build-response http-helper)
                     whitelisted? (authorizers svc-id)]

                 (if-not (whitelisted? r)
                   (build-response {:error :permission-denied})
                   (try+

                    (->> (call-local-svc-fn rpc-settings get-service svc-id fn-name args)
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
