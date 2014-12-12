(ns puppetlabs.trapperkeeper.rpc.dummy-services)

(defprotocol RPCTestService
  (add [this x y])
  (subtract [this x y]))
