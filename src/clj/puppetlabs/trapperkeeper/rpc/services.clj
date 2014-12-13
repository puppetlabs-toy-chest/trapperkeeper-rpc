(ns puppetlabs.trapperkeeper.rpc.services
  "TODO"
  (:require [puppetlabs.trapperkeeper.services :refer [defservice get-service]]
            [puppetlabs.trapperkeeper.rpc.http :refer [rpc-route]]

            [compojure.core :as c]
            [ring.util.servlet :refer [servlet]]
            ))

(def rpc-path "This path is anchored to the root of the server with the :rpc server id." "/rpc")

(defservice rpc-server-service
  [[:ConfigService get-in-config]
   [:WebserverService add-servlet-handler]]

  (init [this tk-ctx]
        (let [rpc-settings (get-in-config [:rpc])
              rpc-app (->> (rpc-route rpc-settings (partial get-service this))
                           (c/context rpc-path [])
                           servlet)]
          (add-servlet-handler rpc-app rpc-path {:server-id :rpc})
          tk-ctx)))
