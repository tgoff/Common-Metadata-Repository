(ns cmr.system-int-test.search.acls.collection-acl-search-test
  "Tests searching for collections with ACLs in place"
  (:require
    [cmr.common-app.test.side-api :as side]
    [clojure.test :refer :all]
    [clojure.string :as str]
    [cmr.acl.acl-fetcher :as acl-fetcher]
    [cmr.common.services.messages :as msg]
    [cmr.common.util :refer [are2] :as util]
    [cmr.mock-echo.client.echo-util :as e]
    [cmr.system-int-test.data2.atom :as da]
    [cmr.system-int-test.data2.collection :as dc]
    [cmr.system-int-test.data2.core :as d]
    [cmr.system-int-test.data2.opendata :as od]
    [cmr.system-int-test.system :as s]
    [cmr.system-int-test.utils.dev-system-util :as dev-sys-util]
    [cmr.system-int-test.utils.index-util :as index]
    [cmr.system-int-test.utils.ingest-util :as ingest]
    [cmr.system-int-test.utils.metadata-db-util :as mdb]
    [cmr.system-int-test.utils.search-util :as search]
    [cmr.transmit.access-control :as ac]
    [cmr.transmit.config :as tc]))


(use-fixtures :each (join-fixtures
                      [(ingest/reset-fixture {"provguid1" "PROV1" "provguid2" "PROV2"
                                              "provguid3" "PROV3" "provguid4" "PROV4"}
                                             {:grant-all-search? false})
                       (search/freeze-resume-time-fixture)]))

(comment
  (dev-sys-util/reset)
  (ingest/create-provider {:provider-guid "PROV1" :provider-id "PROV1"})
  (ingest/create-provider {:provider-guid "PROV2" :provider-id "PROV2"})
  (ingest/create-provider {:provider-guid "PROV3" :provider-id "PROV3"}))

(deftest invalid-security-token-test
  (is (= {:errors ["Token ABC123 does not exist"], :status 401}
         (search/find-refs :collection {:token "ABC123"}))))

(deftest expired-security-token-test
  (is (= {:errors ["Token [expired-token] has expired."], :status 401}
         (search/find-refs :collection {:token "expired-token"}))))

(deftest collection-search-with-no-acls-test
  ;; system token can see all collections with no ACLs
  (let [guest-token (e/login-guest (s/context))
        c1-echo (d/ingest "PROV1"
                          (dc/collection {:entry-title "c1-echo" :access-value 1})
                          {:format :echo10})
        c1-dif (d/ingest "PROV1"
                         (dc/collection-dif {:entry-title "c1-dif" :access-value 1})
                         {:format :dif})
        c1-dif10 (d/ingest "PROV1"
                           (dc/collection-dif10 {:entry-title "c1-dif10" :access-value 1})
                           {:format :dif10})
        c1-iso (d/ingest "PROV1"
                         (dc/collection {:entry-title "c1-iso" :access-value 1})
                         {:format :iso19115})
        c1-smap (d/ingest "PROV1"
                          (dc/collection {:entry-title "c1-smap" :access-value 1})
                          {:format :iso-smap})]
    (index/wait-until-indexed)

    ;;;;system token sees everything
    (is (d/refs-match? [c1-echo c1-dif c1-dif10 c1-iso c1-smap]
                       (search/find-refs :collection {:token (tc/echo-system-token)})))
    ;;guest user sees nothing
    (is (d/refs-match? []
                       (search/find-refs :collection {:token guest-token})))))

