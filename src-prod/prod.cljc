(ns prod
  (:require
   #?(:clj [clojure.edn :as edn])
   #?(:clj [clojure.java.io :as io])
   #?(:clj [clojure.tools.logging :as log])
   #?(:clj [contrib.assert :refer [check]])
   #?(:clj [server.server-jetty :as jetty])
   [hyperfiddle.electric :as e]
   app.main
   admin.main))

#?(:clj
   (do
     (defmacro compile-time-resource [filename] (some-> filename io/resource slurp edn/read-string))

     (def server-config
       (merge
    ;; Client program's version and server program's versions must match in prod (dev is not concerned)
    ;; `src-build/build.clj` will compute the common version and store it in `resources/public/electric-manifest.edn`
    ;; On prod boot, `electric-manifest.edn`'s content is injected here.
    ;; Server is therefore aware of the program version.
    ;; The client's version is injected in the compiled .js file.
        (doto (compile-time-resource "electric-manifest.edn") prn)
        {:host "localhost"
         :port 8080}))

     (defn -main [& {:strs [] :as args}] ; clojure.main entrypoint, args are strings
       (log/info (pr-str server-config))
       (check string? (::e/user-version server-config))
       (jetty/start-server!
        server-config
        app.main/config
        admin.main/config))))

#?(:cljs
   (do 
     (defn ^:export start-app! []
       (app.main/entrypoint
        #(js/console.log "reactor success:" %)
        #(js/console.error "reactor failure:" %)))

     (defn ^:export start-admin! []
       (admin.main/entrypoint
        #(js/console.log "reactor success:" %)
        #(js/console.error "reactor failure:" %)))))