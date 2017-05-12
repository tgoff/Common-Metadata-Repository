(ns cmr.system-int-test.data2.umm-spec-collection
  "Contains data generators for example based testing in system integration tests."
  (:require
    [clj-time.core :as t]
    [clj-time.format :as f]
    [cmr.common.date-time-parser :as p]
    [cmr.common.util :as util]
    [cmr.system-int-test.data2.core :as d]
    [cmr.umm-spec.additional-attribute :as aa]
    [cmr.umm-spec.models.umm-collection-models :as umm-c]
    [cmr.umm-spec.models.umm-common-models :as umm-cmn]
    [cmr.umm-spec.temporal :as umm-spec-temporal]
    [cmr.umm-spec.util :as u]))

(defn additional-attribute
  "Creates an AdditionalAttribute"
  [aa]
  (let [{:keys [Name Group Description DataType Value ParameterRangeBegin ParameterRangeEnd]} aa]
    (if (or (some? ParameterRangeBegin) (some? ParameterRangeEnd))
      (umm-cmn/map->AdditionalAttributeType
        {:Name Name
         :Group Group
         :Description (or Description "Generated")
         :DataType DataType
         :ParameterRangeBegin (aa/gen-value DataType ParameterRangeBegin)
         :ParameterRangeEnd (aa/gen-value DataType ParameterRangeEnd)})
      (umm-cmn/map->AdditionalAttributeType
        {:Name Name
         :Group Group
         :Description (or Description "Generated")
         :DataType DataType
         :Value (aa/gen-value DataType Value)}))))

(defn tiling-identification-system
  "Creates TilingIdentificationSystem specific attribute"
  [name]
  (umm-cmn/map->TilingIdentificationSystemType
    {:TilingIdentificationSystemName name
     :Coordinate1 {:MinimumValue 0.0 :MaximumValue 0.0}
     :Coordinate2 {:MinimumValue 0.0 :MaximumValue 0.0}}))

(defn tiling-identification-systems
  "Returns a sequence of tiling-identification-systems with the given names"
  [& names]
  (map tiling-identification-system names))

(defn data-dates
  "Returns DataDates field of umm-spec collection"
  [datadates]
  (for [datadate datadates]
    (umm-cmn/map->DateType {:Date (p/parse-datetime (:Date datadate))
                            :Type (:Type datadate)})))

(defn temporal-extent
  "Return a temporal extent with the given date times"
  [attribs]
  (let [{:keys [beginning-date-time ending-date-time single-date-time ends-at-present?]} attribs
        begin (when beginning-date-time (p/parse-datetime beginning-date-time))
        end (when ending-date-time (p/parse-datetime ending-date-time))
        single (when single-date-time (p/parse-datetime single-date-time))]
    (cond
      (or begin end)
      (umm-spec-temporal/temporal {:RangeDateTimes [(umm-cmn/->RangeDateTimeType begin end)]
                                   :EndsAtPresentFlag ends-at-present?})
      single
      (umm-spec-temporal/temporal {:SingleDateTimes [single]}))))

(defn science-keyword
  "Return a science keyword based on the given attributes."
  [attribs]
  (umm-cmn/map->ScienceKeywordType attribs))

(defn instrument
  "Return an instrument based on instrument attribs"
  [attribs]
  (umm-cmn/map->InstrumentType (merge {:ShortName (d/unique-str "short-name")}
                                      attribs)))

(defn instruments
  "Return a sequence of instruments with the given short names"
  [& short-names]
  (map #(umm-cmn/map->InstrumentType
          {:ShortName %
           :LongName (d/unique-str "long-name")})
       short-names))

(defn instrument-with-childinstruments
  "Return an instrument, with a sequence of child instruments"
  [short-name & child-instrument-short-names]
  (let [childinstruments (apply instruments child-instrument-short-names)]
    (umm-cmn/map->InstrumentType {:ShortName short-name
                                  :LongName (d/unique-str "long-name")
                                  :ComposedOf childinstruments})))

(defn characteristic
  "Returns a platform characteristic"
  [attribs]
  (umm-cmn/map->CharacteristicType (merge {:Name (d/unique-str "name")
                                           :Description "dummy"
                                           :DataType "dummy"
                                           :Unit "dummy"
                                           :Value "dummy"}
                                     attribs)))
