(ns cmr.system-int-test.search.collection-doi-search-test
  "Integration test for CMR collection search by doi"
  (:require
    [clojure.test :refer :all]
    [cmr.common.util :as util :refer [are3]]
    [cmr.system-int-test.data2.core :as d]
    [cmr.system-int-test.utils.index-util :as index]
    [cmr.system-int-test.utils.ingest-util :as ingest]
    [cmr.system-int-test.utils.search-util :as search]
    [cmr.umm-spec.models.umm-common-models :as cm]
    [cmr.umm-spec.test.expected-conversion :as exp-conv]))

(use-fixtures :each (ingest/reset-fixture {"provguid1" "PROV1"}))

(deftest search-by-doi
  (let [coll1 (d/ingest-umm-spec-collection "PROV1"
                        (-> exp-conv/example-collection-record
                            (assoc :ShortName "CMR3674SN1")
                            (assoc :EntryTitle "CMR3674ET1")
                            (assoc :DOI (cm/map->DoiType
                                         {:DOI "doi1" :Authority "auth1"})))
                        {:format :umm-json
                         :accept-format :json})
        coll2 (d/ingest-umm-spec-collection "PROV1"
                        (-> exp-conv/example-collection-record
                            (assoc :ShortName "CMR3674SN2")
                            (assoc :EntryTitle "CMR3674ET2")
                            (assoc :DOI (cm/map->DoiType
                                         {:DOI "doi2" :Authority "auth2"})))
                        {:format :umm-json
                         :accept-format :json})]
    (index/wait-until-indexed)

    (testing "search collections by doi"
      (are3 [items doi options]
            (let [params (merge {:doi doi}
                                (when options
                                  {"options[doi]" options}))]
              (d/refs-match? items (search/find-refs :collection params)))
       "search collections with doi1"
       [coll1] "DoI1" {}

       "search collections with auth1 returns nothing"
       [] "auth1" {}

       "search for collections with either doi1 or doi2"
       [coll1 coll2] ["Doi1" "doI2"] {}

       "search for collections with either doi1 or doi2"
       [coll1 coll2] ["Doi*"] {:pattern true}

       "search for collections with both doi1 and doi2 returns nothing"
       [] ["Doi1" "doI2"] {:and true}))))
