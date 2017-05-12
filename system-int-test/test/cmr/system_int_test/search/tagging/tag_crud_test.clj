(ns cmr.system-int-test.search.tagging.tag-crud-test
  "This tests the CMR Search API's tagging capabilities"
  (:require [clojure.test :refer :all]
            [clojure.string :as str]
            [cmr.common.util :refer [are2]]
            [cmr.system-int-test.utils.ingest-util :as ingest]
            [cmr.system-int-test.utils.search-util :as search]
            [cmr.system-int-test.utils.index-util :as index]
            [cmr.system-int-test.utils.tag-util :as tags]
            [cmr.system-int-test.data2.core :as d]
            [cmr.system-int-test.data2.collection :as dc]
            [cmr.mock-echo.client.echo-util :as e]
            [cmr.system-int-test.system :as s]))

(use-fixtures :each (join-fixtures
                      [(ingest/reset-fixture {"provguid1" "PROV1"})
                       tags/grant-all-tag-fixture]))

(def field-maxes
  "A map of fields to their max lengths"
  {:tag_key 1030
   :description 4000})

(deftest create-tag-validation-test
  (testing "Create without token"
    (is (= {:status 401
            :errors ["Tags cannot be modified without a valid user token."]}
           (tags/create-tag nil (tags/make-tag)))))

  (testing "Create with unknown token"
    (is (= {:status 401
            :errors ["Token ABC does not exist"]}
           (tags/create-tag "ABC" (tags/make-tag)))))

  (let [valid-user-token (e/login (s/context) "user1")
        valid-tag (tags/make-tag)]

    (testing "Create tag with invalid content type"
      (is (= {:status 400,
              :errors
              ["The mime types specified in the content-type header [application/xml] are not supported."]}
             (tags/create-tag valid-user-token valid-tag {:http-options {:content-type :xml}}))))

    (testing "Create tag with invalid tag key"
      (is (= {:status 422,
              :errors
              ["Tag key [a/c] contains '/' character. Tag keys cannot contain this character."]}
             (tags/create-tag valid-user-token (assoc valid-tag :tag-key "a/c")))))

    (testing "Missing field validations"
      (is (= {:status 400
              :errors ["object has missing required properties ([\"tag_key\"])"]}
             (tags/create-tag valid-user-token (dissoc valid-tag :tag-key)))))

    (testing "Minimum field length validations"
      (are [field]
           (= {:status 400
               :errors [(format "/%s string \"\" is too short (length: 0, required minimum: 1)"
                                (name field))]}
              (tags/create-tag valid-user-token (assoc valid-tag field "")))

           :tag_key :description))

    (testing "Maximum field length validations"
      (doseq [[field max-length] field-maxes]
        (let [long-value (tags/string-of-length (inc max-length))]
          (is (= {:status 400
                  :errors [(format "/%s string \"%s\" is too long (length: %d, maximum allowed: %d)"
                                   (name field) long-value (inc max-length) max-length)]}
                 (tags/create-tag
                   valid-user-token
                   (assoc valid-tag field long-value)))))))))

(deftest create-tag-test
  (testing "Successful creation"
    (let [tag-key "tag1"
          tag (tags/make-tag {:tag-key tag-key})
          token (e/login (s/context) "user1")
          {:keys [status concept-id revision-id]} (tags/create-tag token tag)]
      (is (= 201 status))
      (is concept-id)
      (is (= 1 revision-id))
      (tags/assert-tag-saved (assoc tag :originator-id "user1") "user1" concept-id revision-id)

      (testing "Creation with an already existing tag-key"
        (is (= {:status 409
                :errors [(format "A tag with tag-key [%s] already exists with concept id [%s]."
                                 (:tag-key tag) concept-id)]}
               (tags/create-tag token tag))))

      (testing "tag-key is case-insensitive"
        (let [{:keys[status errors]} (tags/create-tag token (update tag :tag-key str/upper-case))]
          (is (= [409 [(format "A tag with tag-key [%s] already exists with concept id [%s]."
                               (:tag-key tag) concept-id)]]
                 [status errors]))))

      (testing "Creation with different tag-key succeeds"
        (let [response (tags/create-tag token (assoc tag :tag-key "different"))]
          (is (= 201 (:status response)))
          (is (not= concept-id (:concept-id response)))
          (is (= 1 (:revision-id response)))))

      (testing "Creation of previously deleted tag"
        (tags/delete-tag token tag-key)
        (let [new-tag (assoc tag :description "new description")
              token2 (e/login (s/context) "user2")
              response (tags/create-tag token2 new-tag)]
          (is (= {:status 200 :concept-id concept-id :revision-id 3}
                 response))
          ;; A tag that was deleted but recreated gets a new originator id.
          (tags/assert-tag-saved (assoc new-tag :originator-id "user2") "user2" concept-id 3))))

    (testing "Create tag with fields at maximum length"
      (let [tag (into {} (for [[field max-length] field-maxes]
                           [field (tags/string-of-length max-length)]))]
        (is (= 201 (:status (tags/create-tag (e/login (s/context) "user1") tag)))))))

  (testing "Creation without optional fields is allowed"
    (let [tag (dissoc (tags/make-tag {:tag-key "tag-key2"}) :description)
          token (e/login (s/context) "user1")
          {:keys [status concept-id revision-id]} (tags/create-tag token tag)]
      (is (= 201 status))
      (is concept-id)
      (is (= 1 revision-id)))))

