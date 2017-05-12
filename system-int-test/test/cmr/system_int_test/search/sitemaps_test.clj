(ns cmr.system-int-test.search.sitemaps-test
  (:require [clj-http.client :as client]
            [clj-xml-validation.core :as xmlv]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.test :refer :all]
            [cmr.system-int-test.data2.core :as d]
            [cmr.system-int-test.utils.index-util :as index]
            [cmr.system-int-test.utils.ingest-util :as ingest]
            [cmr.system-int-test.utils.tag-util :as tags]
            [cmr.mock-echo.client.echo-util :as e]
            [cmr.system-int-test.system :as s]
            [cmr.transmit.config :as transmit-config]
            [cmr.umm-spec.models.umm-common-models :as cm]
            [cmr.umm-spec.test.expected-conversion :as exp-conv]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Constants and general utility functions for the tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private base-url
  "We don't call to `(transmit-config/application-public-root-url)`
   due to the fact that it requires a context and we're not creating
   contexts for these integration tests, we're simply using an HTTP
   client."
   (format "%s://%s:%s/"
           (transmit-config/search-protocol)
           (transmit-config/search-host)
           (transmit-config/search-port)))

(defn- get-response
  [url-path]
  (->> url-path
       (str base-url)
       (client/get)))

(def ^:private validate-sitemap-index
  (xmlv/create-validation-fn (io/resource "sitemaps/siteindex.xsd")))

