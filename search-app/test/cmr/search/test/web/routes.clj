(ns cmr.search.test.web.routes
  (:require [clojure.test :refer :all]
            [cmr.search.web.routes :as r])
  (:use ring.mock.request))

(def ^:private web (#'cmr.search.web.routes/build-routes
                     {:public-conf {:protocol "https"
                                    :relative-root-url "/search"}}))

(deftest find-query-str-mixed-arity-param
  (testing "find-query-str-mixed-arity-param finds parameter with mixed arity correctly"
    (are [query-str found]
         (= found
            (r/find-query-str-mixed-arity-param query-str))
         "foo=1&foo[bar]=2" "foo"
         "foo[]=1&foo[bar]=2" "foo"
         "foo=0&foo[]=1&foo[]=2" nil
         "foo[bar]=1&foo[x]=2" nil
         "foo=1&options[foo][pattern]=true" nil
         "foo[]=1&options[foo][pattern]=true" nil
         "foo=1&bar_foo[baz]=2" nil
         "foo[baz]=1&bar_foo=2" nil)))