(deftest get-tag-test
  (let [tag (tags/make-tag {:tag-key "MixedCaseTagKey"})
        tag-key "mixedcasetagkey"
        token (e/login (s/context) "user1")
        _ (tags/create-tag token tag)
        expected-tag (-> tag
                         (update :tag-key str/lower-case)
                         (assoc :originator-id "user1" :status 200))]
    (testing "Retrieve existing tag, verify tag-key is converted to lowercase"
      (is (= expected-tag (tags/get-tag tag-key))))

    (testing "Retrieve tag with tag-key is case insensitive"
      (is (= expected-tag (tags/get-tag "MixedCaseTagKey"))))
    (testing "Retrieve unknown tag"
      (is (= {:status 404
              :errors ["Tag could not be found with tag-key [tag100]"]}
             (tags/get-tag "Tag100"))))

    (testing "Retrieve deleted tag"
      (tags/delete-tag token tag-key)
      (is (= {:status 404
              :errors [(format "Tag with tag-key [%s] was deleted." tag-key)]}
             (tags/get-tag tag-key))))))

(deftest update-tag-test
  (let [tag-key "tag1"
        tag (tags/make-tag {:tag-key tag-key})
        token (e/login (s/context) "user1")
        {:keys [concept-id revision-id]} (tags/create-tag token tag)]

    (testing "Update with originator id"
      (let [updated-tag (-> tag
                            (update-in [:description] #(str % " updated"))
                            (assoc :originator-id "user1"))
            token2 (e/login (s/context) "user2")
            response (tags/update-tag token2 tag-key updated-tag)]
        (is (= {:status 200 :concept-id concept-id :revision-id 2}
               response))
        (tags/assert-tag-saved updated-tag "user2" concept-id 2)))

    (testing "Update without originator id"
      (let [updated-tag (dissoc tag :originator-id)
            token2 (e/login (s/context) "user2")
            response (tags/update-tag token2 tag-key updated-tag)]
        (is (= {:status 200 :concept-id concept-id :revision-id 3}
               response))
        ;; The previous originator id should not change
        (tags/assert-tag-saved (assoc updated-tag :originator-id "user1") "user2" concept-id 3)))))

(deftest update-tag-failure-test
  (let [tag-key "tag1"
        tag (tags/make-tag {:tag-key tag-key})
        token (e/login (s/context) "user1")
        {:keys [concept-id revision-id]} (tags/create-tag token tag)
        ;; The stored updated tag would have user1 in the originator id
        tag (assoc tag :originator-id "user1")]

    (testing "Update tag with invalid content type"
      (is (= {:status 400,
              :errors
              ["The mime types specified in the content-type header [application/xml] are not supported."]}
             (tags/update-tag token tag-key tag {:http-options {:content-type :xml}}))))

    (testing "Update without token"
      (is (= {:status 401
              :errors ["Tags cannot be modified without a valid user token."]}
             (tags/update-tag nil tag-key tag))))

    (testing "Fields that cannot be changed"
      (are [field human-name]
           (= {:status 400
               :errors [(format (str "%s cannot be modified. Attempted to change existing value"
                                     " [%s] to [updated]")
                                human-name
                                (get tag field))]}
              (tags/update-tag token tag-key (assoc tag field "updated")))
           :tag-key "Tag Key"
           :originator-id "Originator Id"))

    (testing "Updates applies JSON validations"
      (is (= {:status 400
              :errors ["/description string \"\" is too short (length: 0, required minimum: 1)"]}
             (tags/update-tag token concept-id (assoc tag :description "")))))

    (testing "Update tag that doesn't exist"
      (is (= {:status 404
              :errors ["Tag could not be found with tag-key [tag2]"]}
             (tags/update-tag token "tag2" tag))))

    (testing "Update deleted tag"
      (tags/delete-tag token tag-key)
      (is (= {:status 404
              :errors [(format "Tag with tag-key [%s] was deleted." tag-key)]}
             (tags/update-tag token tag-key tag))))))

(deftest delete-tag-test
  (let [tag-key "tag1"
        tag (tags/make-tag {:tag-key tag-key})
        token (e/login (s/context) "user1")
        {:keys [concept-id revision-id]} (tags/create-tag token tag)]

    (testing "Delete without token"
      (is (= {:status 401
              :errors ["Tags cannot be modified without a valid user token."]}
             (tags/delete-tag nil tag-key))))

    (testing "Delete success"
      (is (= {:status 200 :concept-id concept-id :revision-id 2}
             (tags/delete-tag token tag-key)))
      (tags/assert-tag-deleted tag "user1" concept-id 2))

    (testing "Delete tag that was already deleted"
      (is (= {:status 404
              :errors [(format "Tag with tag-key [%s] was deleted." tag-key)]}
             (tags/delete-tag token tag-key))))

    (testing "Delete tag that doesn't exist"
      (is (= {:status 404
              :errors ["Tag could not be found with tag-key [tag2]"]}
             (tags/delete-tag token "tag2"))))))
