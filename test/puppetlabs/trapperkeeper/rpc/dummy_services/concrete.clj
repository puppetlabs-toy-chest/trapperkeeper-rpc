(ns puppetlabs.trapperkeeper.rpc.dummy-services.concrete
  (:require [puppetlabs.trapperkeeper.rpc.dummy-services :refer [RPCTestService]]
            [puppetlabs.trapperkeeper.rpc.core :refer [call-remote-svc-fn]]
            [puppetlabs.trapperkeeper.services :refer [defservice]]))


(defservice rpc-test-service-concrete
  RPCTestService
  []
  (add [this x y] (+ x y))
  (subtract [this x y] (- x y)))