(defn platform
  "Return a platform based on platform attribs"
  [attribs]
  (umm-cmn/map->PlatformType (merge {:ShortName (d/unique-str "short-name")
                                     :LongName (d/unique-str "long-name")
                                     :Type (d/unique-str "Type")}
                                    attribs)))
(defn platforms
  "Return a sequence of platforms with the given short names"
  [& short-names]
  (map #(umm-cmn/map->PlatformType
          {:ShortName %
           :LongName (d/unique-str "long-name")
           :Type (d/unique-str "Type")})
       short-names))

(defn platform-with-instruments
  "Return a platform with a list of instruments"
  [short-name & instr-short-names]
  (let [instruments (apply instruments instr-short-names)]
    (umm-cmn/map->PlatformType {:ShortName short-name
                                :LongName (d/unique-str "long-name")
                                :Type (d/unique-str "Type")
                                :Instruments instruments})))

(defn platform-with-instrument-and-childinstruments
  "Return a platform with an instrument and a list of child instruments"
  [plat-short-name instr-short-name & child-instrument-short-names]
  (let [instr-with-sensors (apply instrument-with-childinstruments instr-short-name child-instrument-short-names)]
    (umm-cmn/map->PlatformType {:ShortName plat-short-name
                                :LongName (d/unique-str "long-name")
                                :Type (d/unique-str "Type")
                                :Instruments [instr-with-sensors]})))

(defn projects
  "Return a sequence of projects with the given short names"
  [& short-names]
  (map #(umm-cmn/map->ProjectType
          {:ShortName %
           :LongName (d/unique-str "long-name")})
       short-names))

(defn project
  "Return a project with the given short name and long name"
  [project-sn long-name]
  (umm-cmn/map->ProjectType
    {:ShortName project-sn
     :LongName (d/unique-str long-name)}))

(defn data-center
  "Return archive/ processing center"
  [roles center-name]
  (umm-cmn/map->DataCenterType
    {:Roles roles
     :ShortName center-name}))

(defn related-url
  "Creates related url for online_only test"
  ([]
   (related-url nil))
  ([attribs]
   (umm-cmn/map->RelatedUrlType (merge {:URL (d/unique-str "http://example.com/file")
                                        :Description (d/unique-str "description")}
                                       attribs))))
(defn spatial
  [attributes]
  (let [{:keys [sc hsd vsds gsr orbit]} attributes]
    (umm-cmn/map->SpatialExtentType {:SpatialCoverageType sc
                                     :HorizontalSpatialDomain
                                       (when hsd (umm-cmn/map->HorizontalSpatialDomainType hsd))
                                     :VerticalSpatialDomains
                                       (when vsds (map umm-cmn/map->VerticalSpatialDomainType vsds))
                                     :GranuleSpatialRepresentation gsr
                                     :OrbitParameters (when orbit (umm-cmn/map->OrbitParametersType orbit))})))

(defn contact-person
  "Creates a Personnel record for the opendata tests."
  ([first-name last-name email]
   (contact-person first-name last-name email "dummy"))
  ([first-name last-name email role]
   (let [contact-info (when email
                        (umm-cmn/map->ContactInformationType
                          {:ContactMechanisms [(umm-cmn/map->ContactMechanismType
                                                 {:Type "Email" 
                                                  :Value email})]}))]
     (umm-cmn/map->ContactPersonType {:FirstName first-name
                                      :LastName last-name
                                      :ContactInformation contact-info
                                      :Roles [role]}))))

;; Used for testing warnings when reqired properties are missing.
(def umm-c-missing-properties
  "This is the UMM-C that misses required properties."
  {:ShortName "short"
   :Version "V1"
   :EntryTitle "The entry title V5"
   :DataDates [(umm-cmn/map->DateType {:Date (t/date-time 2012)
                                       :Type "CREATE"})]
   :Abstract "A very abstract collection"})

