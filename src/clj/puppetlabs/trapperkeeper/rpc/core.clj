(ns puppetlabs.trapperkeeper.rpc.core
  "TODO"
  (:require [clojure.java.io :as io]
            [puppetlabs.http.client.sync :as http]
            [slingshot.slingshot :refer [throw+]]
            [cheshire.core :as json])
  (:import [puppetlabs.trapperkeeper.rpc RPCException RPCConnectionException]))

;; TODO
;; * tests
;; * cert authentication
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

(defn extract-body [r]
  (let [body (:body r)]
    (json/decode
     (condp instance? body
       java.lang.String body
       (slurp (io/reader body)))
     true)))

(defn call-remote-svc-fn [rpc-settings svc-id fn-name & args]
  (let [payload {:svc-id svc-id
                 :fn-name fn-name
                 :args args}
        endpoint (get-in rpc-settings [svc-id :endpoint])]

    (when (nil? (rpc-settings svc-id))
      (throw (RPCException. (format "No entry for service %s in settings." svc-id))))

    (when (nil? endpoint)
      (throw (RPCException. (format "Could not find rpc endpoint for service %s in settings." svc-id))))

    ;; TODO cert
    ;; TODO handle cannot connect
    (try
      (let [response (http/post endpoint {:body (json/encode payload)
                                          :headers {"Content-Type" "application/json;charset=utf-8"}})]



        (when (not= 200 (:status response))
          ;; TODO use RPCConnectionException, here, and RPCException for
          ;; everything else.
          (throw (RPCConnectionException. (format "RPC service did not return 200. Returned %s instead.\nReceived body: %s"
                                                  (:status response)
                                                  (:body response)))))

        (let [body (extract-body response)]

          (when (some? (:error body))
            (handle-rpc-error! body))

          (:result body)))
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
