(ns puppetlabs.trapperkeeper.rpc.wire
  (:require [clojure.java.io :as io]
            [cognitect.transit :as transit]
            [cheshire.core :as json]
            [ring.util.response :refer [response header]])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream]))

(defprotocol RPCSerializer
  (encode [this data])
  (decode [this data]))

(def transit-serializer
  (reify RPCSerializer
    (encode [this data] "HI")
    (decode [this data] "HI")))

(def json-serializer
  (reify RPCSerializer

    (encode [this data]
      (json/encode data))

    (decode [this data]
      (let [data (if (instance? java.lang.String data)
                   data
                   (slurp (io/reader data)))]

        (json/decode data true)))))

(defprotocol RPCWire
  (build-response [this data])
  (parse-request [this request]))

(def transit-wire
  (reify RPCWire
    (build-response [this data] "TODO")
    (parse-request [this request] "TODO")))

(def json-wire
  (reify RPCWire
    (build-response [this data]
      (-> (encode json-serializer data)
        response
        (header "content-type" "application/json;charset=utf-8")))

    (parse-request [this request]
      (let [parsed (decode json-serializer (:body request))]
        (assoc parsed :svc-id (keyword (:svc-id parsed)))))))