(def umm-c-missing-properties-dif 
  "This is the minimal valid UMM-C."
  {:DataCenters [u/not-provided-data-center]
   :ScienceKeywords [(umm-cmn/map->ScienceKeywordType
                      {:Category "EARTH SCIENCE SERVICES"
                       :Topic "DATA ANALYSIS AND VISUALIZATION"
                       :Term "GEOGRAPHIC INFORMATION SYSTEMS"})]
   :ShortName "short"
   :Version "V1"
   :EntryTitle "The entry title V5"
   :DataDates [(umm-cmn/map->DateType {:Date (t/date-time 2012)
                                       :Type "CREATE"})]
   :Abstract "A very abstract collection"})

(def umm-c-missing-properties-dif10 
  "This is the minimal valid UMM-C."
  {:Platforms [(umm-cmn/map->PlatformType
                 {:ShortName "A340-600" :LongName "Airbus A340-600"})]
   :DataCenters [u/not-provided-data-center]
   :ScienceKeywords [(umm-cmn/map->ScienceKeywordType
                      {:Category "EARTH SCIENCE SERVICES"
                       :Topic "DATA ANALYSIS AND VISUALIZATION"
                       :Term "GEOGRAPHIC INFORMATION SYSTEMS"})]
   :SpatialExtent (umm-cmn/map->SpatialExtentType {:GranuleSpatialRepresentation "NO_SPATIAL"})
   :ShortName "short"
   :Version "V1"
   :EntryTitle "The entry title V5"
   :DataDates [(umm-cmn/map->DateType {:Date (t/date-time 2012)
                                       :Type "CREATE"})]
   :Abstract "A very abstract collection"
   :TemporalExtents [(umm-cmn/map->TemporalExtentType {:SingleDateTimes [(t/date-time 2012)]})]})

(def minimal-umm-c
  "This is the minimal valid UMM-C."
  {:Platforms [(umm-cmn/map->PlatformType
                 {:ShortName "A340-600" :LongName "Airbus A340-600"})]
   :ProcessingLevel (umm-c/map->ProcessingLevelType {:Id "3"})
   :DataCenters [u/not-provided-data-center]
   :ScienceKeywords [(umm-cmn/map->ScienceKeywordType
                      {:Category "EARTH SCIENCE SERVICES"
                       :Topic "DATA ANALYSIS AND VISUALIZATION"
                       :Term "GEOGRAPHIC INFORMATION SYSTEMS"})]
   :SpatialExtent (umm-cmn/map->SpatialExtentType {:GranuleSpatialRepresentation "NO_SPATIAL"})
   :ShortName "short"
   :Version "V1"
   :EntryTitle "The entry title V5"
   :CollectionProgress "COMPLETE"
   :DataDates [(umm-cmn/map->DateType {:Date (t/date-time 2012)
                                       :Type "CREATE"})]
   :Abstract "A very abstract collection"
   :TemporalExtents [(umm-cmn/map->TemporalExtentType {:SingleDateTimes [(t/date-time 2012)]})]})

(defn collection-missing-properties
  "Returns a UmmCollection missing reqired properties"
  ([]
   (collection-missing-properties {}))
  ([attribs]
   (umm-c/map->UMM-C (merge umm-c-missing-properties attribs))))

(defn collection-missing-properties-dif
  "Returns a UmmCollection missing reqired properties"
  ([]
   (collection-missing-properties {}))
  ([attribs]
   (umm-c/map->UMM-C (merge umm-c-missing-properties-dif attribs))))

(defn collection-missing-properties-dif10
  "Returns a UmmCollection missing reqired properties"
  ([]
   (collection-missing-properties {}))
  ([attribs]
   (umm-c/map->UMM-C (merge umm-c-missing-properties-dif10 attribs))))

(defn collection
  "Returns a UmmCollection from the given attribute map."
  ([]
   (collection {}))
  ([attribs]
   (umm-c/map->UMM-C (merge minimal-umm-c attribs)))
  ([index attribs]
   (umm-c/map->UMM-C
    (merge
     minimal-umm-c
     {:ShortName (str "Short Name " index)
      :Version (str "V" index)
      :EntryTitle (str "Entry Title " index)}
     attribs))))

(defn collection-concept
  "Returns the collection for ingest with the given attributes"
  ([attribs]
   (collection-concept attribs :echo10))
  ([attribs concept-format]
   (let [{:keys [provider-id native-id]} attribs]
     (-> attribs
         collection
         (assoc :provider-id provider-id :native-id native-id)
         (d/umm-c-collection->concept concept-format)))))
