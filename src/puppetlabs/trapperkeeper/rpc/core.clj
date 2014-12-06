(ns puppetlabs.rbac.rpc
  "TODO"
  (:require [clojure.java.io :as io]
            [puppetlabs.http.client.sync :as http]
            [slingshot.slingshot :refer [throw+]]
            [cheshire.core :as json]))

;; TODO
;; * add error handling for rpc errors
;; * figure out how to register custom encoders
;; * schema
;; * serialization
;; * cert authentication

(defn- handle-http-error! [response]
  ;; TODO
  nil)

(defn- handle-rpc-error! [body]
  ;; TODO
  nil)

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

    (when (nil? endpoint)
      ;; TODO this is user-facing and should be a POJE
      (throw+ {:type ::bad-cfg
               :msg (format "Could not find rpc endpoint for service %s in settings." svc-id)}))

    (let [response (http/post endpoint {:body (json/encode payload)
                                        :headers {"Content-Type" "application/json;charset=utf-8"}})]

      (when (not= 200 (:status response))
        (handle-http-error! response))

      (let [body (extract-body response)]

        (when (some? (:error body))
          (handle-rpc-error! body))

        (:result body)))))

(defn- lookup-fn [rpc-settings svc-id fn-name]
  (if-let [protocol-ns (get-in rpc-settings [svc-id :protocol-ns])]
    (try
      (-> (symbol protocol-ns fn-name)
          find-var
          var-get)
      (catch IllegalArgumentException _
        (throw+ {:type ::no-such-fn
                 :svc-id svc-id
                 :fn-name fn-name})))
    (throw+ {:type ::bad-cfg
             :msg (format "Could not find the :protocol-ns for service %s in settings." % svc-id)})))

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
