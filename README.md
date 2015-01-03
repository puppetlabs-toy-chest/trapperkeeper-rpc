# trapperkeeper-rpc

(TODO clojars)

This library enables transparent RPC implementation for TK
services. It provides two things:

 * A `call-remote-svc-fn` function used to implement a proxied version
   of a given TK service protocol
 * An RPC server service
   (`puppetlabs.trapperkeeper.rpc.services.rpc-server-service`) that
   exposes proxied service implementations over HTTP.

## Feature Overview

 * Choice of JSON or Transit+msgpack (the latter being the default) for serialization
 * Optional Per-service certificate whitelisting
 * HTTP or HTTPS
 * Per-service endpoints
 * Robust error reporting

Planned features:

 * basic API for adding custom (de|en)coders
 * support for ignoring (defn-) defined functions

## Example usage

```clojure
(ns gnarlytimes.services
    (:require [puppetlabs.trapperkeeper.core :refer [defservice]]
              [puppetlabs.trapperkeeper.rpc.core :refer [call-remote-svc-fn]]))

;; (note: TK best practices are eschewed for the sake of brevity)

;; A service protocol
(defprotocol MathService
    (add [this x y])
    (divide [this x y]))

;; The traditional (or "concrete") implementation of the MathService protocol
(defservice math-service
    MathService
    []
    (add [this x y] (+ x y))
    (divide [this x y] (/ x y)))

;; A proxied implementation of MathService for use via RPC
(defservice remote-math-service
    MathService
    [[:ConfigService get-in-config]]
    (add [this x y] (call-remote-svc-fn (get-in-config [:rpc]) :MathService :add x y))
    (divide [this x y] (call-remote-svc-fn (get-in-config [:rpc]) :MathService :divide x y)))
```

Given the following config:

```clojure
  {:rpc {;; currently supported: :transit (using msgpack) and :json
         :wire-format :transit

         ;; settings for making signed requests to the rpc server
         :ssl {:client-cert "dev-resources/ssl/client-cert.pem"
               :client-key "dev-resources/ssl/client-key.pem"
               :client-ca "dev-resources/ssl/ca.pem"}


         ;; This maps service IDs to RPC settings used by both the RPC
         ;; client and server components.
         :services {:MathService
                    {;; used by the RPC server service to find service functions
                     :protocol-ns "gnarlytimes.services"
                     ;; each service's RPC endpoint can have its own cert whitelist
                     :certificate-whitelist "dev-resources/ssl/math-service-cert-whitelist"
                     ;; the client uses this to issue RPC calls
                     :endpoint "https://localhost:9002/rpc/call"}}}

   ;; a webserver that will listen for RPC calls.
   :webserver {:rpc {:ssl-host "0.0.0.0"
                     :ssl-port 9002
                     :ssl-key "dev-resources/ssl/key.pem"
                     :ssl-cert "dev-resources/ssl/cert.pem"
                     :ssl-ca-cert "dev-resources/ssl/ca.pem"})}
```

and two TK stacks, one with a bootstrap.cfg like this:

```
puppetlabs.trapperkeeper.rpc/rpc-server-service
gnarlytimes.services/math-service
```

and another like this:

```
gnarlytimes.services/remote-math-service
```

The latter can issue calls to the functions defined by `MathService`
as if the service was defined locally.

## Error handling

TODO

## Running the tests

`lein test`

## Author

Nathaniel Smith <nathaniel@puppetlabs.com>

## License

Copyright © 2015 Puppet Labs

Distributed under the [Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.html)