(deftest collection-search-with-restriction-flag-acls-test
  (let [guest-token (e/login-guest (s/context))
        c1-echo (d/ingest "PROV1" (dc/collection {:entry-title "c1-echo"
                                                  :access-value 1})
                          {:format :echo10})
        c2-echo (d/ingest "PROV1" (dc/collection {:entry-title "c2-echo"
                                                  :access-value 0})
                          {:format :echo10})
        c1-dif (d/ingest "PROV1" (dc/collection-dif {:entry-title "c1-dif"
                                                     :access-value 1})
                         {:format :dif})
        c2-dif (d/ingest "PROV1" (dc/collection-dif {:entry-title "c2-dif"
                                                     :access-value 0})
                         {:format :dif})
        c1-dif10 (d/ingest "PROV1" (dc/collection-dif10 {:entry-title "c1-dif10"
                                                         :access-value 1})
                           {:format :dif10})
        c2-dif10 (d/ingest "PROV2" (dc/collection-dif10 {:entry-title "c2-dif10"
                                                         :access-value 0})
                           {:format :dif10})
        c1-iso (d/ingest "PROV1" (dc/collection {:entry-title "c1-iso"
                                                 :access-value 1})
                         {:format :iso19115})
        c2-iso (d/ingest "PROV1" (dc/collection {:entry-title "c2-iso"
                                                 :access-value 0})
                         {:format :iso19115})
        ;; access-value is not supported in ISO-SMAP, so it won't be found
        c1-smap (d/ingest "PROV1" (dc/collection {:entry-title "c1-smap"
                                                  :access-value 1})
                          {:format :iso-smap})
        coll3 (d/ingest "PROV1" (dc/collection {:entry-title "coll3"}))]
    (index/wait-until-indexed)

    ;; grant restriction flag acl
    (e/grant-guest (s/context)
                   (e/coll-catalog-item-id
                     "PROV1"
                     (e/coll-id ["c1-echo" "c2-echo" "c1-dif" "c2-dif" "c1-dif10"
                                 "c1-iso" "c2-iso" "c1-smap" "coll3"]
                                {:min-value 0.5 :max-value 1.5})))
    (ingest/reindex-collection-permitted-groups (tc/echo-system-token))
    (index/wait-until-indexed)

    (is (d/refs-match? [c1-echo c1-dif c1-dif10 c1-iso]
                       (search/find-refs :collection {:token guest-token})))))

