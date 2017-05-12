(ns cmr.search.site.data
  "The functions of this namespace are specifically responsible for generating
  data structures to be consumed by site page templates.

  Of special note: this namespace and its sibling `page` namespace are only
  ever meant to be used in the `cmr.search.site` namespace, particularly in
  support of creating site routes for access in a browser.

  Under no circumstances should `cmr.search.site.data` be accessed from outside
  this context; the data functions defined herein are specifically for use
  in page templates, structured explicitly for their needs."
  (:require
   [clj-time.core :as clj-time]
   [clojure.string :as string]
   [cmr.common-app.services.search.query-execution :as query-exec]
   [cmr.search.services.query-service :as query-svc]
   [cmr.transmit.config :as config]
   [cmr.transmit.metadata-db :as mdb]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Data utility functions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn get-tag-short-name
  "A utility function used to present a more human-friendly version of a tag."
  [tag]
  (case tag
    "gov.nasa.eosdis" "EOSDIS"))

(defn provider-data
  "Create a provider data structure suitable for template iteration to
  generate links.

  Note that the given tag will be used to filter provider collections data
  that is used on the destination page."
  [tag provider-data]
  {:id (:provider-id provider-data)
   :name (:provider-id provider-data)
   :tag tag})

(defn get-doi
  "Extract the DOI information from a collection item."
  [item]
  (get-in item [:umm "DOI"]))

(defn has-doi?
  "Determine whether a collection item has a DOI entry."
  [item]
  (some? (get-doi item)))

(defn doi-link
  "Given DOI umm data of the form `{:doi <STRING>}`, generate a landing page
  link."
  [doi-data]
  (format "http://dx.doi.org/%s" (doi-data "DOI")))

(defn cmr-link
  "Given a CMR host and a concept ID, return the collection landing page for
  the given id."
  [cmr-base-url concept-id]
  (format "%sconcepts/%s.html" cmr-base-url concept-id))

(defn make-href
  "Create the `href` part of a landing page link."
  [cmr-base-url item]
  (if (has-doi? item)
    (doi-link (get-doi item))
    (cmr-link cmr-base-url (get-in item [:meta :concept-id]))))

(defn get-entry-title
  "Get a collection item's long name."
  [item]
  (get-in item [:umm "EntryTitle"]))

(defn get-short-name
  "Get a collection item's short name, if it exists."
  [item]
  (get-in item [:umm "ShortName"]))

(defn make-text
  "Create the `text` part of a landing page link."
  [item]
  (format "%s (%s)" (get-entry-title item) (get-short-name item)))

(defn make-link
  "Given a single item from a query's collections, generate an appropriate
  landing page link.

  A generated link has the form `{:href ... :text ...}`."
  [cmr-base-url item]
  {:href (make-href cmr-base-url item)
   :text (make-text item)})

(defn make-links
  "Given a collection from an elastic search query, generate landing page
  links appropriate for the collection."
  [cmr-base-url coll]
  (map (partial make-link cmr-base-url) coll))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Page data functions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn base-page
  "Data that all pages have in common."
  [context]
  {:base-url (config/application-public-root-url context)
   :datestamp (str (clj-time/today))})

(defn get-index
  "Return the data for the index page (none for now)."
  [context]
  (base-page context))

(defn get-directory-links
  "Provide the list of links that will be rendered on the top-level directory
  page."
  [context]
  (merge
   (base-page context)
   {:links [{:href "site/collections/directory/eosdis"
             :text "EOSDIS Collections"}]}))

(defn get-eosdis-directory-links
  "Generate the data necessary to render EOSDIS directory page links."
  [context]
  (let [providers (mdb/get-providers context)]
    (merge
      (base-page context)
      {:providers (map (partial provider-data "gov.nasa.eosdis") providers)})))

(defn get-provider-tag-landing-links
  "Generate the data necessary to render EOSDIS landing page links."
  ([context provider-id tag]
    (get-provider-tag-landing-links context provider-id tag (constantly true)))
  ([context provider-id tag filter-fn]
   (let [query (query-svc/make-concepts-query
                context
                :collection
                {:tag-key tag
                 :provider provider-id
                 :result-format {:format :umm-json-results}})
         coll (:items (query-exec/execute-query context query))]
     (merge (base-page context)
            {:provider-name provider-id
             :provider-id provider-id
             :tag-name (get-tag-short-name tag)
             :links (filter filter-fn
                            (make-links
                             (config/application-public-root-url context)
                             coll))}))))

(defn get-provider-tag-sitemap-landing-links
  "Generate the data necessary to render EOSDIS landing page links that will
  be included in a sitemap.xml file.

  Note that generally the sitemap spec does not support cross-site inclusions."
  [context provider-id tag]
  (get-provider-tag-landing-links
   context
   provider-id
   tag
   #(string/includes?
     (str %)
     (config/application-public-root-url context))))