(def ^:private validate-sitemap
  (xmlv/create-validation-fn (io/resource "sitemaps/sitemap.xsd")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Functions for creating testing data
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- setup-collections
  "A utility function that generates testing collections data with the bits we
  need to test."
  []
  (let [[c1-p1 c2-p1 c3-p1
         c1-p2 c2-p2 c3-p2] (for [p ["PROV1" "PROV2"]
                                  n (range 1 4)]
                              (d/ingest-umm-spec-collection
                                p
                                (-> exp-conv/example-collection-record
                                    (assoc :ShortName (str "s" n))
                                    (assoc :EntryTitle (str "Collection Item " n)))
                                {:format :umm-json
                                 :accept-format :json}))
         [c1-p3 c2-p3 c3-p3] (for [n (range 4 7)]
                               (d/ingest-umm-spec-collection
                                 "PROV3"
                                 (-> exp-conv/example-collection-record
                                     (assoc :ShortName (str "s" n))
                                     (assoc :EntryTitle (str "Collection Item " n))
                                     (assoc :DOI (cm/map->DoiType
                                                   {:DOI (str "doi" n)
                                                    :Authority (str "auth" n)})))
                                 {:format :umm-json
                                  :accept-format :json}))]
    ;; Wait until collections are indexed so tags can be associated with them
    (index/wait-until-indexed)
    ;; Use the following to generate html links that will be matched in tests
    (let [user-token (e/login (s/context) "user")
          notag-colls [c1-p1 c1-p2 c1-p3]
          nodoi-colls [c1-p1 c2-p1 c3-p1 c1-p2 c2-p2 c3-p2]
          doi-colls [c1-p3 c2-p3 c3-p3]
          all-colls (into nodoi-colls doi-colls)
          tag-colls [c2-p1 c2-p2 c2-p3 c3-p1 c3-p2 c3-p3]
          tag (tags/save-tag
                user-token
                (tags/make-tag {:tag-key "gov.nasa.eosdis"})
                tag-colls)]
    (index/wait-until-indexed)
    ;; Sanity checks
    (assert (= (count notag-colls) 3))
    (assert (= (count nodoi-colls) 6))
    (assert (= (count doi-colls) 3))
    (assert (= (count tag-colls) 6))
    (assert (= (count all-colls) 9)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Fixtures
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def collections-fixture
  (fn [f]
    (setup-collections)
    (f)))

(use-fixtures :once (join-fixtures
                      [(ingest/reset-fixture {"provguid1" "PROV1"
                                              "provguid2" "PROV2"
                                              "provguid3" "PROV3"})
                       tags/grant-all-tag-fixture
                       collections-fixture]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest sitemap-master
  (let [response (get-response "sitemap.xml")
        body (:body response)]
    (testing "XML validation"
      (is (xmlv/valid? (validate-sitemap-index body))))
    (testing "presence and content of master sitemap.xml index file"
      (is (= (:status response) 200))
      (is (string/includes? body "/site/sitemap.xml</loc>"))
      (is (string/includes? body "/collections/directory/PROV1/gov.nasa.eosdis/sitemap.xml</loc>"))
      (is (string/includes? body "/collections/directory/PROV2/gov.nasa.eosdis/sitemap.xml</loc>"))
      (is (string/includes? body "/collections/directory/PROV3/gov.nasa.eosdis/sitemap.xml</loc>")))))

(deftest sitemap-top-level
  (let [response (get-response "site/sitemap.xml")
        body (:body response)]
    (testing "XML validation"
      (is (xmlv/valid? (validate-sitemap body))))
    (testing "presence and content of sitemap.xml file"
      (is (= (:status response) 200))
      (is (string/includes? body "/docs/search/api</loc>"))
      (is (string/includes? body "<changefreq>daily</changefreq>"))
      (is (string/includes? body "/collections/directory</loc>"))
      (is (string/includes? body "/collections/directory/eosdis</loc>"))
      (is (string/includes? body "/collections/directory/PROV1/gov.nasa.eosdis</loc>"))
      (is (string/includes? body "/collections/directory/PROV2/gov.nasa.eosdis</loc>"))
      (is (string/includes? body "/collections/directory/PROV3/gov.nasa.eosdis</loc>")))))

(deftest sitemap-provider1
  (let [provider "PROV1"
        tag "gov.nasa.eosdis"
        url-path (format
                  "site/collections/directory/%s/%s/sitemap.xml"
                  provider tag)
        response (get-response url-path)
        body (:body response)]
    (testing "XML validation"
      (is (xmlv/valid? (validate-sitemap body))))
    (testing "presence and content of sitemap.xml file"
      (is (= 200 (:status response)))
      (is (string/includes? body "<changefreq>daily</changefreq>"))
      (is (string/includes? body "concepts/C1200000015-PROV1.html</loc>"))
      (is (string/includes? body "concepts/C1200000016-PROV1.html</loc>")))
    (testing "the collections not tagged with eosdis shouldn't show up"
      (is (not (string/includes? body "C1200000014-PROV1.html</loc>"))))))

(deftest sitemap-provider2
  (let [provider "PROV2"
        tag "gov.nasa.eosdis"
        url-path (format
                  "site/collections/directory/%s/%s/sitemap.xml"
                  provider tag)
        response (get-response url-path)
        body (:body response)]
    (testing "XML validation"
      (is (xmlv/valid? (validate-sitemap body))))
    (testing "presence and content of sitemap.xml file"
      (is (= 200 (:status response)))
      (is (string/includes? body "<changefreq>daily</changefreq>"))
      (is (string/includes? body "concepts/C1200000018-PROV2.html</loc>"))
      (is (string/includes? body "concepts/C1200000019-PROV2.html</loc>")))
    (testing "the collections not tagged with eosdis shouldn't show up"
      (is (not (string/includes? body "C1200000001-PROV1.html</loc>"))))))

(deftest sitemap-provider3
  (let [provider "PROV3"
        tag "gov.nasa.eosdis"
        url-path (format
                  "site/collections/directory/%s/%s/sitemap.xml"
                  provider tag)
        response (get-response url-path)
        body (:body response)]
    (testing "presence and content of sitemap.xml file"
      (is (= 200 (:status response)))
      (is (not (string/includes? body "http://dx.doi.org/doi5</loc>")))
      (is (not (string/includes? body "http://dx.doi.org/doi5</loc>"))))
    (testing "the collections not tagged with eosdis shouldn't show up"
      (is (not (string/includes? body "C1200000014-PROV1.html</loc>"))))))
