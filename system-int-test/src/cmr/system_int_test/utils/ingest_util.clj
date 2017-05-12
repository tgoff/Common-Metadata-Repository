(ns cmr.system-int-test.utils.ingest-util
  (:require
   [clojure.test :refer :all]
   [clj-http.client :as client]
   [clojure.string :as str]
   [cheshire.core :as json]
   [clojure.data :as d]
   [clojure.data.xml :as x]
   [cmr.common.xml :as cx]
   [cmr.common.mime-types :as mt]
   [cmr.umm.echo10.echo10-collection :as c]
   [cmr.umm.echo10.granule :as g]
   [cmr.system-int-test.data2.provider-holdings :as ph]
   [cmr.umm.echo10.echo10-core :as echo10]
   [cmr.transmit.config :as transmit-config]
   [cmr.transmit.access-control :as ac]
   [cmr.system-int-test.utils.url-helper :as url]
   [cmr.system-int-test.utils.index-util :as index]
   [cmr.mock-echo.client.echo-util :as echo-util]
   [cmr.common.util :as util]
   [cmr.system-int-test.system :as s]
   [cmr.system-int-test.utils.dev-system-util :as dev-sys-util]
   [cmr.common-app.test.side-api :as side]
   [cmr.ingest.config :as icfg])
  (:import [java.lang.NumberFormatException]))

(defn disable-ingest-writes
  "Use the enable/disable endpoint on ingest to disable writes."
  []
  (let [response (client/post (url/disable-ingest-writes-url))]
    (is (= 200 (:status response)))))

(defn enable-ingest-writes
  "Use the enable/disable endpoint on ingest to enable writes."
  []
  (let [response (client/post (url/enable-ingest-writes-url))]
    (is (= 200 (:status response)))))

(defn- create-provider-through-url
  "Create the provider by http POST on the given url"
  [provider endpoint-url]
  (client/post endpoint-url
               {:body (json/generate-string provider)
                :content-type :json
                :throw-exceptions false
                :connection-manager (s/conn-mgr)
                :headers {transmit-config/token-header (transmit-config/echo-system-token)}}))

(defn create-mdb-provider
  "Create the provider with the given provider id in the metadata db"
  [provider]
  (create-provider-through-url provider (url/create-provider-url)))

(defn create-ingest-provider
  "Create the provider with the given provider id through ingest app"
  [provider]
  (create-provider-through-url provider (url/ingest-create-provider-url)))

(defn get-providers-through-url
  [provider-url]
  (-> (client/get provider-url {:connection-manager (s/conn-mgr)})
      :body
      (json/decode true)))

(defn get-providers
  []
  (get-providers-through-url (url/create-provider-url)))

(defn get-ingest-providers
  []
  (get-providers-through-url (url/ingest-create-provider-url)))

