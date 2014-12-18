(ns puppetlabs.trapperkeeper.rpc.testutils.dummy-services)

(defprotocol RPCTestService
  (add [this x y])
  (fun-divide [this x]))
