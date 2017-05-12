(ns cmr.bootstrap.services.bootstrap-service
  "Provides methods to insert migration requets on the approriate channels."
  (:require
    [clojure.core.async :as async :refer [go >!]]
    [cmr.bootstrap.config :as cfg]
    [cmr.bootstrap.data.bulk-index :as bulk]
    [cmr.bootstrap.data.bulk-migration :as bm]
    [cmr.bootstrap.data.rebalance-util :as rebalance-util]
    [cmr.bootstrap.data.virtual-products :as vp]
    [cmr.common.cache :as cache]
    [cmr.common.concepts :as concepts]
    [cmr.common.log :refer (debug info warn error)]
    [cmr.common.services.errors :as errors]
    [cmr.indexer.data.index-set :as indexer-index-set]
    [cmr.indexer.system :as indexer-system]
    [cmr.transmit.index-set :as index-set]))


(defn migrate-provider
  "Copy all the data for a provider (including collections and graunules) from catalog rest
  to the metadata db without blocking."
  [context provider-id synchronous]
  (if synchronous
    (bm/copy-provider (:system context) provider-id)
    (let [channel (get-in context [:system :provider-db-channel])]
      (info "Adding provider" provider-id "to provider channel")
      (go (>! channel provider-id)))))

(defn migrate-collection
  "Copy all the data for a given collection (including graunules) from catalog rest
  to the metadata db without blocking."
  [context provider-id collection-id synchronous]
  (if synchronous
    (bm/copy-single-collection (:system context) provider-id collection-id)
    (let [channel (get-in context [:system :collection-db-channel])]
      (info "Adding collection"  collection-id "for provider" provider-id "to collection channel")
      (go (>! channel {:collection-id collection-id :provider-id provider-id})))))

(defn- get-provider
  "Returns the metadata db provider that matches the given provider id. Throws exception if
  no matching provider is found."
  [context provider-id]
  (if-let [provider (bulk/get-provider-by-id context provider-id)]
    provider
    (errors/throw-service-errors :bad-request
                              [(format "Provider: [%s] does not exist in the system" provider-id)])))

(defn validate-collection
  "Validates to be bulk_indexed collection exists in cmr else an exception is thrown."
  [context provider-id collection-id]
  (let [provider (get-provider context provider-id)]
    (when-not (bulk/get-collection context provider collection-id)
      (errors/throw-service-errors :bad-request
                                [(format "Collection [%s] does not exist." collection-id)]))))

(defn index-provider
  "Bulk index all the collections and granules for a provider."
  [context provider-id synchronous start-index]
  (get-provider context provider-id)
  (if synchronous
    (bulk/index-provider (:system context) provider-id start-index)
    (let [channel (get-in context [:system :provider-index-channel])]
      (info "Adding provider" provider-id "to provider index channel")
      (go (>! channel {:provider-id provider-id
                       :start-index start-index})))))

(defn index-data-later-than-date-time
  "Bulk index all the concepts with a revision date later than the given date-time."
  [context date-time synchronous]
  (if synchronous
    (bulk/index-data-later-than-date-time (:system context) date-time)
    (let [channel (get-in context [:system :data-index-channel])]
      (info "Adding date-time" date-time "to data index channel.")
      (go (>! channel {:date-time date-time})))))

(defn index-collection
  "Bulk index all the granules in a collection"
  ([context provider-id collection-id synchronous]
   (index-collection context provider-id collection-id synchronous nil))
  ([context provider-id collection-id synchronous options]
   (validate-collection context provider-id collection-id)
   (if synchronous
     (bulk/index-granules-for-collection (:system context) provider-id collection-id options)
     (let [channel (get-in context [:system :collection-index-channel])]
       (info "Adding collection" collection-id "to collection index channel")
       (go (>! channel (merge options
                              {:provider-id provider-id
                               :collection-id collection-id})))))))

(defn index-system-concepts
  "Bulk index all the tags, acls, and access-groups."
  [context synchronous start-index]
  (if synchronous
    (bulk/index-system-concepts (:system context) start-index)
    (let [channel (get-in context [:system :system-concept-channel])]
      (info "Adding bulk index request to system concepts channel.")
      (go (>! channel {:start-index start-index})))))

(defn index-concepts-by-id
  "Bulk index the concepts given by the concept-ids"
  [context synchronous provider-id concept-type concept-ids]
  (if synchronous
    (bulk/index-concepts-by-id (:system context) provider-id concept-type concept-ids)
    (let [channel (get-in context [:system :concept-id-channel])]
      (info "Adding bulk index request to concept-id channel.")
      (go (>! channel {:provider-id provider-id 
                       :concept-type concept-type 
                       :request :index 
                       :concept-ids concept-ids})))))

