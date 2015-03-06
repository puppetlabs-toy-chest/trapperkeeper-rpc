(ns puppetlabs.trapperkeeper.rpc.testutils.dummy-services.proxied
  (:require [puppetlabs.trapperkeeper.rpc.testutils.dummy-services :refer [RPCTestService]]
            [puppetlabs.trapperkeeper.rpc.core :refer [defremoteservice]]))

(defremoteservice rpc-test-service-proxied
  RPCTestService
  (add [this x y])
  (fun-divide [this x]))