(deftest collection-search-with-acls-test
  (let [group1-concept-id (e/get-or-create-group (s/context) "group1")
        group2-concept-id (e/get-or-create-group (s/context) "group2")
        group3-concept-id (e/get-or-create-group (s/context) "group3")

        coll1 (d/ingest "PROV1" (dc/collection {:entry-title "coll1"}))
        coll2 (d/ingest "PROV1" (dc/collection {:entry-title "coll2"}))
        coll3 (d/ingest "PROV1" (dc/collection {:entry-title "coll3"}))
        coll4 (d/ingest "PROV1" (dc/collection {:entry-title "coll4"
                                                :access-value 5.0}))
        ;; no permission granted on coll5
        coll5 (d/ingest "PROV1" (dc/collection {:entry-title "coll5"}))

        ;; PROV2
        coll6 (d/ingest "PROV2" (dc/collection {:entry-title "coll6"}))
        coll7 (d/ingest "PROV2" (dc/collection {:entry-title "coll7"}))
        ;; A dif collection
        coll8 (d/ingest "PROV2" (dc/collection-dif
                                  {:entry-title "coll8"
                                   :short-name "S8"
                                   :version-id "V8"
                                   :long-name "coll8"})
                        {:format :dif})
        ;; added for atom results
        coll8 (assoc coll8 :original-format "DIF")

        ;; PROV3
        coll9 (d/ingest "PROV3" (dc/collection {:entry-title "coll9"}))
        coll10 (d/ingest "PROV3" (dc/collection {:entry-title "coll10"
                                                 :access-value 12.0}))
        ;; PROV4
        ;; group3 has permission to read this collection revision
        coll11-1 (d/ingest "PROV4" (dc/collection {:entry-title "coll11-entry-title"
                                                   :native-id "coll11"}))
        ;; tombstone
        coll11-2 (assoc (ingest/delete-concept (d/item->concept coll11-1))
                        :entry-title "coll11-entry-title"
                        :deleted true
                        :revision-id 2)
        ;; no permissions to read this revision since entry-title has changed
        coll11-3 (d/ingest "PROV4" (dc/collection {:entry-title "coll11"
                                                   :native-id "coll11"}))
        ;; group 3 has permission to read this collection revision
        coll12-1 (d/ingest "PROV4" (dc/collection {:entry-title "coll12-entry-title"
                                                   :native-id "coll12"}))
        ;; no permissions to read this collection since entry-title has changed
        coll12-2 (d/ingest "PROV4" (dc/collection {:entry-title "coll12"
                                                   :native-id "coll12"}))
        ;; no permision to see this tombstone since it has same entry-title as coll12-2
        coll12-3 (assoc (ingest/delete-concept (d/item->concept coll12-2))
                        :deleted true
                        :revision-id 2)

        all-colls [coll1 coll2 coll3 coll4 coll5 coll6 coll7 coll8 coll9 coll10]
        guest-permitted-collections [coll1 coll4 coll6 coll7 coll8 coll9]
        guest-token (e/login-guest (s/context))
        user1-token (e/login (s/context) "user1")
        user2-token (e/login (s/context) "user2" [group1-concept-id])
        user3-token (e/login (s/context) "user3" [group1-concept-id group2-concept-id])
        user4-token (e/login (s/context) "user4" [group3-concept-id])]

    (index/wait-until-indexed)
    ;; Grant guests permission to coll1
    (e/grant-guest (s/context) (e/coll-catalog-item-id "PROV1" (e/coll-id ["coll1"])))
    (e/grant-guest (s/context) (e/coll-catalog-item-id "PROV1" (e/coll-id ["notexist"])))
    ;; restriction flag acl grants matches coll4
    (e/grant-guest (s/context) (e/coll-catalog-item-id "PROV1" (e/coll-id ["coll4"] {:min-value 4 :max-value 6})))

    ;; Grant undefined access values in prov3
    (e/grant-guest (s/context) (e/coll-catalog-item-id "PROV3" (e/coll-id nil {:include_undefined_value true})))

    ;; all collections in prov2 granted to guests
    (e/grant-guest (s/context) (e/coll-catalog-item-id "PROV2"))
    ;; grant registered users permission to coll2 and coll4
    (e/grant-registered-users (s/context) (e/coll-catalog-item-id "PROV1" (e/coll-id ["coll2" "coll4"])))
    ;; grant specific group permission to coll3, coll6, and coll8
    (e/grant-group (s/context) group1-concept-id (e/coll-catalog-item-id "PROV1" (e/coll-id ["coll3"])))
    (e/grant-group (s/context) group2-concept-id (e/coll-catalog-item-id "PROV2" (e/coll-id ["coll6" "coll8"])))
    (e/grant-group (s/context) group3-concept-id (e/coll-catalog-item-id "PROV4"
                                                         (e/coll-id ["coll11-entry-title" "coll12-entry-title"])))

    (ingest/reindex-collection-permitted-groups (tc/echo-system-token))
    (index/wait-until-indexed)

    (testing "parameter search acl enforcement"
      (are [token items]
        (d/refs-match? items (search/find-refs :collection (when token {:token token})))

        ;; not logged in should be guest
        nil guest-permitted-collections

        ;; login and use guest token
        guest-token guest-permitted-collections

        ;; test searching as a user
        user1-token [coll2 coll4]

        ;; Test searching with users in groups
        user2-token [coll2 coll4 coll3]
        user3-token [coll2 coll4 coll3 coll6 coll8]))

    (testing "token can be sent through a header"
      (is (d/refs-match? [coll2 coll4]
                         (search/find-refs :collection {} {:headers {"Echo-Token" user1-token}}))))
    (testing "aql search parameter enforcement"
      (is (d/refs-match? [coll2 coll4]
                         (search/find-refs-with-aql :collection [] {} {:headers {"Echo-Token" user1-token}}))))
    (testing "Direct transformer retrieval acl enforcement"
      (testing "registered user"
        (d/assert-metadata-results-match
         :echo10 [coll2 coll4]
         (search/find-metadata :collection :echo10 {:token user1-token
                                                    :concept-id (conj (map :concept-id all-colls)
                                                                      "C9999-PROV1")})))
      (testing "guest access"
        (d/assert-metadata-results-match
         :echo10 guest-permitted-collections
         (search/find-metadata :collection :echo10 {:token guest-token
                                                    :concept-id (map :concept-id all-colls)})))
      (testing "Empty token matches guest access"
        (d/assert-metadata-results-match
         :echo10 guest-permitted-collections
         (search/find-metadata :collection :echo10 {:token ""
                                                    :concept-id (map :concept-id all-colls)})))

      (testing "user in groups"
        (d/assert-metadata-results-match
         :echo10 [coll4 coll6 coll3 coll8 coll2]
         (search/find-metadata :collection :echo10 {:token user3-token
                                                    :concept-id (map :concept-id all-colls)}))))
    (testing "ATOM ACL enforcement"
      (testing "all items"
        (let [coll-atom (da/collections->expected-atom
                         guest-permitted-collections
                         (format "collections.atom?token=%s&page_size=100" guest-token))]
          (is (= coll-atom (:results (search/find-concepts-atom :collection {:token guest-token
                                                                             :page-size 100}))))))

      (testing "by concept id"
        (let [concept-ids (map :concept-id all-colls)
              coll-atom (da/collections->expected-atom
                         guest-permitted-collections
                         (str "collections.atom?token=" guest-token
                              "&page_size=100&concept_id="
                              (str/join "&concept_id=" concept-ids)))]
          (is (= coll-atom (:results (search/find-concepts-atom :collection {:token guest-token
                                                                             :page-size 100
                                                                             :concept-id concept-ids})))))))
    (testing "JSON ACL enforcement"
      (testing "all items"
        (let [coll-json (da/collections->expected-atom
                         guest-permitted-collections
                         (format "collections.json?token=%s&page_size=100" guest-token))]
          (is (= coll-json (:results (search/find-concepts-json :collection {:token guest-token
                                                                             :page-size 100}))))))

      (testing "by concept id"
        (let [concept-ids (map :concept-id all-colls)
              coll-json (da/collections->expected-atom
                         guest-permitted-collections
                         (str "collections.json?token=" guest-token
                              "&page_size=100&concept_id="
                              (str/join "&concept_id=" concept-ids)))]
          (is (= coll-json (:results (search/find-concepts-json :collection {:token guest-token
                                                                             :page-size 100
                                                                             :concept-id concept-ids})))))))

    (testing "opendata ACL enforcement"
      (testing "all items"
        (let [actual-od (search/find-concepts-opendata :collection {:token guest-token
                                                                    :page-size 100})]
          (od/assert-collection-opendata-results-match guest-permitted-collections actual-od)))

      (testing "by concept id"
        (let [concept-ids (map :concept-id all-colls)
              actual-od (search/find-concepts-opendata :collection {:token guest-token
                                                                    :page-size 100
                                                                    :concept-id concept-ids})]
          (od/assert-collection-opendata-results-match guest-permitted-collections actual-od))))

    (testing "all_revisions"
      (are2 [collections params]
        (d/refs-match? collections (search/find-refs :collection params))

        ;; only old revisions satisfy ACL - they should not be returned
        "provider-id all-revisions=false"
        []
        {:provider-id "PROV4" :all-revisions false :token user4-token}

        ;; only permissioned revisions are returned - including tombstones
        "provider-id all-revisions=true"
        [coll11-1 coll11-2 coll12-1]
        {:provider-id "PROV4" :all-revisions true :token user4-token}

        ;; none of the revisions are readable by guest users
        "provider-id all-revisions=true no token"
        []
        {:provider-id "PROV4" :all-revisions true}))))

