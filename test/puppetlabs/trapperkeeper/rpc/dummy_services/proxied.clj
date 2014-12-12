(ns puppetlabs.trapperkeeper.rpc.dummy-services.proxied
  (:require [puppetlabs.trapperkeeper.rpc :refer [RPCTestService]]
            [puppetlabs.trapperkeeper.services :refer [defservice]]))

(defservice rpc-test-service-proxied
  RPCTestService
  [[:ConfigService get-in-config]]
  (add [this x y] (call-remote-svc-fn (get-in-config [:rpc]) :RPCTestService :add x y))
  (subtract [this x y] (call-remote-svc-fn (get-in-config [:rpc]) :RPCTestService :subtract x y)))
