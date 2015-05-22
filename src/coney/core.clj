(ns coney.core
  (:gen-class)
  (:require [clojure.edn :as edn]
            [org.httpkit.client :as http]
            [cheshire.core :as cheshire]
            [coney.rabbit-password :as rp]
            [clojure.tools.cli :refer [parse-opts]]
            [clojure.string :refer [join]])
)

(def root (atom "http://localhost:15672/api/"))

(defn get-key-from-hash [key hash]
  (hash-map (keyword (key hash)) hash)
)

(defn get-name-from-hash [hash]
  (get-key-from-hash :name hash)
)

(defn get-user-from-hash [hash]
  (get-key-from-hash :user hash)
)

(def core-params {:basic-auth ["guest" "guest"] :headers {"content-type" "application/json"}})

(defn expected-code [resp code]
  (if (not= (:status resp) code)
    (do
      (println (format "Wanted HTTP code %d but got %d" code (:status resp)))
      (println resp)
      (throw (RuntimeException. (str resp)))
    )
  )
)

(defn- get-x-from-api [get-func api-key]
   (apply merge (map get-func (-> @(http/get (str @root api-key) core-params) :body (cheshire/decode true))))
)

(defn get-names-from-api [api-key]
  (get-x-from-api get-name-from-hash api-key)
)

(defn get-users-from-api [api-key]
  (get-x-from-api get-user-from-hash api-key)
)

(defn get-names-from-hash [key config]
  (apply merge (map get-name-from-hash (key config)))
)

(defn get-users-from-hash [key config]
  (apply merge (map get-user-from-hash (key config)))
)

(defn sync-config [kind existing wanted vhost sync-keys]
	(let [vhost-encoded (http/url-encode (name vhost))]
		(doseq [item (keys wanted)
				:let [wanted-keys (select-keys (item wanted) sync-keys)]]
			(if (or (not (contains? existing item)) (not= wanted-keys (select-keys (item existing) sync-keys)))
				(do
					(println (format "missing/wrong %s for '%s' on '%s'" kind (name item) vhost))
					(expected-code @(http/put
								   (str @root kind "/" vhost-encoded "/" (name item))
								   (merge core-params {:body (cheshire/encode wanted-keys)}))
								 204)
				)
			)
		)
	)
)

(defn sync-bindings [kind existing wanted vhost sync-keys]
	(let [vhost-encoded (http/url-encode (name vhost))]
		(doseq [item (keys wanted)
				:let [wanted-item (item wanted)
              wanted-keys (select-keys wanted-item sync-keys)]]
			(if (or (not (contains? existing item)) (not= wanted-keys (select-keys (item existing) sync-keys)))
				(do
					(println (format "missing/wrong bindings for '%s'" (name item)))
					(expected-code @(http/post
								   (str @root "bindings/" vhost-encoded "/e/" (:source wanted-item) "/q/" (:destination wanted-item))
								   (merge core-params {:body (cheshire/encode wanted-keys)}))
								 201)
				)
			)
		)
	)
)

