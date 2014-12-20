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

    (encode [this data]
      (let [out (ByteArrayOutputStream.) ;; grow as needed
            writer (transit/writer out :json)]
        (transit/write writer data)
        (ByteArrayInputStream. (.toByteArray out))))

    (decode [this data]
      (let [in (ByteArrayInputStream. (if (instance? java.lang.String data)
                                        (.getBytes data)
                                        (.getBytes (slurp (io/reader data)))))
            reader (transit/reader in :json)]
        (transit/read reader)))))

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
    (build-response [this data]
      (-> (encode transit-serializer data)
          response
          (header "content-type" "application/octet-stream")))

    (parse-request [this request]
      (let [parsed (decode transit-serializer (:body request))]
        (-> parsed
            (assoc :svc-id (keyword (:svc-id parsed)))
            (assoc :fn-name (name (:fn-name parsed))))))))

(def json-wire
  (reify RPCWire
    (build-response [this data]
      (-> (encode json-serializer data)
        response
        (header "content-type" "application/json;charset=utf-8")))

    (parse-request [this request]
      (let [parsed (decode json-serializer (:body request))]
        (assoc parsed :svc-id (keyword (:svc-id parsed)))))))
