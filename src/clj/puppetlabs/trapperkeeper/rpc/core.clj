(ns puppetlabs.trapperkeeper.rpc.core
  "TODO"
  (:require [clojure.java.io :as io]
            [puppetlabs.certificate-authority.core :as ssl]
            [puppetlabs.kitchensink.core :refer [cn-whitelist->authorizer]]
            [puppetlabs.http.client.sync :as http-sync]
            [puppetlabs.http.client.common :as http-common]
            [slingshot.slingshot :refer [throw+]]
            [puppetlabs.trapperkeeper.services :refer [service] :as tk-svc]
            [puppetlabs.trapperkeeper.rpc.wire :refer [json-serializer msgpack-serializer decode encode]])
  (:import [puppetlabs.trapperkeeper.rpc RPCException RPCConnectionException RPCAuthenticationException]))

(defn- format-stacktrace [body]
  (if-let [stacktrace (:stacktrace body)]
    (format "Remote stacktrace:\n%s" stacktrace)
    ""))

(defn- handle-rpc-error! [body]
  (if (= :permission-denied (keyword (:error body)))
    (throw (RPCAuthenticationException. "Permission denied to call functions from this service. Either request was not signed or was signed with a certificate not in the service's certificate whitelist."))
    (let [stacktrace (format-stacktrace body)
          msg (format "%s\n%s\n" (:msg body) stacktrace)]
      (throw (RPCException. msg)))))

(defn- pick-serializer [rpc-settings]
  (condp = (:wire-format rpc-settings)
    :msgpack msgpack-serializer
    :json json-serializer
    msgpack-serializer))

(defn settings->authorizers
  "Given a map of rpc settings, produces a map of service id ->
  function. Each function takes a request and returns true or false
  based on whether that request passes the service's cert whitelist.

  If no cert whitelist is provided for a service, it is unprotected
  (the function always returns true)."
  [rpc-settings]
  (let [mk-authorizer (fn [cert-whitelist-path]
                        (let [authorizer (cn-whitelist->authorizer cert-whitelist-path)]
                          (fn [req]
                            (if (= :http (:scheme req))
                              false
                              (authorizer req)))))]

    (->> (keys (:services rpc-settings))
         (mapv (fn [svc-id]
                 (if-let [cert-whitelist-path (get-in rpc-settings [:services svc-id :certificate-whitelist])]
                   [svc-id (mk-authorizer cert-whitelist-path)]
                   [svc-id (constantly true)])))
         (into {}))))

(defn body->string [r]
  (let [body (:body r)]
    (condp instance? body
      java.lang.String body
      (slurp (io/reader body)))))

(defn settings->ssl-context
  "Given the RPC settings, produce an ssl-context using
  pems->ssl-context."
  [rpc-settings]
  (let [ssl-settings (:ssl rpc-settings)]

    (when-not (every? some? (vals (select-keys ssl-settings [:client-cert :client-key :client-ca])))
      (throw (RPCException. "SSL desired but misconfigured. Ensure that :client-cert, :client-key, and :client-ca are all set.")))


    (ssl/pems->ssl-context (:client-cert ssl-settings)
                           (:client-key ssl-settings)
                           (:client-ca ssl-settings))))

(defn https? [^String s] (.startsWith s "https"))

(defn settings->client*
  "Given a map of RPC settings and a keyword service id, returns a
  synchronous HTTP client that sets up ssl context if appropriate and
  no other options."
  [rpc-settings svc-id]
  (let [endpoint (get-in rpc-settings [:services svc-id :endpoint])
        ssl-context (if (https? endpoint) (settings->ssl-context rpc-settings))
        client-opts (if (some? ssl-context) {:ssl-context ssl-context} {})]

    ;; TODO support an async client for functions where return is
    ;; not cared about
    (http-sync/create-client client-opts)))

(def settings->client (memoize settings->client*))

(defn post
  "Given rpc settings, a service id, an endpoint, and a payload, makes
  either an http or https request based on the presence of a cert
  whitelist."
  [client rpc-settings svc-id endpoint payload]
  (let [encode (partial encode (pick-serializer rpc-settings))
        opts {:body (encode payload)
              :headers {"Content-Type" "application/json;charset=utf-8"}}]

    (http-common/post client endpoint opts)))

