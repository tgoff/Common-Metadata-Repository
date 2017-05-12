(ns cmr.umm-spec.umm-to-xml-mappings.iso-smap
  "Defines mappings from UMM records into ISO SMAP XML."
  (:require
    [clojure.string :as str]
    [cmr.common.xml.gen :refer :all]
    [cmr.umm-spec.date-util :as du]
    [cmr.umm-spec.iso-keywords :as kws]
    [cmr.umm-spec.iso19115-2-util :as iso]
    [cmr.umm-spec.umm-to-xml-mappings.iso-shared.distributions-related-url :as sdru]
    [cmr.umm-spec.umm-to-xml-mappings.iso19115-2.tiling-system :as tiling]
    [cmr.umm-spec.util :as su :refer [with-default char-string]]))

(def iso-smap-xml-namespaces
  {:xmlns:gmd "http://www.isotc211.org/2005/gmd"
   :xmlns:gco "http://www.isotc211.org/2005/gco"
   :xmlns:gmi "http://www.isotc211.org/2005/gmi"
   :xmlns:gml "http://www.opengis.net/gml/3.2"
   :xmlns:gmx "http://www.isotc211.org/2005/gmx"
   :xmlns:gsr "http://www.isotc211.org/2005/gsr"
   :xmlns:gss "http://www.isotc211.org/2005/gss"
   :xmlns:gts "http://www.isotc211.org/2005/gts"
   :xmlns:srv "http://www.isotc211.org/2005/srv"
   :xmlns:xlink "http://www.w3.org/1999/xlink"
   :xmlns:xsi "http://www.w3.org/2001/XMLSchema-instance"})

(defn- generate-collection-progress
  "Returns ISO SMAP CollectionProgress element from UMM-C collection c."
  [c]
  (when-let [collection-progress (:CollectionProgress c)]
    [:gmd:MD_ProgressCode
     {:codeList "http://www.isotc211.org/2005/resources/Codelist/gmxCodelists.xml#MD_ProgressCode"
      :codeListValue (str/lower-case collection-progress)}
     collection-progress]))

(defn- generate-spatial-extent
  "Returns ISO SMAP SpatialExtent content generator instructions"
  [spatial-extent]
  (for [br (get-in spatial-extent [:HorizontalSpatialDomain :Geometry :BoundingRectangles])]
    [:gmd:geographicElement
     [:gmd:EX_GeographicBoundingBox
      [:gmd:extentTypeCode
       [:gco:Boolean 1]]
      [:gmd:westBoundLongitude
       [:gco:Decimal (:WestBoundingCoordinate br)]]
      [:gmd:eastBoundLongitude
       [:gco:Decimal (:EastBoundingCoordinate br)]]
      [:gmd:southBoundLatitude
       [:gco:Decimal (:SouthBoundingCoordinate br)]]
      [:gmd:northBoundLatitude
       [:gco:Decimal (:NorthBoundingCoordinate br)]]]]))

(defn- generate-data-dates
  "Returns ISO SMAP XML elements for the DataDates of given UMM collection.
  If no DataDates are present, use the default date value as the CREATE datetime."
  [c]
  (let [dates (or (:DataDates c) [{:Type "CREATE" :Date du/default-date-value}])]
    (for [date dates
          :let [type-code (get iso/iso-date-type-codes (:Type date))
                date-value (or (:Date date) du/default-date-value)]]
      [:gmd:date
       [:gmd:CI_Date
        [:gmd:date
         [:gco:DateTime date-value]]
        [:gmd:dateType
         [:gmd:CI_DateTypeCode {:codeList (str (:iso iso/code-lists) "#CI_DateTypeCode")
                                :codeListValue type-code} type-code]]]])))

