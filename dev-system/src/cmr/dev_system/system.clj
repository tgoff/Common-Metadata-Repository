(ns cmr.dev-system.system
  (:require
   [cmr.access-control.config :as access-control-config]
   [cmr.access-control.system :as access-control-system]
   [cmr.bootstrap.system :as bootstrap-system]
   [cmr.common-app.services.search.elastic-search-index :as es-search]
   [cmr.common.config :as config :refer [defconfig]]
   [cmr.common.dev.gorilla-repl :as gorilla-repl]
   [cmr.common.jobs :as jobs]
   [cmr.common.lifecycle :as lifecycle]
   [cmr.common.log :refer [debug info warn error]]
   [cmr.common.util :as u]
   [cmr.cubby.system :as cubby-system]
   [cmr.dev-system.control :as control]
   [cmr.elastic-utils.config :as elastic-config]
   [cmr.elastic-utils.embedded-elastic-server :as elastic-server]
   [cmr.elastic-utils.test-util :as elastic-test-util]
   [cmr.index-set.data.elasticsearch :as es-index]
   [cmr.index-set.system :as index-set-system]
   [cmr.indexer.config :as indexer-config]
   [cmr.indexer.system :as indexer-system]
   [cmr.ingest.data.memory-db :as ingest-data]
   [cmr.ingest.system :as ingest-system]
   [cmr.message-queue.config :as rmq-conf]
   [cmr.message-queue.queue.memory-queue :as mem-queue]
   [cmr.message-queue.queue.rabbit-mq :as rmq]
   [cmr.message-queue.queue.sqs :as sqs]
   [cmr.message-queue.services.queue :as queue]
   [cmr.message-queue.test.queue-broker-wrapper :as wrapper]
   [cmr.metadata-db.data.memory-db :as memory]
   [cmr.metadata-db.system :as mdb-system]
   [cmr.mock-echo.system :as mock-echo-system]
   [cmr.search.system :as search-system]
   [cmr.transmit.config :as transmit-config]
   [cmr.virtual-product.config :as vp-config]
   [cmr.virtual-product.system :as vp-system]))

(def external-echo-port 10000)