(defn delete-concepts-from-index-by-id
  "Bulk delete the concepts given by the concept-ids from the indexes"
  [context synchronous provider-id concept-type concept-ids]
  (if synchronous
    (bulk/delete-concepts-by-id (:system context) provider-id concept-type concept-ids)
    (let [channel (get-in context [:system :concept-id-channel])]
      (info "Adding bulk delete reqeust to concept-id channel.")
      (go (>! channel {:provider-id provider-id 
                       :concept-type concept-type
                       :request :delete
                       :concept-ids concept-ids})))))

(defn bootstrap-virtual-products
  "Initializes virtual products."
  [context synchronous provider-id entry-title]
  (if synchronous
    (vp/bootstrap-virtual-products (:system context) provider-id entry-title)
    (go
      (info "Adding message to virtual products channel.")
      (-> context :system (get vp/channel-name) (>! {:provider-id provider-id
                                                     :entry-title entry-title})))))

(defn- wait-until-index-set-hash-cache-times-out
  "Waits until the indexer's index set cache hash codes times out so that all of the indexer's will
   be using the same cached data."
  []
  ;; Wait 3 seconds beyond the time that the indexer set cache consistency setting.
  (let [sleep-secs (+ 3 (indexer-system/index-set-cache-consistent-timeout-seconds))]
    (info "Waiting" sleep-secs "seconds so indexer index set hashes will timeout.")
    (Thread/sleep (* 1000 sleep-secs))))

(defn start-rebalance-collection
  "Kicks off collection rebalancing. Will run synchronously if synchronous is true. Throws exceptions
  from failures to change the index set."
  [context concept-id synchronous]
  (validate-collection context (:provider-id (concepts/parse-concept-id concept-id)) concept-id)
  ;; This will throw an exception if the collection is already rebalancing
  (index-set/add-rebalancing-collection context indexer-index-set/index-set-id concept-id)

  ;; Clear the cache so that the newest index set data will be used.
  ;; This clears embedded caches so the indexer cache in this bootstrap app will be cleared.
  (cache/reset-caches context)

  ;; We must wait here so that any new granules coming in will start to pick up the new index set
  ;; and be indexed into both the old and the new. Then we can safely reindex everything and know
  ;; we haven't missed a granule. There would be a race condition otherwise where a new granule
  ;; came in and was indexed only to the old collection but after we started reindexing the collection.
  (wait-until-index-set-hash-cache-times-out)

  (let [provider-id (:provider-id (concepts/parse-concept-id concept-id))]
    ;; queue the collection for reindexing into the new index
    (index-collection
     context provider-id concept-id synchronous
     {:target-index-key (keyword concept-id)
      :completion-message (format "Completed reindex of [%s] for rebalancing granule indexes." concept-id)})))

(defn finalize-rebalance-collection
  "Finalizes collection rebalancing."
  [context concept-id]
  (validate-collection context (:provider-id (concepts/parse-concept-id concept-id)) concept-id)
  ;; This will throw an exception if the collection is not rebalancing
  (index-set/finalize-rebalancing-collection context indexer-index-set/index-set-id concept-id)
  ;; Clear the cache so that the newest index set data will be used.
  ;; This clears embedded caches so the indexer cache in this bootstrap app will be cleared.
  (cache/reset-caches context)

  ;; There is a race condition as noted here: https://wiki.earthdata.nasa.gov/display/CMR/Rebalancing+Collection+Indexes+Approach
  ;; "There's a period of time during which the different indexer applications may be processing
  ;; granules for this very collection and may have already decided which index its going to. It's
  ;; possible that the indexer will index a granule into small collections after the bootstrap has
  ;; issued the delete. The next step to verify should identify if the race conditions has occurred. "
  ;; The sleep here decreases the probability of the race condition giving time for
  ;; indexer to finish indexing any granule currently being processed.
  ;; This doesn't remove the race condition. We still have steps in the overall process to detect it
  ;; and resolve it. (manual fixes if necessary)
  (wait-until-index-set-hash-cache-times-out)

  ;; Remove all granules from small collections for this collection.
  (rebalance-util/delete-collection-granules-from-small-collections context concept-id))



(defn rebalance-status
  "Returns a map of counts of granules in the collection in metadata db, the small collections index,
   and in the separate collection index if it exists."
  [context concept-id]
  (rebalance-util/rebalancing-collection-counts context concept-id))