(defn get-bindings-from-hash [hash]
  (hash-map (keyword (join "-" (map #(% hash) [:destination :destination_type :arguments :routing_key :source]))) hash)
)

(def cli-options [
                  ["-h" "--help"]
                  [nil "--host HOST" :default "localhost"]
                  ["-f" "--filetype FILETYPE" :default :edn :parse-fn #(keyword %)]
                  ])

(defn exit [status msg]
  (println msg)
  (System/exit status))

(defn error-msg [errors]
  (str "The following errors occurred while parsing your command:\n\n"
       (join \newline errors)))

(defn usage [options-summary]
  (str "Usage: \n" options-summary)
)

(defn file-exists [path]
  (.exists (clojure.java.io/as-file path))
)

(defn parse-file [filetype data]
  (case filetype
    :edn (edn/read-string data)
    :json (cheshire/parse-string data true)
    (throw (RuntimeException. (format "Don't know file format '%s'" (name filetype))))
  )
)

(defn has-key [coll key]
  (and (not (nil? (keys coll))) (.contains (keys coll) key))
)

(defn sync-config-multiple-vhost [kind existing-for-vhost wanted sync-keys]
  (sync-config kind (existing-for-vhost (http/url-encode "/")) wanted "/" sync-keys)
)

(defn -main
  [& args]
  (let [{:keys [options arguments errors summary]} (parse-opts args cli-options)]
      (reset! root (str "http://" (:host options) ":15672/api/"))
      (cond
        (:help options) (exit 0 (usage summary))
        errors (exit 1 (error-msg errors))
        (not= (count arguments) 1) (exit 1 (format "Need a single file argument, but got %d arguments" (count arguments)))
        (not (file-exists (first arguments))) (exit 1 (error-msg [(format "No such file '%s'" (first arguments))]))
        :default (let [config (->> arguments first slurp (parse-file (:filetype options)))
          existing-users (get-names-from-api "users")
          wanted-users (get-names-from-hash :users config)
          existing-vhosts (map #(keyword (:name %)) (-> @(http/get (str @root "vhosts") core-params) :body (cheshire/decode true)))
          wanted-vhosts (get-names-from-hash :vhosts config)
          ]
          (doseq [user (keys wanted-users)]
            (if (or (not (contains? existing-users user)) (not (rp/check-rabbit-password (:password (user wanted-users)) (:password_hash (user existing-users)))))
              (do
                (println (format "missing user '%s'" (name user)))
                (expected-code @(http/put
                                 (str @root "users/" (name user))
                                 (merge core-params {:body (cheshire/encode {:tags "" :password (:password (user wanted-users))})}))
                               204)
                )
              )
          )
          (doseq [vhost (keys wanted-vhosts)]
            (if (not (.contains existing-vhosts vhost))
              (do
                (println (format "missing vhost '%s'" (name vhost)))
                (expected-code @(http/put
                                 (str @root "vhosts/" (name vhost))
                                 core-params)
                               204)
                )
            )
            (let [vhost-encoded (http/url-encode (name vhost))
                  vhost-config (vhost wanted-vhosts)
                  ]
              (sync-config "permissions"
                           (get-users-from-api "permissions")
                           (get-users-from-hash :permissions vhost-config) vhost
                           [:configure :write :read])
              (sync-config "queues"
                           (get-names-from-api (str "queues/" vhost-encoded))
                           (get-names-from-hash :queues vhost-config) vhost
                           [:arguments :durable :auto-delete])
              (sync-config "exchanges"
                           (get-names-from-api (str "exchanges/" vhost-encoded))
                           (get-names-from-hash :exchanges vhost-config) vhost
                           [:arguments :internal :type :auto_delete :durable])
              (sync-bindings "bindings"
                           (get-x-from-api get-bindings-from-hash "bindings")
                           (apply merge (map get-bindings-from-hash (:bindings vhost-config)))
                           vhost
                           [:destination :destination_type :arguments :routing_key :source]
              )
            )
          )
          (if (has-key config :permissions)
              (sync-config-multiple-vhost "permissions"
                           (get-users-from-api "permissions")
                           (get-users-from-hash :permissions config)
                           [:configure :write :read])
          )
          (if (has-key config :queues)
              (sync-config-multiple-vhost "queues"
                           #(get-names-from-api (str "queues/" %))
                           (get-names-from-hash :queues config)
                           [:arguments :durable :auto-delete])
          )
          (if (has-key config :exchanges)
            (sync-config-multiple-vhost "exchanges"
                         #(get-names-from-api (str "exchanges/" %))
                         (get-names-from-hash :exchanges config)
                         [:arguments :internal :type :auto_delete :durable])
          )
          (if (has-key config :bindings)
              (sync-bindings "bindings"
                           (get-x-from-api get-bindings-from-hash "bindings")
                           (apply merge (map get-bindings-from-hash (:bindings config)))
                           "/"
                           [:destination :destination_type :arguments :routing_key :source]
              )
          )
          (println "All done")
        )
    )
  )
)
