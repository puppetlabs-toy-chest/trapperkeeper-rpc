(ns puppetlabs.trapperkeeper.rpc.services-test
  (:require [clojure.test :refer :all]
            [puppetlabs.trapperkeeper.testutils.bootstrap :refer [with-app-with-config]]
            [puppetlabs.trapperkeeper.app :refer [get-service]]
            [puppetlabs.http.client.sync :as http]
            [puppetlabs.trapperkeeper.services.webserver.jetty9-service :refer [jetty9-service]]
            [puppetlabs.trapperkeeper.rpc.testutils.dummy-services :refer [add subtract]]
            [puppetlabs.trapperkeeper.rpc.testutils.dummy-services.concrete :refer [rpc-test-service-concrete]]
            [puppetlabs.trapperkeeper.rpc.testutils.dummy-services.proxied :refer [rpc-test-service-proxied]]
            [puppetlabs.trapperkeeper.rpc.services :refer [rpc-server-service]])
  (:import [puppetlabs.trapperkeeper.rpc RPCException RPCConnectionException]))


(def config
  {:rpc {:RPCTestService
         {:protocol-ns "puppetlabs.trapperkeeper.rpc.testutils.dummy-services"
          :endpoint "http://localhost:9001/rpc/call"}}
   :webserver {:rpc {:host "0.0.0.0"
                     :port 9001}}})

;ssl-host: 0.0.0.0
;ssl-port: 4431
;ssl-key: "./dev-resources/ssl/key.pem"
;ssl-cert: "./dev-resources/ssl/cert.pem"
;ssl-ca-cert: "./dev-resources/ssl/ca.pem"
(def client-app-atom (atom nil))

(use-fixtures :once
  (fn [f]

    ;; This fixture starts up two TK stacks. One simply runs the
    ;; proxied test service while the other starts up a webserver that
    ;; exposes the RPC route and carries the actual implementation of
    ;; the test service.

    ;; We save a reference to the "client" app at global scope so we
    ;; can use it to invoke service functions.

    (with-app-with-config client-app
      [rpc-test-service-proxied]
      config

      (reset! client-app-atom client-app)

      (with-app-with-config server-app
        [rpc-test-service-concrete rpc-server-service jetty9-service]
        config

        (f)))))

(deftest rpc
  (testing "When invoking functions via RPC"
    (testing "and the specified service is not in the config"
      (testing "we see the expected exception"))
    (testing "and the specified function does not exist on the remote server"
      (testing "we see the expected exception"))
    (testing "and the remote function threw an exception"
      (testing "we see an RPCException"
        (testing "with the expected message")
        (testing "with a stacktrace")))
    (testing "and the RPC server is not reachable"
      (testing "we see an RPCConnectionException"))
    (testing "and the remote function worked"
      (let [result (add (get-service @client-app-atom :RPCTestService) 1 1)]
        (testing "we get the expected result"
          (is (= 2 result)))))))
