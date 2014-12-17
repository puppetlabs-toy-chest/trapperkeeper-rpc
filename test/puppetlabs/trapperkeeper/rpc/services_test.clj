(ns puppetlabs.trapperkeeper.rpc.services-test
  (:require [clojure.test :refer :all]
            [puppetlabs.trapperkeeper.testutils.bootstrap :refer [with-app-with-config]]
            [puppetlabs.trapperkeeper.app :refer [get-service]]
            [puppetlabs.certificate-authority.core :as ssl]
            [puppetlabs.http.client.sync :as http]
            [puppetlabs.trapperkeeper.services.webserver.jetty9-service :refer [jetty9-service]]
            [puppetlabs.trapperkeeper.rpc.services :refer [rpc-server-service]]
            [puppetlabs.trapperkeeper.rpc.core :refer [call-remote-svc-fn]]
            [puppetlabs.trapperkeeper.rpc.testutils.dummy-services :refer [add fun-divide]]
            [puppetlabs.trapperkeeper.rpc.testutils.dummy-services.concrete :refer [rpc-test-service-concrete]]
            [puppetlabs.trapperkeeper.rpc.testutils.dummy-services.proxied :refer [rpc-test-service-proxied]])
  (:import [puppetlabs.trapperkeeper.rpc RPCException RPCConnectionException]))


(def config
  {:rpc {:RPCTestService
         {:protocol-ns "puppetlabs.trapperkeeper.rpc.testutils.dummy-services"
          :endpoint "http://localhost:9001/rpc/call"}}

   :webserver {:rpc {:host "0.0.0.0"
                     :port 9001}}})

(def ssl-server-settings {:ssl-host "0.0.0.0"
                          :ssl-port 9002
                          :ssl-key "./dev-resources/ssl/key.pem"
                          :ssl-cert "./dev-resources/ssl/cert.pem"
                          :ssl-ca-cert "./dev-resources/ssl/ca.pem"})

(deftest end-to-end
  (testing "When invoking functions via RPC"
    (with-app-with-config client-app
      [rpc-test-service-proxied]
      config

      (with-app-with-config server-app
        [rpc-test-service-concrete rpc-server-service jetty9-service]
        config

        (testing "and the remote function worked"
          (let [result (add (get-service client-app :RPCTestService) 1 1)]

            (testing "we get the expected result"
              (is (= 2 result)))))))))

(deftest error-handling
  (testing "When invoking functions via RPC"
    (let [rpc-settings (:rpc config)]
      (with-app-with-config server-app
        [rpc-test-service-concrete rpc-server-service jetty9-service]
        config

        (testing "and the specified service is not in the local config we see the expected exception"
          (is (thrown-with-msg? RPCException
                               #".*No entry for service :NoSuchService.*"
                               (call-remote-svc-fn rpc-settings :NoSuchService :add 1 1))))

        (testing "and the specified function does not exist on the remote server we see the expected exception"
          (is (thrown-with-msg? RPCException
                                #".*specified function dne does not exist.*"
                                (call-remote-svc-fn rpc-settings :RPCTestService :dne 1 1))))

        (testing "and the remote function threw an exception"
          (try
            (call-remote-svc-fn rpc-settings :RPCTestService :fun-divide 2)
            (catch Exception e
              (testing "we see an RPCException"
                (is (instance? RPCException e))

                (testing "with the expected message"
                  (is (re-find #"(?m)The function :RPCTestService/fun-divide threw.*ArithmeticException.*Divide by zero"
                                  (.toString e))))
                (testing "with a stacktrace"
                  (is (re-find #"Numbers.java" (.toString e))))))))

        (testing "and the RPC server returns an error status code"
          (with-redefs [http/post (constantly {:status 500 :body "oh no"})]
            (try
              (call-remote-svc-fn rpc-settings :RPCTestService :add 1 2)
              (catch Exception e
                (testing "we see an RPCConnectionException"
                  (is (instance? RPCConnectionException e))
                  (testing "and the expected message"
                    (is (re-find #"Returned 500" (.toString e))))
                  (testing "and the body"
                    (is (re-find #"oh no" (.toString e)))))))))

        (testing "and the RPC server is unreachable"
          (let [bad-settings (assoc-in rpc-settings [:RPCTestService :endpoint] "http://localhost:6666")]
            (testing "we see the expected exception"
              (is (thrown-with-msg? RPCConnectionException
                                    #"RPC server is unreachable at endpoint http://localhost:6666"
                                    (call-remote-svc-fn bad-settings :RPCTestService :add 1 2))))))))))

(deftest certificate-whitelist
  (let [;ssl-context (ssl/pems->ssl-context "dev-resources/ssl/cert.pem"
        ;                                   "dev-resources/ssl/key.pem"
        ;                                   "dev-resources/ssl/ca.pem")
        ssl-config (assoc-in config [:rpc :RPCTestService :endpoint] "https://localhost:9002/rpc/call")]
    ;; TODO FOR TOMORROW
    ;; * get server certs in place
    ;; * get client certs in place
    ;; * actually write code for conditionally using certs from client
    ;; * test all of that
    (testing "When remotely calling a service function"
      (testing "and there is no whitelist for that service"
        (testing "the call is successful"))
      (testing "and there is a whitelist for that service"
        (let [whitelisted-config (assoc-in ssl-config [:rpc :RPCTestService :certificate-whitelist] "dev-resources/ssl/certs.txt")]
          (testing "and the request is signed"
            (testing "with a whitelisted cert"
              (testing "the call is successful"))
            (testing "with a non-whitelisted cert"
              (let [dne-whitelisted-config (assoc-in whitelisted-config [:rpc :RPCTestService :certificate-whitelist] "dev-resources/ssl/dne-certs.txt")]
                (testing "the call throws an RPCAuthenticationException"))))
          (testing "and the request is not signed"
            (testing "the call throws an RPCAuthenticationException")))))))