(defn umm-c-to-iso-smap-xml
  "Returns ISO SMAP XML from UMM-C record c."
  [c]
  (xml
    [:gmd:DS_Series
     iso-smap-xml-namespaces
     [:gmd:composedOf {:gco:nilReason "inapplicable"}]
     [:gmd:seriesMetadata
      [:gmi:MI_Metadata
       [:gmd:language (char-string "eng")]
       [:gmd:contact {:xlink:href "#alaskaSARContact"}]
       [:gmd:dateStamp
        [:gco:Date "2013-01-02"]]
       [:gmd:identificationInfo
        [:gmd:MD_DataIdentification
         [:gmd:citation
          [:gmd:CI_Citation
           [:gmd:title (char-string "SMAP Level 1A Parsed Radar Instrument Telemetry")]
           (generate-data-dates c)
           [:gmd:identifier
            [:gmd:MD_Identifier
             [:gmd:code (char-string (:ShortName c))]
             [:gmd:description [:gco:CharacterString "The ECS Short Name"]]]]
           [:gmd:identifier
            [:gmd:MD_Identifier
             [:gmd:code (char-string (:Version c))]
             [:gmd:description [:gco:CharacterString "The ECS Version ID"]]]]
         (when-let [doi (:DOI c)]
           [:gmd:identifier
            [:gmd:MD_Identifier
           (when-let [authority (:Authority doi)]
             [:gmd:authority
              [:gmd:CI_Citation
               [:gmd:title [:gco:CharacterString ""]]
                [:gmd:date ""]
               [:gmd:citedResponsibleParty
                [:gmd:CI_ResponsibleParty
                 [:gmd:organisationName [:gco:CharacterString authority]]
                 [:gmd:role
                  [:gmd:CI_RoleCode {:codeList "http://www.isotc211.org/2005/resources/Codelist/gmxCodelists.xml#CI_RoleCode"
                                     :codeListValue ""} "authority"]]]]]])
             [:gmd:code [:gco:CharacterString (:DOI doi)]]
             [:gmd:codeSpace [:gco:CharacterString "gov.nasa.esdis.umm.doi"]]
             [:gmd:description [:gco:CharacterString "DOI"]]]])]]
         [:gmd:abstract (char-string (or (:Abstract c) su/not-provided))]
         [:gmd:purpose {:gco:nilReason "missing"} (char-string (:Purpose c))]
         [:gmd:status (generate-collection-progress c)]
         (kws/generate-iso-smap-descriptive-keywords
          kws/science-keyword-type (map kws/science-keyword->iso-keyword-string (:ScienceKeywords c)))
         (kws/generate-iso-smap-descriptive-keywords
          kws/location-keyword-type (map kws/location-keyword->iso-keyword-string (:LocationKeywords c)))
         [:gmd:descriptiveKeywords
          [:gmd:MD_Keywords
           (for [platform (:Platforms c)]
             [:gmd:keyword
              (char-string (kws/smap-keyword-str platform))])
           (for [instrument (distinct (mapcat :Instruments (:Platforms c)))]
             [:gmd:keyword
              (char-string (kws/smap-keyword-str instrument))])]]
         [:gmd:language (char-string (or (:DataLanguage c) "eng"))]
         [:gmd:extent
          [:gmd:EX_Extent
           (tiling/tiling-system-elements c)
           (generate-spatial-extent (:SpatialExtent c))
           (for [temporal (:TemporalExtents c)
                 rdt (:RangeDateTimes temporal)]
             [:gmd:temporalElement
              [:gmd:EX_TemporalExtent
               [:gmd:extent
                [:gml:TimePeriod {:gml:id (su/generate-id)}
                 [:gml:beginPosition (:BeginningDateTime rdt)]
                 (let [ends-at-present (:EndsAtPresentFlag temporal)]
                   [:gml:endPosition (if ends-at-present
                                       {:indeterminatePosition "now"}
                                       {})
                    (when-not ends-at-present
                      (or (:EndingDateTime rdt) ""))])]]]])
           (for [temporal (:TemporalExtents c)
                 date (:SingleDateTimes temporal)]
             [:gmd:temporalElement
              [:gmd:EX_TemporalExtent
               [:gmd:extent
                [:gml:TimeInstant {:gml:id (su/generate-id)}
                 [:gml:timePosition date]]]]])]]]]
       [:gmd:identificationInfo
        [:gmd:MD_DataIdentification
         [:gmd:citation
          [:gmd:CI_Citation
           [:gmd:title (char-string "DataSetId")]
           (generate-data-dates c)]]
         [:gmd:abstract (char-string "DataSetId")]
         (sdru/generate-browse-urls c)
         [:gmd:aggregationInfo
          [:gmd:MD_AggregateInformation
           [:gmd:aggregateDataSetIdentifier
            [:gmd:MD_Identifier
             [:gmd:code (char-string (:EntryTitle c))]]]
           [:gmd:associationType
            [:gmd:DS_AssociationTypeCode {:codeList "http://www.isotc211.org/2005/resources/Codelist/gmxCodelists.xml#DS_AssociationTypeCode"
                                          :codeListValue "largerWorkCitation"}
             "largerWorkCitation"]]]]
         (sdru/generate-publication-related-urls c)
         [:gmd:language (char-string "eng")]]]
       (sdru/generate-service-related-url (:RelatedUrls c))
       (let [related-url-distributions (sdru/generate-distributions c)]
        (when related-url-distributions
         [:gmd:distributionInfo
          [:gmd:MD_Distribution
           related-url-distributions]]))
       [:gmd:dataQualityInfo
        [:gmd:DQ_DataQuality
         [:gmd:scope
          [:gmd:DQ_Scope
           [:gmd:level
            [:gmd:MD_ScopeCode
             {:codeList (str (:iso iso/code-lists) "#MD_ScopeCode")
              :codeListValue "series"}
             "series"]]]]
         (when-let [quality (:Quality c)]
           [:gmd:report
            [:gmd:DQ_QuantitativeAttributeAccuracy
             [:gmd:evaluationMethodDescription (char-string quality)]
             [:gmd:result {:gco:nilReason "missing"}]]])]]]]]))
