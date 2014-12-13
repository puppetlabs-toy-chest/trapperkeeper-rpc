(ns puppetlabs.trapperkeeper.rpc.testutils.dummy-services)

(defprotocol RPCTestService
  (add [this x y])
  (subtract [this x y]))