(defn external-echo-system-token
  "Returns the ECHO system token based on the value for ECHO_SYSTEM_READ_TOKEN in the ECHO
  configuration file. The WORKSPACE_HOME environment variable must be set in order to find the
  file. Returns mock-echo-system-token if it cannot extract the value."
  []
  (try
    (let [token (->> (str (or (System/getenv "WORKSPACE_HOME")
                              "../../")
                          "/deployment/primary/config.properties")
                     slurp
                     (re-find #"\n@ECHO_SYSTEM_READ_TOKEN@=(.*)\n")
                     peek)]
      (info "Using system token" token)
      token)
    (catch Exception e
      (warn "Unable to extract the ECHO system read token from configuration.")
      transmit-config/mock-echo-system-token)))

(def app-control-functions
  "A map of application name to the start function"
  {:mock-echo {:start mock-echo-system/start
               :stop mock-echo-system/stop}
   :metadata-db {:start mdb-system/start
                 :stop mdb-system/stop}
   :index-set {:start index-set-system/start
               :stop index-set-system/stop}
   :indexer {:start indexer-system/dev-start
             :stop indexer-system/stop}
   :ingest {:start ingest-system/start
            :stop ingest-system/stop}
   :search {:start search-system/start
            :stop search-system/stop}
   :bootstrap {:start bootstrap-system/start
               :stop bootstrap-system/stop}
   :access-control {:start access-control-system/dev-start
                    :stop access-control-system/stop}
   :cubby {:start cubby-system/dev-start
           :stop cubby-system/stop}
   :virtual-product {:start vp-system/start
                     :stop vp-system/stop}})

(def app-startup-order
  "Defines the order in which applications should be started"
  [:mock-echo :cubby :metadata-db :access-control :index-set :indexer :ingest :search :virtual-product :bootstrap])

(def use-compression?
  "Indicates whether the servers will use gzip compression. Disable this to make tcpmon usable"
  true)

(defconfig use-access-log
  "Indicates whether the servers will use the access log."
 {:type Boolean
  :default false})

(defn- set-web-server-options
  "Modifies the app server instances to configure web server options. Takes the system
  and returns it with the updates made. Should be run a system before it is started"
  [system]
  (update-in system [:apps]
             (fn [app-map]
               (into {} (for [[app-name app-system] app-map]
                          [app-name (-> app-system
                                        (assoc-in [:web :use-compression?] use-compression?)
                                        (assoc-in [:web :use-access-log?] (use-access-log)))])))))


(defmulti create-elastic
  "Sets elastic configuration values and returns an instance of an Elasticsearch component to run
  in memory if applicable."
  (fn [type]
    type))

(defmethod create-elastic :in-memory
  [_]
  (let [http-port (elastic-config/elastic-port)]
    (elastic-server/create-server http-port
                                  (+ http-port 10)
                                  "es_data/dev_system")))

(defmethod create-elastic :external
  [_]
  (elastic-config/set-elastic-port! 9209))

(defmulti create-db
  "Returns an instance of the database component to use."
  (fn [type]
    type))

(defmethod create-db :in-memory
  [type]
  (memory/create-db))

(defmethod create-db :external
  [type]
  nil)

(defmulti create-echo
  "Sets ECHO configuration values and returns an instance of a mock ECHO component to run in
  memory if applicable."
  (fn [type]
    type))

(defmethod create-echo :in-memory
  [type]
  (mock-echo-system/create-system))

(defmethod create-echo :external
  [type]
  (transmit-config/set-echo-rest-port! external-echo-port)
  (transmit-config/set-echo-system-token! (external-echo-system-token))
  (transmit-config/set-echo-rest-context! "/echo-rest"))

(defmulti create-queue-broker
  "Sets message queue configuration values and returns an instance of the message queue broker
  if applicable."
  (fn [type]
    type))

(defmethod create-queue-broker :in-memory
  [type]
  (-> (indexer-config/queue-config)
      (rmq-conf/merge-configs (vp-config/queue-config))
      (rmq-conf/merge-configs (access-control-config/queue-config))
      mem-queue/create-memory-queue-broker
      wrapper/create-queue-broker-wrapper))

(defn- external-queue-config
  "Create a configuration for an external queue (Rabbit MQ or AWS)."
  [ttls]
  (-> (indexer-config/queue-config)
      (rmq-conf/merge-configs (vp-config/queue-config))
      (rmq-conf/merge-configs (access-control-config/queue-config))
      (assoc :ttls ttls)))

;; for legacy reasons :external refers to Rabbit MQ
(defmethod create-queue-broker :external
  [type]
  ;; set the time-to-live on the retry queues to 1 second so our retry tests won't take too long
  (let [ttls [1 1 1 1 1]]
    (rmq-conf/set-rabbit-mq-ttls! ttls)
    (-> (external-queue-config ttls)
        rmq/create-queue-broker
        wrapper/create-queue-broker-wrapper)))

(defmethod create-queue-broker :aws
  [type]
  (-> (external-queue-config [])
      sqs/create-queue-broker
      wrapper/create-queue-broker-wrapper))

(defn create-metadata-db-app
  "Create an instance of the metadata-db application."
  [db-component queue-broker]
  (let [sys-with-db (if db-component
                      (-> (mdb-system/create-system)
                          (assoc :db db-component
                                 :scheduler (jobs/create-non-running-scheduler)))
                      (mdb-system/create-system))]
    (assoc sys-with-db :queue-broker queue-broker)))

(defn create-indexer-app
  "Create an instance of the indexer application."
  [queue-broker]
  (assoc (indexer-system/create-system) :queue-broker queue-broker))

(defn create-virtual-product-app
  "Create an instance of the virtual product application."
  [queue-broker]
  (assoc (vp-system/create-system) :queue-broker queue-broker))

(defn create-access-control-app
  "Create an instance of the access control application."
  [queue-broker]
  (assoc (access-control-system/create-system) :queue-broker queue-broker))

(defmulti create-ingest-app
  "Create an instance of the ingest application."
  (fn [db-type queue-broker]
    db-type))

(defmethod create-ingest-app :in-memory
  [db-type queue-broker]
  (-> (ingest-system/create-system)
      (assoc :db (ingest-data/create-in-memory-db)
             :queue-broker queue-broker
             :scheduler (jobs/create-non-running-scheduler))))

(defmethod create-ingest-app :external
  [db-type queue-broker]
  (-> (ingest-system/create-system)
      (assoc :queue-broker queue-broker)))

(defn create-search-app
  "Create an instance of the search application."
  [db-component queue-broker]
  (let [search-app (if db-component
                     (assoc-in (search-system/create-system)
                               [:embedded-systems :metadata-db :db]
                               db-component)
                     (search-system/create-system))]
    (assoc-in search-app
              [:embedded-systems :metadata-db :queue-broker]
              queue-broker)))

(defn- base-parse-dev-system-component-type
  "Parse the component type and validate it against the given set."
  [value valid-types-set]
  (when-not (valid-types-set value)
    (throw (Exception. (str "Unexpected component type value:" value))))
  (keyword value))

(defn parse-dev-system-component-type
  "Parse the component type and validate it is either in-memory or external."
  [value]
  (base-parse-dev-system-component-type value #{"in-memory" "external"}))

(defn parse-dev-system-message-queue-type
  "Parse the component type and validate it one of the valid queue types."
  [value]
  (base-parse-dev-system-component-type value #{"in-memory" "aws" "external"}))

(defconfig dev-system-echo-type
  "Specifies whether dev system should run an in-memory mock ECHO or use an external ECHO."
  {:default :in-memory
   :parser parse-dev-system-component-type})

(defconfig dev-system-db-type
  "Specifies whether dev system should run an in-memory database or use an external database."
  {:default :in-memory
   :parser parse-dev-system-component-type})

(defconfig dev-system-message-queue-type
  "Specifies whether dev system should skip the use of a message queue or use a Rabbit MQ or
  AWS SNS/SQS message queue"
  {:default :in-memory
   :parser parse-dev-system-message-queue-type})

(defconfig dev-system-elastic-type
  "Specifies whether dev system should run an in-memory elasticsearch or use an external instance."
  {:default :in-memory
   :parser parse-dev-system-component-type})

(defconfig gorilla-repl-port
  "Specifies the port gorilla repl should listen on. It will only be started if non-zero."
  {:default 0
   :type Long})

(defn component-type-map
  "Returns a map of dev system components options to run in memory or externally."
  []
  {:elastic (dev-system-elastic-type)
   :echo (dev-system-echo-type)
   :db (dev-system-db-type)
   :message-queue (dev-system-message-queue-type)})

(defn create-system
  "Returns a new instance of the whole application."
  []
  (let [{:keys [elastic echo db message-queue]} (component-type-map)
        db-component (create-db db)
        echo-component (create-echo echo)
        queue-broker (create-queue-broker message-queue)
        elastic-server (create-elastic elastic)
        control-server (control/create-server)]
    {:apps (u/remove-nil-keys
             {:mock-echo echo-component
              :access-control (create-access-control-app queue-broker)
              :cubby (cubby-system/create-system)
              :metadata-db (create-metadata-db-app db-component queue-broker)
              :bootstrap (when-not db-component (bootstrap-system/create-system))
              :indexer (create-indexer-app queue-broker)
              :index-set (index-set-system/create-system)
              :ingest (create-ingest-app db queue-broker)
              :search (create-search-app db-component queue-broker)
              :virtual-product (create-virtual-product-app queue-broker)})
     :pre-components (u/remove-nil-keys
                       {:elastic-server elastic-server
                        :broker-wrapper queue-broker})
     :post-components {:control-server control-server
                       :gorilla-repl (when-not (zero? (gorilla-repl-port))
                                       (gorilla-repl/create-gorilla-repl-server (gorilla-repl-port)))}}))

(defn- stop-components
  [system components-key]
  (reduce (fn [system component]
            (update-in system [components-key component]
                       #(when % (lifecycle/stop % system))))
          system
          (keys (components-key system))))

(defn- stop-apps
  [system]
  (reduce (fn [system app]
            (let [{stop-fn :stop} (app-control-functions app)]
              (update-in system [:apps app] #(when % (stop-fn %)))))
          system
          (reverse app-startup-order)))

(defn- start-components
  [system components-key]
  (reduce (fn [system component]
            (update-in system [components-key component]
                       #(try
                          (when % (lifecycle/start % system))
                          (catch Exception e
                            (error e "Failure during startup")
                            (stop-components (stop-apps system) :pre-components)
                            (stop-components (stop-apps system) :post-components)
                            (throw e)))))
          system
          (keys (components-key system))))

(defn- start-apps
  [system]
  (let [system (set-web-server-options system)]
    (reduce (fn [system app]
              (let [{start-fn :start} (app-control-functions app)]
                (update-in system [:apps app]
                           #(try
                              (when %
                                (start-fn %))
                              (catch Exception e
                                (error e (format "Failure of %s app during startup" app))
                                (stop-components (stop-apps system) :pre-components)
                                (stop-components (stop-apps system) :post-components)
                                (throw e))))))
            system
            app-startup-order)))

(defn start
  "Performs side effects to initialize the system, acquire resources,
  and start it running. Returns an updated instance of the system."
  [this]
  (info "System starting")

  (-> this
      (start-components :pre-components)
      start-apps
      (start-components :post-components)))

(defn stop
  "Performs side effects to shut down the system and release its
  resources. Returns an updated instance of the system."
  [this]
  (info "System shutting down")

  (-> this
      (stop-components :post-components)
      stop-apps
      (stop-components :pre-components)))
