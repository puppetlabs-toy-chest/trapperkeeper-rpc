(ns puppetlabs.trapperkeeper.rpc.core
  "TODO"
  (:require [clojure.java.io :as io]
            [puppetlabs.certificate-authority.core :as ssl]
            [puppetlabs.kitchensink.core :refer [cn-whitelist->authorizer]]
            [puppetlabs.http.client.sync :as http]
            [slingshot.slingshot :refer [throw+]]
            [cheshire.core :as json])
  (:import [puppetlabs.trapperkeeper.rpc RPCException RPCConnectionException RPCAuthenticationException]))

;; TODO
;; * serialization abstraction / custom encoders
;; * schema


(defn- format-stacktrace [body]
  (if-let [stacktrace (:stacktrace body)]
    (format "Remote stacktrace:\n%s" stacktrace)
    ""))

(defn- handle-rpc-error! [body]
  (let [stacktrace (format-stacktrace body)
        msg (format "%s\n%s\n" (:msg body) stacktrace)]
    (throw (RPCException. msg))))

(defn settings->authorizers
  "Given a map of rpc settings, produces a map of service id ->
  function. Each function takes a request and returns true or false
  based on whether that request passes the service's cert whitelist.

  If no cert whitelist is provided for a service, it is unprotected
  (the function always returns true)."
  [rpc-settings]
  (->> (keys rpc-settings)
       (mapv (fn [svc-id]
               (if-let [cert-whitelist-path (get-in rpc-settings [svc-id :certificate-whitelist])]
                 [svc-id (cn-whitelist->authorizer cert-whitelist-path)]
                 [svc-id (constantly true)])))
       (into {})))

(defn body->string [r]
  (let [body (:body r)]
    (condp instance? body
      java.lang.String body
      (slurp (io/reader body)))))

(defn extract-body [r]
  (json/decode (body->string r) true))

(defn settings->ssl-context
  "Given the RPC settings, produce an ssl-context using
  pems->ssl-context."
  [rpc-settings]
  ; TODO
  {})

(defn post
  "Given rpc settings, a service id, an endpoint, and a payload, makes
  either an http or https request based on the presence of a cert
  whitelist."
  [rpc-settings svc-id endpoint payload]
  (let [basic-opts {:body (json/encode payload)
                    :headers {"Content-Type" "application/json;charset=utf-8"}}
        opts (if (true? (get-in rpc-settings [svc-id :use-ssl]))
               (assoc basic-opts :ssl-context (settings->ssl-context rpc-settings))
               basic-opts)]
    (http/post endpoint opts)))

(defn call-remote-svc-fn [rpc-settings svc-id fn-name & args]
  (let [payload {:svc-id svc-id
                 :fn-name fn-name
                 :args args}
        endpoint (get-in rpc-settings [svc-id :endpoint])]

    (when (nil? (rpc-settings svc-id))
      (throw (RPCException. (format "No entry for service %s in settings." svc-id))))

    (when (nil? endpoint)
      (throw (RPCException. (format "Could not find rpc endpoint for service %s in settings." svc-id))))

    (try
      (let [response (post rpc-settings svc-id endpoint payload)]

        (condp = (:status response)
          401 (throw (RPCAuthenticationException. "Permission denied to call functions from this service. Either request was not signed or was signed with a certificate not in the service's certificate whitelist."))
          200 (let [body (extract-body response)]

                (when (some? (:error body))
                  (handle-rpc-error! body))

                (:result body))
          (throw (RPCConnectionException. (format "RPC service did not return 200. Returned %s instead.\nReceived body: %s"
                                                  (:status response)
                                                  (body->string response))))))

      (catch java.net.ConnectException _
        (throw (RPCConnectionException. (format "RPC server is unreachable at endpoint %s" endpoint)))))))

(defn- lookup-fn [rpc-settings svc-id fn-name]
  (when (nil? (rpc-settings svc-id))
    (throw+ {:type ::no-such-svc
             :svc-id svc-id}))

  (if-let [protocol-ns (get-in rpc-settings [svc-id :protocol-ns])]
    (let [no-such-fn-exception {:type ::no-such-fn
                                :svc-id svc-id
                                :fn-name fn-name}]
      (try
        ;; TODO try to enforce respect for private functions
        (-> (symbol protocol-ns fn-name)
            find-var
            var-get)
        (catch IllegalArgumentException _
          (throw+ no-such-fn-exception))
        (catch NullPointerException _
          (throw+ no-such-fn-exception))))

    (throw+ {:type ::bad-cfg
             :msg (format "Could not find the :protocol-ns for service %s in settings." svc-id)})))

(defn- wrapped-get-service [get-service svc-id]
  (try
    (get-service svc-id)
    (catch IllegalArgumentException e
      (throw+ {:type ::no-such-svc
               :svc-id svc-id}))))

(defn call-local-svc-fn [rpc-settings get-service svc-id fn-name args]
  (let [svc (wrapped-get-service get-service svc-id)
        function (lookup-fn rpc-settings svc-id fn-name)]
      (apply function svc args)))