;; This tests that when acls change after collections have been indexed that collections will be
;; reindexed when ingest detects the acl hash has change.
(deftest acl-change-test
  (let [coll1 (d/ingest "PROV1" (dc/collection-dif10 {:entry-title "coll1"}) {:format :dif10})
        coll2-umm (dc/collection {:entry-title "coll2" :short-name "short1"})
        coll2-1 (d/ingest "PROV1" coll2-umm)
        ;; 2 versions of collection 2 will allow us to test the force reindex option after we
        ;; force delete the latest version of coll2-2
        coll2-2 (d/ingest "PROV1" (assoc-in coll2-umm [:product :short-name] "short2"))
        coll3 (d/ingest "PROV2" (dc/collection-dif10 {:entry-title "coll3"}) {:format :dif10})
        coll4 (d/ingest "PROV2" (dc/collection {:entry-title "coll4"}))

        _ (index/wait-until-indexed)
        acl1 (e/grant-guest (s/context) (e/coll-catalog-item-id "PROV1" (e/coll-id ["coll1"])))
        acl2 (e/grant-guest (s/context) (e/coll-catalog-item-id "PROV2" (e/coll-id ["coll3"])))]

    (testing "normal reindex collection permitted groups"
      (ingest/reindex-collection-permitted-groups (tc/echo-system-token))
      (index/wait-until-indexed)

      ;; before acls change
      (is (d/refs-match? [coll1 coll3] (search/find-refs :collection {})))

      ;; Grant collection 2
      (e/grant-guest (s/context) (e/coll-catalog-item-id "PROV1" (e/coll-id ["coll2"])))
      ;; Ungrant collection 3
      (e/ungrant (s/context) acl2)

      ;; Try searching again before the reindexing
      (is (d/refs-match? [coll1 coll3] (search/find-refs :collection {})))

      ;; Reindex collection permitted groups
      (ingest/reindex-collection-permitted-groups (tc/echo-system-token))
      (index/wait-until-indexed)

      ;; Search after reindexing
      (is (d/refs-match? [coll1 coll2-2] (search/find-refs :collection {}))))

    (testing "reindex all collections"

      ;; Grant collection 4
      (e/grant-guest (s/context) (e/coll-catalog-item-id "PROV2" (e/coll-id ["coll4"])))

      ;; Try before reindexing
      (is (d/refs-match? [coll1 coll2-2] (search/find-refs :collection {})))

      ;; Reindex all collections
      ;; manually check the logs here. It should say it's reindexing provider 1 and provider 3 as well.
      (ingest/reindex-all-collections)
      (index/wait-until-indexed)

      ;; Search after reindexing
      (is (d/refs-match? [coll1 coll2-2 coll4] (search/find-refs :collection {}))))

    ;; Tests reindexing using the force current version option
    (testing "Force version reindex all collections"
      ;; Verify we can find coll2 with the lastest data
      (d/assert-refs-match [coll2-2] (search/find-refs :collection {:short-name "short2"}))

      ;; Delete the latest version of coll2
      (is (= 200 (:status (mdb/force-delete-concept (:concept-id coll2-2) 2))))
      (index/wait-until-indexed)

      ;; After deleting the latest version of coll2 we will still find that.
      (d/assert-refs-match [coll2-2] (search/find-refs :collection {:short-name "short2"}))

      ;; Reindexing all the collections doesn't solve the problem
      (ingest/reindex-all-collections)
      (index/wait-until-indexed)
      (d/assert-refs-match [coll2-2] (search/find-refs :collection {:short-name "short2"}))

      ;; A force reindex all collections will make elastic take the earlier version of the collections.
      (ingest/reindex-all-collections {:force-version true})
      (index/wait-until-indexed)
      (d/assert-refs-match [] (search/find-refs :collection {:short-name "short2"}))
      (d/assert-refs-match [coll2-1] (search/find-refs :collection {:short-name "short1"})))))




;; Verifies that tokens are cached by checking that a logged out token still works after it was used.
;; This isn't the desired behavior. It's just a side effect that shows it's working.
(deftest cache-token-test
  (let [coll1 (d/ingest "PROV1" (dc/collection {:entry-title "coll1"}))
        acl1 (e/grant-registered-users (s/context) (e/coll-catalog-item-id "PROV1" (e/coll-id ["coll1"])))
        user1-token (e/login (s/context) "user1")
        user2-token (e/login (s/context) "user2")]

    (index/wait-until-indexed)

    ;; A logged out token is normally not useful
    (e/logout (s/context) user2-token)
    (is (= {:errors ["Token ABC-2 does not exist"], :status 401}
           (search/find-refs :collection {:token user2-token})))

    ;; Use user1-token so it will be cached
    (is (d/refs-match? [coll1] (search/find-refs :collection {:token user1-token})))

    ;; logout
    (e/logout (s/context) user1-token)
    ;; The token should be cached
    (is (d/refs-match? [coll1] (search/find-refs :collection {:token user1-token})))))
