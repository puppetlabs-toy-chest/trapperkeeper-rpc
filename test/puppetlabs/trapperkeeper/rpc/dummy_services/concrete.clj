(ns puppetlabs.trapperkeeper.rpc.dummy-services.concrete
  (:require [puppetlabs.trapperkeeper.rpc :refer [RPCTestService]]
            [puppetlabs.trapperkeeper.services :refer [defservice]]))


(defservice rpc-test-service-concrete
  RPCTestService
  []
  (add [this x y] (+ x y))
  (subtract [this x y] (- x y)))