(defn delete-provider
  "Delete the provider with the matching provider-id from the CMR metadata repo."
  [provider-id]
  (let [{:keys [status]} (client/delete
                           (url/delete-provider-url provider-id)
                           {:throw-exceptions false
                            :connection-manager (s/conn-mgr)
                            :headers {transmit-config/token-header (transmit-config/echo-system-token)}})]
    (is (contains? #{204 404} status))))

(defn delete-ingest-provider
  "Delete the provider with the matching provider-id through the CMR ingest app."
  [provider-id]
  (let [{:keys [status body] :as response}
        (client/delete (url/ingest-provider-url provider-id)
                       {:throw-exceptions false
                        :connection-manager (s/conn-mgr)
                        :headers {transmit-config/token-header (transmit-config/echo-system-token)}})
        errors (:errors (json/decode body true))
        content-type (get-in response [:headers :content-type])
        content-length (get-in response [:headers :content-length])]
    {:status status
     :errors errors
     :content-type content-type
     :content-length content-length}))

(defn update-ingest-provider
  "Updates the ingest provider with the given parameters, which is a map of key and value for
  provider-id, short-name, cmr-only and small fields of the provider."
  [params]
  (client/put (url/ingest-provider-url (:provider-id params))
              {:throw-exceptions false
               :body (json/generate-string params)
               :content-type :json
               :connection-manager (s/conn-mgr)
               :headers {transmit-config/token-header (transmit-config/echo-system-token)}}))

(defn reindex-collection-permitted-groups
  "Tells ingest to run the reindex-collection-permitted-groups job"
  []
  (let [response (client/post (url/reindex-collection-permitted-groups-url)
                              {:connection-manager (s/conn-mgr)})]
    (is (= 200 (:status response)))))

(defn reindex-all-collections
  "Tells ingest to run the reindex all collections job"
  ([]
   (reindex-all-collections nil))
  ([{:keys [force-version]}]
   (let [response (client/post (url/reindex-all-collections-url)
                               {:connection-manager (s/conn-mgr)
                                :query-params {:force_version force-version}})]
     (is (= 200 (:status response))))))

(defn cleanup-expired-collections
  "Tells ingest to run the cleanup-expired-collections job"
  []
  (let [response (client/post (url/cleanup-expired-collections-url)
                              {:connection-manager (s/conn-mgr)})]
    (is (= 200 (:status response)))))

(defn translate-metadata
  "Translates metadata using the ingest translation endpoint. Returns the response."
  ([concept-type input-format metadata output-format options]
   (client/post (url/translate-metadata-url concept-type)
                {:connection-manager (s/conn-mgr)
                 :throw-exceptions false
                 :body metadata
                 :query-params (:query-params options)
                 :headers {"content-type" (mt/format->mime-type input-format)
                           "accept" (mt/format->mime-type output-format)
                           "cmr-skip-sanitize-umm-c" (:skip-sanitize-umm-c options)}}))
  ([concept-type input-format metadata output-format]
   (translate-metadata concept-type input-format metadata output-format nil)))

(defn translate-between-umm-versions
  "Translates two umm-versions using the ingest translation endpoint. Returns the response."
  ([concept-type input-version metadata output-version options]
   (let [format-base "application/vnd.nasa.cmr.umm+json;version="]
    (client/post (url/translate-metadata-url concept-type)
                 {:connection-manager (s/conn-mgr)
                  :throw-exceptions false
                  :body metadata
                  :query-params (:query-params options)
                  :headers {"Content-Type" (str format-base input-version)
                            "Accept" (str format-base output-version)}})))
  ([concept-type input-format metadata output-format]
   (translate-between-umm-versions concept-type input-format metadata output-format nil)))

(defn- parse-error-path
  "Convert the error path string into a sequence with element conversion to integers where possible"
  [path]
  (when path
    (map (fn [v]
           (try
             (Integer. v)
             (catch NumberFormatException _
               v)))
         (str/split path #"/"))))

(comment

  (parse-error-path "SpatialCoverage/Geometries/0")
  (parse-error-path "SpatialCoverage/1/Geometries"))


(defn- parse-xml-error-elem
  "Parse an xml error entry. If this contains a path then we need to return map with a :path
  and an :errors tag. Otherwise, just return the list of error messages."
  [elem]
  (if-let [path (parse-error-path (cx/string-at-path elem [:path]))]
    {:errors (vec (cx/strings-at-path elem [:errors :error])) :path path}
    (first (:content elem))))

(defn- parse-xml-error-response-elem
  "Parse an xml error response element"
  [elem]
  (let [{:keys [tag content]} elem
        expanded-content (map parse-xml-error-response-elem content)]
    (if (= :error tag)
      (parse-xml-error-elem elem)
      {tag expanded-content})))

(defmulti parse-ingest-body
  "Parse the ingest response body as a given format"
  (fn [response-format body]
    response-format))

(defmethod parse-ingest-body :xml
  [response-format response]
  (try
    (let [xml-elem (x/parse-str (:body response))]
      (if-let [errors (seq (cx/strings-at-path xml-elem [:error]))]
        (parse-xml-error-response-elem xml-elem)
        {:concept-id (cx/string-at-path xml-elem [:concept-id])
         :revision-id (Integer. (cx/string-at-path xml-elem [:revision-id]))
         :warnings (cx/string-at-path xml-elem [:warnings])}))

    (catch Exception e
      (throw (Exception. (str "Error parsing ingest body: " (pr-str (:body response)) e))))))

(defmethod parse-ingest-body :json
  [response-format response]
  (try
    (json/decode (:body response) true)
    (catch Exception e
      (throw (Exception. (str "Error parsing ingest body: " (pr-str (:body response))) e)))))

(defn parse-ingest-response
  "Parse an ingest response (if required) and append a status"
  [response options]
  (if (get options :raw? false)
    response
    (assoc (parse-ingest-body (or (:accept-format options) :xml) response)
           :status (:status response))))

(defmulti parse-validate-body
  "Parse the validate response body as a given format"
  (fn [response-format body]
    response-format))

(defmethod parse-validate-body :xml
  [response-format response]
  (when (not-empty (:body response))
    (try
      (let [xml-elem (x/parse-str (:body response))]
        (if-let [errors (seq (cx/strings-at-path xml-elem [:error]))]
          (parse-xml-error-response-elem xml-elem)
          {:warnings (cx/string-at-path xml-elem [:warnings])}))

      (catch Exception e
        (throw (Exception. (str "Error parsing validate-format body: " (pr-str (:body response)) e)))))))

(defmethod parse-validate-body :json
  [response-format response]
  (try
    (json/decode (:body response) true)
    (catch Exception e
      (throw (Exception. (str "Error parsing validate body: " (pr-str (:body response))) e)))))

(defn parse-validate-response
  "Parse a validate response (if required) and append a status"
  [response options]
  (if (get options :raw? false)
    response
    (assoc (parse-validate-body (or (:accept-format options) :xml) response)
           :status (:status response))))

(defn exec-ingest-http-request
  "Execute the http request defined by the given params map and returns the parsed ingest response."
  [params]
  (parse-ingest-response
    (client/request (assoc params :throw-exceptions false :connection-manager (s/conn-mgr))) {}))

(defn concept
  "Returns the concept map for ingest"
  [concept-type provider-id native-id format-key metadata]
  (let [mime-type (mt/format->mime-type format-key)]
    {:concept-type concept-type
     :provider-id provider-id
     :native-id native-id
     :metadata metadata
     :format mime-type}))

(defn ingest-concept
  "Ingest a concept and return a map with status, concept-id, and revision-id"
  ([concept]
   (ingest-concept concept {}))
  ([concept options]
   (let [{:keys [metadata format concept-type concept-id revision-id provider-id native-id]} concept
         {:keys [token client-id user-id validate-keywords validate-umm-c cmr-request-id]} options
         accept-format (:accept-format options)
         headers (util/remove-nil-keys {"Cmr-Concept-id" concept-id
                                        "Cmr-Revision-id" revision-id
                                        "Cmr-Validate-Keywords" validate-keywords
                                        "Cmr-Validate-Umm-C" validate-umm-c
                                        "Echo-Token" token
                                        "User-Id" user-id
                                        "Client-Id" client-id
                                        "CMR-Request-Id" cmr-request-id})
         params {:method :put
                 :url (url/ingest-url provider-id concept-type native-id)
                 :body  metadata
                 :content-type format
                 :headers headers
                 :throw-exceptions false
                 :connection-manager (s/conn-mgr)}
         params (merge params (when accept-format {:accept accept-format}))]
     (parse-ingest-response (client/request params) options))))

(defn delete-concept
  "Delete a given concept."
  ([concept]
   (delete-concept concept {}))
  ([concept options]
   (let [{:keys [provider-id concept-type native-id]} concept
         {:keys [token client-id accept-format revision-id user-id]} options
         headers (util/remove-nil-keys {"Echo-Token" token
                                        "Client-Id" client-id
                                        "User-Id" user-id
                                        "Cmr-Revision-id" revision-id})
         params {:method :delete
                 :url (url/ingest-url provider-id concept-type native-id)
                 :headers headers
                 :accept accept-format
                 :throw-exceptions false
                 :connection-manager (s/conn-mgr)}
         params (merge params (when accept-format {:accept accept-format}))]
     (parse-ingest-response (client/request params) options))))

(defn multipart-param-request
  "Submits a multipart parameter request to the given url with the multipart parameters indicated.
  A multipart parameter is a map the keys :name, :content, and :content-type."
  [url multipart-params]

  ;; clj-http has a specific format for the multipart params.
  (let [multipart-params (for [{:keys [name content content-type]} multipart-params]
                           {:name name
                            :content content
                            :mime-type content-type
                            :encoding "UTF-8"})
        response (client/request {:method :post
                                  :url url
                                  :multipart multipart-params
                                  :accept :json
                                  :throw-exceptions false
                                  :connection-manager (s/conn-mgr)})
        body (json/decode (:body response) true)]
    (assoc body :status (:status response))))

(defn validate-concept
  "Validate a concept and return a map with status and error messages if applicable."
  ([concept]
   (validate-concept concept {}))
  ([concept options]
   (let [{:keys [metadata format concept-type concept-id revision-id provider-id native-id]} concept
         {:keys [client-id validate-keywords]} options
         accept-format (get options :accept-format :xml)
         ;; added to allow testing of the raw response
         raw? (get options :raw? false)
         headers (util/remove-nil-keys {"Cmr-Concept-id" concept-id
                                        "Cmr-Revision-id" revision-id
                                        "Cmr-Validate-Keywords" validate-keywords
                                        "Client-Id" client-id})
         response (client/request
                    {:method :post
                     :url (url/validate-url provider-id concept-type native-id)
                     :body  metadata
                     :content-type format
                     :headers headers
                     :accept accept-format
                     :throw-exceptions false
                     :connection-manager (s/conn-mgr)})
         status (:status response)]
     (if raw?
       response
       (let [parsed-response (parse-validate-response response options)]
         (if (not= status 200)
           ;; Validation only returns a response body if there are errors.
           parsed-response
           (select-keys parsed-response [:status :warnings])))))))

(defn validate-granule
  "Validates a granule concept by sending it and optionally its parent collection to the validation
  endpoint"
  ([granule-concept]
   ;; Use the regular validation endpoint in this case.
   (validate-concept granule-concept))
  ([granule-concept collection-concept]
   (let [{:keys [provider-id native-id]} granule-concept]
     (multipart-param-request
       (url/validate-url provider-id :granule native-id)
       [{:name "granule"
         :content (:metadata granule-concept)
         :content-type (:format granule-concept)}
        {:name "collection"
         :content (:metadata collection-concept)
         :content-type (:format collection-concept)}]))))

(defn ingest-concepts
  "Ingests all the given concepts assuming that they should all be successful."
  ([concepts]
   (ingest-concepts concepts nil))
  ([concepts options]
   (doseq [concept concepts]
     (is (= {:status 200
             :concept-id (:concept-id concept)
             :revision-id (:revision-id concept)}
            (ingest-concept concept options))))))

(defn delete-concepts
  "Deletes all the given concepts assuming that they should all be successful."
  ([concepts]
   (delete-concepts concepts nil))
  ([concepts options]
   (doseq [concept concepts]
     (is (#{404 200} (:status (delete-concept concept options)))))))

;;; fixture - each test to call this fixture
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn create-provider
  "Creates a provider along with ACLs to create and access the providers data.
  Provider map should contain fields with details for an individual provider like what would be
  taken on the ingest api.

  Options:
  * grant-all-search? - Indicates whether all search acls should be granted for a provider. Default true
  * grant-all-ingest? - Indicates whether all ingest acls should be granted for a provider. Default true"
  ([provider-map]
   (create-provider provider-map {}))
  ([provider-map options]
   (let [{:keys [provider-guid provider-id short-name small cmr-only]} provider-map
         short-name (or short-name (:short-name options) provider-id)
         cmr-only (if (some? cmr-only) cmr-only (get options :cmr-only true))
         small (if (some? small) small (get options :small false))
         grant-all-search? (get options :grant-all-search? true)
         grant-all-ingest? (get options :grant-all-ingest? true)
         grant-all-access-control (get options :grant-all-access-control? true)]

     (create-mdb-provider {:provider-id provider-id
                           :short-name short-name
                           :cmr-only cmr-only
                           :small small})
     (echo-util/create-providers (s/context) {provider-guid provider-id})

     (when grant-all-search?
       (echo-util/grant (s/context)
                        [echo-util/guest-read-ace
                         echo-util/registered-user-read-ace]
                        :catalog-item-identity
                        (assoc (echo-util/catalog-item-id provider-guid)
                               :collection-applicable true
                               :granule-applicable true)))
     (when grant-all-ingest?
       (echo-util/grant-all-ingest (s/context) provider-guid))

     (when grant-all-access-control
       (echo-util/grant-system-group-permissions-to-all (s/context))
       (echo-util/grant-provider-group-permissions-to-all (s/context) provider-guid)))))

(def reset-fixture-default-options
  {:grant-all-search? true
   :grant-all-ingest? true})

(defn reset-fixture
  "Resets all the CMR systems then creates the given providers in ECHO and the CMR.
  providers can be passed in two ways: 1) a map of provider-guids to provider-ids
  {'provider-guid1' 'PROV1' 'provider-guid2' 'PROV2'}, or
  2) a list of provider attributes maps:
  [{:provider-guid 'provider-guid1' :provider-id 'PROV1' :short-name 'provider short name'}...]"
  ([]
   (reset-fixture {}))
  ([providers]
   (reset-fixture providers nil))
  ([providers options]
   (fn [f]
     (let [{:keys [grant-all-search? grant-all-ingest?]}
           (merge reset-fixture-default-options options)]
       (dev-sys-util/reset)
       (let [providers (if (sequential? providers)
                         providers
                         (for [[provider-guid provider-id] providers]
                           {:provider-guid provider-guid :provider-id provider-id}))]
         (doseq [provider-map providers]
           (create-provider provider-map {:grant-all-search? grant-all-search?
                                          :grant-all-ingest? grant-all-ingest?})))
       (f)))))

(defn clear-caches
  "Clears caches in the ingest application"
  []
  (client/post (url/ingest-clear-cache-url)
               {:connection-manager (s/conn-mgr)
                :headers {transmit-config/token-header (transmit-config/echo-system-token)}}))
