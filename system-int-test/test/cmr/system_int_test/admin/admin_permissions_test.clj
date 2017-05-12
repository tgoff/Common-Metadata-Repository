(ns cmr.system-int-test.admin.admin-permissions-test
  "Verifies the correct administrative permissions are enforced admin only apis"
  (:require [clojure.test :refer :all]
            [cmr.system-int-test.utils.ingest-util :as ingest]
            [cmr.system-int-test.utils.search-util :as search]
            [cmr.system-int-test.utils.index-util :as index]
            [cmr.mock-echo.client.echo-util :as e]
            [cmr.system-int-test.utils.url-helper :as url]
            [cmr.system-int-test.system :as s]
            [cmr.system-int-test.utils.index-util :as index-util]
            [cmr.transmit.config :as transmit-config]
            [clj-http.client :as client]))

(use-fixtures :each (ingest/reset-fixture {"provguid1" "PROV1"} {:grant-all-search? false
                                                                 :grant-all-ingest? false}))

(defn has-action-permission?
  "Attempts to perform the given action using the url and method with the token. Returns true
  if the action was successful."
  [url method token]
  (let [response (client/request {:url url
                                  :method method
                                  :query-params {:token token}
                                  :connection-manager (s/conn-mgr)
                                  :throw-exceptions false})
        status (:status response)]
     ;; Make sure the status returned is success or 401
    (is (some #{status} [200 201 204 401]))
    (not= status 401)))

(deftest ingest-management-permission-test

  (let [admin-read-update-group-concept-id (e/get-or-create-group (s/context) "admin-read-update-group")
        admin-read-group-concept-id (e/get-or-create-group (s/context) "admin-read-group")
        admin-update-group-concept-id (e/get-or-create-group (s/context) "admin-update-group")
        prov-admin-group-concept-id (e/get-or-create-group (s/context) "prov-admin-group")
        group2-concept-id (e/get-or-create-group (s/context) "group2")
        group3-concept-id (e/get-or-create-group (s/context) "group3")
        guest-token (e/login-guest (s/context))
        user-token (e/login (s/context) "user1" [group2-concept-id group3-concept-id])
        admin-read-token (e/login (s/context) "admin1" [admin-read-group-concept-id group3-concept-id])
        admin-update-token (e/login (s/context) "admin2" [admin-update-group-concept-id group3-concept-id])
        admin-read-update-token (e/login (s/context) "admin3" [admin-read-update-group-concept-id group3-concept-id])
        prov-admin-token (e/login (s/context) "prov-admin" [prov-admin-group-concept-id group3-concept-id])]

    ;; Grant admin-group-guid admin permission
    (e/grant-group-admin (s/context) admin-read-group-concept-id :read)
    (e/grant-group-admin (s/context) admin-update-group-concept-id :update)
    (e/grant-group-admin (s/context) admin-read-update-group-concept-id :read :update)
    ;; Grant provider admin permission, but not system permission
    (e/grant-group-provider-admin (s/context) prov-admin-group-concept-id "PROV1" :read :update)

    (are [url]
      (and
       (not (has-action-permission? url :post prov-admin-token))
       (not (has-action-permission? url :post guest-token))
       (not (has-action-permission? url :post user-token))
       (not (has-action-permission? url :post admin-read-token))
       (has-action-permission? url :post admin-update-token)
       (has-action-permission? url :post admin-read-update-token))

      (url/search-clear-cache-url)
      (url/search-reset-url)
      (url/indexer-clear-cache-url)
      (url/indexer-reset-url)
      (url/enable-ingest-writes-url)
      (url/disable-ingest-writes-url)
      (url/enable-search-writes-url)
      (url/disable-search-writes-url)
      (url/enable-access-control-writes-url)
      (url/disable-access-control-writes-url)
      (url/mdb-reset-url)
      (url/index-set-reset-url)
      (url/cubby-reset-url)
      (url/reindex-collection-permitted-groups-url)
      (url/reindex-all-collections-url)
      (url/cleanup-expired-collections-url)
      (url/access-control-reindex-acls-url))))
