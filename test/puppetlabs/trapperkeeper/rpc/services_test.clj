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
  (:import [puppetlabs.trapperkeeper.rpc RPCException RPCConnectionException RPCAuthenticationException]))


(def config
  {:rpc {:wire-format :msgpack
         :services {:RPCTestService
                    {:protocol-ns "puppetlabs.trapperkeeper.rpc.testutils.dummy-services"
                     :endpoint "http://localhost:9001/rpc/call"}}}

   :webserver {:rpc {:host "0.0.0.0"
                     :port 9001}}})

(def ssl-server-settings {:ssl-host "0.0.0.0"
                          :ssl-port 9002
                          :ssl-key "./dev-resources/ssl/key.pem"
                          :ssl-cert "./dev-resources/ssl/cert.pem"
                          :ssl-ca-cert "./dev-resources/ssl/ca.pem"})

(def server-services [rpc-test-service-concrete rpc-server-service jetty9-service])

(deftest end-to-end
  (doseq [fmt [:msgpack :json]]
    (testing "When invoking functions via RPC"
      (with-app-with-config client-app
        [rpc-test-service-proxied]
        (assoc-in config [:rpc :wire-format] fmt)

        (with-app-with-config server-app
          server-services
          (assoc-in config [:rpc :wire-format] fmt)

          (testing "and the remote function worked"
            (let [result (add (get-service client-app :RPCTestService) 1 1)]

              (testing "we get the expected result"
                (is (= 2 result))))))))))

(deftest error-handling
  (doseq [fmt [:msgpack :json]]
    (testing "When invoking functions via RPC"
      (let [config (assoc-in config [:rpc :wire-format] fmt)
            rpc-settings (:rpc config)]
        (with-app-with-config server-app
          server-services
          config

          (testing "and the specified service is not in the local config we see the expected exception"
            (is (thrown-with-msg? RPCException
                                  #".*No entry for service :NoSuchService.*"
                                  (call-remote-svc-fn rpc-settings {:svc-id :NoSuchService :fn-name :add :args [1 1]}))))

          (testing "and the specified function does not exist on the remote server we see the expected exception"
            (is (thrown-with-msg? RPCException
                                  #".*specified function dne does not exist.*"
                                  (call-remote-svc-fn rpc-settings {:svc-id :RPCTestService :fn-name :dne :args [1 1]}))))

          (testing "and the remote function threw an exception"
            (try
              (call-remote-svc-fn rpc-settings {:svc-id :RPCTestService :fn-name :fun-divide :args [2]})
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
                (call-remote-svc-fn rpc-settings {:svc-id :RPCTestService :fn-name :add :args [1 2]})
                (catch Exception e
                  (testing "we see an RPCConnectionException"
                    (is (instance? RPCConnectionException e))
                    (testing "and the expected message"
                      (is (re-find #"Returned 500" (.toString e))))
                    (testing "and the body"
                      (is (re-find #"oh no" (.toString e)))))))))

          (testing "and the RPC server is unreachable"
            (let [bad-settings (assoc-in rpc-settings [:services :RPCTestService :endpoint] "http://localhost:6666")]
              (testing "we see the expected exception"
                (is (thrown-with-msg? RPCConnectionException
                                      #"RPC server is unreachable at endpoint http://localhost:6666"
                                      (call-remote-svc-fn bad-settings {:svc-id :RPCTestService :fn-name :add :args [1 2]})))))))))))

(deftest certificate-whitelist
  (let [ssl-config (-> config
                       (assoc-in [:webserver :rpc] (merge (get-in config [:webserver :rpc]) ssl-server-settings))
                       (assoc-in [:rpc :services :RPCTestService :endpoint] "https://localhost:9002/rpc/call")
                       (assoc-in [:rpc :services :RPCTestService :certificate-whitelist] "dev-resources/ssl/certs.txt")
                       (assoc-in [:rpc :ssl] {:client-cert "dev-resources/ssl/cert.pem"
                                              :client-key "dev-resources/ssl/key.pem"
                                              :client-ca "dev-resources/ssl/ca.pem"}))]

    (testing "When remotely calling a service function and there is a whitelist for that service"

      (testing "and the request is signed"

        (testing "with a whitelisted cert"
          (with-app-with-config app server-services ssl-config
            (let [result (call-remote-svc-fn (:rpc ssl-config) {:svc-id :RPCTestService :fn-name :add :args [1 2]})]

              (testing "the call is successful"
                (is (= 3 result))))))

            (testing "with a non-whitelisted cert"
              (with-app-with-config app
                server-services
                (assoc-in ssl-config [:rpc :services :RPCTestService :certificate-whitelist] "dev-resources/ssl/dne-certs.txt" )
                (testing "the call throws an RPCAuthenticationException"
                  (is (thrown-with-msg? RPCAuthenticationException
                                        #"Permission denied"
                                        (call-remote-svc-fn (:rpc ssl-config) {:svc-id :RPCTestService :fn-name :add :args [1 2]}))))))

          (testing "and the request is not signed"
            (let [whitelist-with-http-config (-> (assoc-in ssl-config [:rpc :services :RPCTestService :endpoint] "http://localhost:9001/rpc/call"))]
                  (with-app-with-config app
                    server-services
                    whitelist-with-http-config

                    (testing "the call throws an RPCAuthenticationException"
                      (is (thrown-with-msg? RPCAuthenticationException
                                            #"Permission denied"
                                            (call-remote-svc-fn (:rpc whitelist-with-http-config) {:svc-id :RPCTestService :fn-name :add :args [1 2]})))))))))))
