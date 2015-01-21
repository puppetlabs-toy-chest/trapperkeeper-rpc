(ns puppetlabs.trapperkeeper.rpc.wire
  (:require [clojure.java.io :as io]
            [cognitect.transit :as transit]
            [ring.util.response :refer [response header]])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream InputStream]))

(defn input-stream-to-byte-array
  [^InputStream stream]
  (let [byte-vector (loop [bv (vector-of :int)]
                      (let [next-byte (.read stream)]
                        (if (= -1 next-byte)
                          bv
                          (recur (conj bv next-byte)))))]
    (byte-array byte-vector)))

(defprotocol RPCSerialization
  (encode [this data])
  (decode [this data]))

(defprotocol RPCHTTPHelper
  (build-response [this data])
  (parse-request [this request]))

(defn do-encode [data & [wire-format]]
  (let [wire-format (or wire-format :msgpack)]
      (let [out (ByteArrayOutputStream.) ;; grow as needed
            writer (transit/writer out wire-format)]
        (transit/write writer data)
        (ByteArrayInputStream. (.toByteArray out)))))

(defn do-decode [data & [wire-format]]
  (let [wire-format (or wire-format :msgpack)
        in (ByteArrayInputStream. (if (instance? java.lang.String data)
                                    (.getBytes ^String data)
                                    (input-stream-to-byte-array data)))
        reader (transit/reader in wire-format)]
    (transit/read reader)))

(def msgpack-serializer
  (reify RPCSerialization
    (encode [this data] (do-encode data :msgpack))
    (decode [this data] (do-decode data :msgpack))))

(def json-serializer
  (reify RPCSerialization
    (encode [this data] (do-encode data :json))
    (decode [this data] (do-decode data :json))))

(defn do-build-response [data & [wire-format]]
  (let [wire-format (or wire-format :msgpack)]
    (-> (do-encode data wire-format)
        response
        (header "content-type" "application/octet-stream"))))

(defn do-parse-request [request & [wire-format]]
  (let [wire-format (or wire-format :msgpack)
        parsed (do-decode (:body request) wire-format)]
    (-> parsed
        (assoc :svc-id (keyword (:svc-id parsed)))
        (assoc :fn-name (name (:fn-name parsed))))))

(def msgpack-http-helper
  (reify RPCHTTPHelper
    (build-response [this data]
      (do-build-response data :msgpack))
    (parse-request [this request]
      (do-parse-request request :msgpack))))

(def json-http-helper
  (reify RPCHTTPHelper
    (build-response [this data]
      (do-build-response data :json))
    (parse-request [this request]
      (do-parse-request request :json))))