(defn call-remote-svc-fn
  "Given a map of RPC settings and a map of options with keys :svc-id,
  :fn-name, :args and optionally :client, calls :fn-name from service
  :svc-id passing on :args via an HTTP call to the proper RPC
  endpoint.

  This function is not meant to be used directly unless there is a
  very good reason. Favor instead the defremoteservice macro."
  [rpc-settings {:keys [svc-id] :as opts}]

  (when (nil? (get-in rpc-settings [:services svc-id]))
    (throw (RPCException. (format "No entry for service %s in settings." svc-id))))

  (let [endpoint (get-in rpc-settings [:services svc-id :endpoint])]

    (when (nil? endpoint)
      (throw (RPCException. (format "Could not find rpc endpoint for service %s in settings." svc-id))))


    (let [payload (select-keys opts [:svc-id :fn-name :args])
          default-opts {:retry false} ;; TODO more default opts
          opts (-> opts
                   (dissoc :svc-id :fn-name :args)
                   (merge default-opts))
          client (or (:client opts) (settings->client rpc-settings svc-id))]

    (try
      (let [response (post client rpc-settings svc-id endpoint payload)]

        (when-not (= 200 (:status response))
          (throw (RPCConnectionException. (format "RPC service did not return 200. Returned %s instead.\nReceived body: %s"
                                                  (:status response)
                                                  (body->string response)))))

        (let [decode (partial decode (pick-serializer rpc-settings))
              body (decode (:body response))]

          (when (some? (:error body))
            (handle-rpc-error! body))

          (:result body)))

      (catch java.net.ConnectException _
        (throw (RPCConnectionException. (format "RPC server is unreachable at endpoint %s" endpoint))))))))

(defn- parse-fn-forms [fn-forms]
  (mapv (fn [fn-form]
          {:fn-name (first fn-form)
           :fn-sig (second fn-form)
           :fn-opts (if (= 3 (count fn-form))
                      (last fn-form)
                      {})})
        fn-forms))

(defn- parse-remote-svc-forms [forms]
  {:service-protocol-sym (first forms)
   :fn-forms (parse-fn-forms (rest forms))})

;; 17 macron
(defmacro remote-service
  "Converts this:
  (remote-service FooService
                  (add [this x y])
                  (divide [this x y] {:retry true :timeout 1000}))

  Into a form roughly like this:
  (service
    FooService
    [[:ConfigService get-in-config]]
    (add [this x y] (call-remote-svc-fn (get-in-config [:rpc]) {:svc-id :FooService :fn-name :add :args [x y]}))
    (divide [this x y] (call-remote-svc-fn (get-in-config [:rpc]) {:retry true :timeout 1000 :svc-id :FooService :fn-name :divide :args [x y]})))
  "
  [& forms]
  (let [{:keys [service-protocol-sym fn-forms]} (parse-remote-svc-forms forms)]
    ;; TODO switch client based on options
    `(service

      ;; Specify the service protocl
      ~service-protocol-sym

      ;; Hardcode a list of deps for the service
      [[:ConfigService ~'get-in-config]]

      ;; function bodies
      ~@(for [fn-form fn-forms]

          ;; name of function
          `( ~(:fn-name fn-form)

             ;; function parameters
             [~@(:fn-sig fn-form)]

               (let [rpc-settings# (~'get-in-config [:rpc])
                     svc-id# ~(keyword service-protocol-sym)]

                 ;; function body
                 (call-remote-svc-fn rpc-settings#
                                     (merge ~(:fn-opts fn-form)
                                            {:svc-id svc-id#
                                             :client (settings->client rpc-settings# svc-id#)
                                             :fn-name ~(keyword (:fn-name fn-form))
                                             :args [~@(rest (:fn-sig fn-form))]}))))))))

(defmacro defremoteservice [name & forms]
  `(def ~name (remote-service ~@forms)))

(defn- lookup-fn [rpc-settings svc-id fn-name]
  (when (nil? (get-in rpc-settings [:services svc-id]))
    (throw+ {:type ::no-such-svc
             :svc-id svc-id}))

  (if-let [protocol-ns (get-in rpc-settings [:services svc-id :protocol-ns])]
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
