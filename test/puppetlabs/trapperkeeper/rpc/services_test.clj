(ns puppetlabs.trapperkeeper.rpc.services-test
  (:require [clojure.test :refer all]
            [puppetlabs.trapperkeeper.testutils.bootstrap :refer [with-app-with-config]]
            [puppetlabs.http.client.sync :as http]
            [puppetlabs.trapperkeeper.services.webserver.jetty9-service :refer [jetty9-service]]
            [puppetlabs.trapperkeeper.rpc.dummy-services.concrete :refer :all]
            [puppetlabs.trapperkeeper.rpc.dummy-services.proxied :refer :all]
            [puppetlabs.trapperkeeper.rpc.core :refer [call-remote-svc-fn]]
            [puppetlabs.trapperkeeper.rpc.services :refer rpc-server-service])
  (:import [puppetlabs.trapperkeeper.rpc RPCException RPCExecutionException]))


(def config
  {:rpc {:RPCTestService
         {:protocol-ns "puppetlabs.trapperkeeper.rpc.dummy-services.concrete"
          :endpoint "http://localhost:9001/rpc/call"}}})

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
      (testing "we see an RPCExecutionException"
        (testing "with the expected message")
        (testing "with a stacktrace")))
    (testing "and the remote function worked"
      (testing "we get the expected result"))))
