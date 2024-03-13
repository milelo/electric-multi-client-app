(ns server.server-jetty
  "Electric integrated into a sample ring + jetty app."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.pprint :refer [pprint]]
   [clojure.tools.logging :as log]
   [contrib.assert :refer [check]]
   [hyperfiddle.electric-ring-adapter :as electric-ring]
   [ring.adapter.jetty :refer [run-jetty]]
   [ring.middleware.content-type :refer [wrap-content-type]]
   [ring.middleware.cookies :as cookies]
   [ring.middleware.params :refer [wrap-params]]
   [ring.middleware.resource :refer [wrap-resource]]
   [ring.util.response :as res]
   [reitit.ring :as ring]
   [lib.debug :refer [we wd]])
  (:import
   (org.eclipse.jetty.server.handler.gzip GzipHandler)
   (org.eclipse.jetty.websocket.server.config JettyWebSocketServletContainerInitializer JettyWebSocketServletContainerInitializer$Configurator)))

;;; Electric integration

(defn wrap-dbg [handler label]
  (fn [request]
    (let [response (handler request)]
      (prn)
      (pprint {:label label :request request :response response})
      response)))

(defn wrap-dbg-uri [handler]
  (fn [request]
    (prn :URI (:uri request))
    (handler request)))

(defn electric-websocket-middleware
  "Open a websocket and boot an Electric server program defined by `entrypoint`.
  Takes:
  - a ring handler `next-handler` to call if the request is not a websocket upgrade (e.g. the next middleware in the chain),
  - a `config` map eventually containing {:hyperfiddle.electric/user-version <version>} to ensure client and server share the same version,
    - see `hyperfiddle.electric-ring-adapter/wrap-reject-stale-client`
  - an Electric `entrypoint`: a function (fn [ring-request] (e/boot-server {} my-ns/My-e-defn ring-request))
  "
  [next-handler config]
  ;; Applied bottom-up
  (-> next-handler
      ;(wrap-dbg :electric-websocket-middleware-4)
      (electric-ring/wrap-electric-websocket (:on-boot-server config)) ; 5. connect electric client
    ; 4. this is where you would add authentication middleware (after cookie parsing, before Electric starts)
      ;(wrap-dbg :electric-websocket-middleware-3)
      (cookies/wrap-cookies) ; 3. makes cookies available to Electric app
      (electric-ring/wrap-reject-stale-client config) ; 2. reject stale electric client
      ;(wrap-dbg :electric-websocket-middleware-2)
      (wrap-params) ; 1. parse query params
      ;(wrap-dbg :electric-websocket-middleware-1);
      ;(wrap-dbg-uri);
      ))

(defn get-modules [{:keys [asset-path manifest-path]}]
  (when-let [manifest (io/resource manifest-path)]
    (->> (slurp manifest)
         (edn/read-string)
         (reduce (fn [r module] (assoc r (keyword "hyperfiddle.client.module" (name (:name module)))
                                       (str asset-path \/ (:output-name module)))) {}))))

(defn template
  "In string template `<div>$:foo/bar$</div>`, replace all instances of $key$
with target specified by map `m`. Target values are coerced to string with `str`.
  E.g. (template \"<div>$:foo$</div>\" {:foo 1}) => \"<div>1</div>\" - 1 is coerced to string."
  [t m] (reduce-kv (fn [acc k v] (str/replace acc (str "$" k "$") (str v))) t m))

;;; Template and serve index.html

(defn wrap-index-page
  "Server the `index.html` file with injected javascript modules from `manifest.edn`.
`manifest.edn` is generated by the client build and contains javascript modules
information."
  [next-handler config]
  (fn [ring-req]
    (if-let [response (we :wrap-index-page-response (res/resource-response (str (check string? (:resources-path config)) (:index-page config))))]
      (if-let [bag (we :wrap-index-page-bag (merge config (get-modules config)))]
        (-> (res/response (template (slurp (:body response)) bag)) ; TODO cache in prod mode
            (res/content-type "text/html") ; ensure `index.html` is not cached
            (res/header "Cache-Control" "no-store")
            (res/header "Last-Modified" (get-in response [:headers "Last-Modified"])))
        (-> (res/not-found (pr-str ::missing-shadow-build-manifest)) ; can't inject js modules
            (res/content-type "text/plain")))
      ;; index.html file not found on classpath
      (next-handler ring-req))))

(defn not-found-handler [_ring-request]
  (-> (res/not-found "Not found")
      (res/content-type "text/plain")))

(defn http-middleware [config]
  ;; these compose as functions, so are applied bottom up
  (-> not-found-handler
      (wrap-index-page config) ; 3. otherwise fallback to default page file
      (wrap-resource (:resources-path config)) ; 2. serve static file from classpath
      (wrap-content-type) ; 1. detect content (e.g. for index.html)
      ))

(defn middleware [config]
  (-> (http-middleware config)  ; 2. otherwise, serve regular http content
      (electric-websocket-middleware config))) ; 1. intercept websocket upgrades and maybe start Electric

(defn- add-gzip-handler!
  "Makes Jetty server compress responses. Optional but recommended."
  [server]
  (.setHandler server
               (doto (GzipHandler.)
                 #_(.setIncludedMimeTypes (into-array ["text/css" "text/plain" "text/javascript" "application/javascript" "application/json" "image/svg+xml"])) ; only compress these
                 (.setMinGzipSize 1024)
                 (.setHandler (.getHandler server)))))

(defn- configure-websocket!
  "Tune Jetty Websocket config for Electric compat." [server]
  (JettyWebSocketServletContainerInitializer/configure
   (.getHandler server)
   (reify JettyWebSocketServletContainerInitializer$Configurator
     (accept [_this _servletContext wsContainer]
       (.setIdleTimeout wsContainer (java.time.Duration/ofSeconds 60))
       (.setMaxBinaryMessageSize wsContainer (* 100 1024 1024)) ; 100M - temporary
       (.setMaxTextMessageSize wsContainer (* 100 1024 1024))   ; 100M - temporary
       ))))

(defn start-server! [{:keys [port host]
                      :or   {port 8080, host "localhost"}
                      :as server-config} app-config admin-config]
  (let [app-middleware (middleware app-config)
        admin-middleware (middleware admin-config)
        router (ring/router
                [["/" app-middleware]
                 ["/app/js/*path" app-middleware]
                 ["/admin" admin-middleware]
                 ["/admin/js/*path" app-middleware]])
        route-handler (ring/ring-handler
                       router
                       (fn [_request] (res/not-found nil)))

        server     (run-jetty
                    route-handler
                    (merge {:port         port
                            :join?        false
                            :configurator (fn [server]
                                            (configure-websocket! server)
                                            (add-gzip-handler! server))}
                           server-config))]
    (log/info "👉" (str "http://" host ":" (-> server (.getConnectors) first (.getPort))))
    (log/info "👉" (str "http://" host ":" (-> server (.getConnectors) first (.getPort)) "/admin"))
    server))
