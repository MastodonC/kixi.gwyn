(ns witan.gwyn.gwyn
  (:require [witan.workspace-api :refer [defworkflowfn
                                         definput
                                         defworkflowoutput]]
            [schema.core :as s]
            [witan.gwyn.schemas :as sc]
            [clojure.core.matrix.dataset :as ds]
            [clojure.core.matrix :as mx]
            [witan.datasets :as wds]
            [witan.datasets.stats :as wst]
            [witan.gwyn.utils :as u]
            [kixi.stats.core :refer [mean standard-deviation]]
            [clojure.data.json :as json]
            [clojure.set :as clj-set]))

(definput fire-station-lookup-table-1-0-0
  {:witan/name :fire-risk/fire-station-lookup-table
   :witan/version "1.0.0"
   :witan/key :fire-station-lookup-table
   :witan/schema sc/FireStations})

(definput lfb-historic-incidents-1-0-0
  {:witan/name :fire-risk/lfb-historic-incidents
   :witan/version "1.0.0"
   :witan/key :lfb-historic-incidents
   :witan/schema sc/LfbHistoricIncidents})

(definput historical-fire-risk-scores-1-0-0
  {:witan/name :fire-risk/historical-fire-risk-scores
   :witan/version "1.0.0"
   :witan/key :historical-fire-risk-scores
   :witan/schema sc/HistoricalFireRiskScores})

(definput property-comparison-1-0-0
  {:witan/name :fire-risk/property-comparison
   :witan/version "1.0.0"
   :witan/key :property-comparison
   :witan/schema sc/PropertyComparison})

(defworkflowfn extract-fire-station-geo-data-1-0-0
  {:witan/name :fire-risk/extract-fire-station-geo-data
   :witan/version "1.0.0"
   :witan/input-schema {:fire-station-lookup-table sc/FireStations}
   :witan/param-schema {:fire-station s/Str}
   :witan/output-schema {:fire-station-geo-data sc/FireStationGeoData}}
  [{:keys [fire-station-lookup-table]} {:keys [fire-station]}]
  {:fire-station-geo-data
   (-> fire-station-lookup-table
       (wds/select-from-ds {:station fire-station})
       (wds/subset-ds :cols [:radius :lat :long]))})

(def google-places-api "https://maps.googleapis.com/maps/api/place/nearbysearch/json?location=")

(defn api-str [loc k]
  (try (str google-places-api loc "&key=" (System/getenv k))
       (catch Exception e (str "env var does not exist:" e))))

(defn fetch-url [api-call]
  (try {:response (slurp api-call)}
       (catch Exception e {:error (str "Fire station does not exist. Incorrect URL: " e)})))

(defn parse-google-places [api-call]
  (let [{:keys [response error]} (fetch-url api-call)]
    (if response
      (-> response
          json/read-str
          (get "results"))
      (throw (Exception. error)))))

(defn collect-property [json-entry]
  (hash-map :address (get json-entry "vicinity")
            :type (set (get json-entry "types"))
            :id (get json-entry "id")
            :name (get json-entry "name")))

(def unwanted-property-types
  #{"administrative_area_level_1" "administrative_area_level_2"
    "administrative_area_level_3" "administrative_area_level_4"
    "administrative_area_level_5" "colloquial_area"
    "country" "establishment" "finance" "floor" "food"
    "general_contractor" "grocery_or_supermarket" "geocode"
    "health" "intersection" "locality" "natural_feature"
    "neighborhood" "place_of_worship" "political" "point_of_interest"
    "post_box" "postal_code" "postal_code_prefix" "postal_code_suffix"
    "postal_town" "premise" "room" "route" "street_address" "street_number"
    "sublocality" "sublocality_level_4" "sublocality_level_5"
    "sublocality_level_3" "sublocality_level_2"
    "sublocality_level_1" "subpremise"})

(defn remove-unwanted-types [ds coll]
  (ds/replace-column ds :type (apply (partial map (fn [t]
                                                    (clj-set/difference t (set coll))))
                                     (map #(ds/column ds %) [:type]))))

(defn clean-properties-dataset
  [properties-dataset]
  (-> properties-dataset
      (ds/select-columns [:address :name :type])
      (remove-unwanted-types unwanted-property-types)
      (wds/select-from-ds {:type {:nin #{#{}}}})))

(defworkflowfn list-commercial-properties-1-0-0
  {:witan/name :fire-risk/list-commercial-properties
   :witan/version "1.0.0"
   :witan/input-schema {:fire-station-geo-data sc/FireStationGeoData}
   :witan/output-schema {:commercial-properties sc/CommercialProperties}}
  [{:keys [fire-station-geo-data]} _]
  {:commercial-properties
   (let [latitude (first (ds/column fire-station-geo-data :lat))
         longitude (first (ds/column fire-station-geo-data :long))
         radius (first (ds/column fire-station-geo-data :radius))
         api-call (str latitude "," longitude "&radius=" radius)
         url (api-str api-call "PLACES_API_KEY")]
     (->> (parse-google-places url)
          (map collect-property)
          (into [])
          ds/dataset
          clean-properties-dataset))})

(defworkflowfn group-commercial-properties-type-1-0-0
  "Takes in LFB historical incidents data for fires in non-residential properties.
   Returns the number of fires, average and standard deviation for the number of pumps
   attending the fire at that type of non-residential property."
  {:witan/name :fire-risk/group-commercial-properties-type
   :witan/version "1.0.0"
   :witan/input-schema {:lfb-historic-incidents sc/LfbHistoricIncidents}
   :witan/output-schema {:commercial-properties-by-type sc/CommercialPropertyTypes}}
  [{:keys [lfb-historic-incidents]} _]
  {:commercial-properties-by-type
   (->> (wds/group-ds lfb-historic-incidents :property-type)
        (mapv (fn [[map-key ds]]
                (let [n (mx/row-count ds)
                      coll-pumps-attending (u/make-coll
                                            (wds/subset-ds ds :cols :num-pumps-attending))]
                  (merge map-key
                         {:num-fires n
                          :avg-pumps-attending
                          (u/average coll-pumps-attending)
                          :sd-pumps-attending (wst/standard-deviation
                                               coll-pumps-attending)}))))
        ds/dataset)})

(defn adjust-avg-num-pumps
  "Takes in the properties dataset. Adjust the average number of pumps
   attending the fire depending on the value of the standard deviation."
  [properties-data]
  (-> properties-data
      (wds/add-derived-column :adjusted-avg-pumps [:sd-pumps-attending :avg-pumps-attending]
                              (fn [sd avg] (if (> sd 2) ;; value to be refined
                                             (+ avg (u/safe-divide sd 2))
                                             avg)))
      (ds/select-columns [:property-type :num-fires :adjusted-avg-pumps :sd-pumps-attending])))

(defn sort-by-pumps-and-fires
  "Takes in the properties dataset with the average number of pumps adjusted.
   Sort the property types by average number of pumps and by number of fires."
  [adjusted-properties-data]
  (->> (ds/row-maps adjusted-properties-data)
       (sort-by (juxt :adjusted-avg-pumps :num-fires))
       ds/dataset))

(defn assign-generic-scores
  "Takes in the properties dataset sorted by pumps and fires.
   Use the sorting order to assign a score to each property type."
  [sorted-properties-data]
  (let [range-data (range 1 (-> sorted-properties-data mx/row-count inc))]
    (-> sorted-properties-data
        (ds/add-column :generic-fire-risk-score range-data)
        (ds/select-columns [:property-type :generic-fire-risk-score]))))

(defworkflowfn generic-commercial-properties-fire-risk-1-0-0
  "Takes in a dataset with number of fires, average and standard deviation for the
   number of pumps attending the fire for each type of non-residential property.
   Returns a fire risk score for each type of non-residential property."
  {:witan/name :fire-risk/generic-commercial-properties-fire-risk
   :witan/version "1.0.0"
   :witan/input-schema {:commercial-properties-by-type sc/CommercialPropertyTypes}
   :witan/output-schema {:generic-fire-risks sc/GenericFireRisk}}
  [{:keys [commercial-properties-by-type]} _]
  {:generic-fire-risks (-> commercial-properties-by-type
                           adjust-avg-num-pumps
                           sort-by-pumps-and-fires
                           assign-generic-scores)})

(defn lookup-score
  [lookup-ds types]
  (let [scores (map #(-> lookup-ds
                         (wds/select-from-ds {:google-property-type
                                              {:eq %}})
                         (wds/subset-ds :cols :generic-fire-risk-score))
                    types)]
    (u/average (flatten scores))))

(defworkflowfn associate-risk-score-to-commercial-properties-1-0-0
  {:witan/name :fire-risk/associate-risk-score-to-commercial-properties
   :witan/version "1.0.0"
   :witan/input-schema {:generic-fire-risks sc/GenericFireRisk
                        :commercial-properties sc/CommercialProperties
                        :property-comparison sc/PropertyComparison}
   :witan/output-schema {:commercial-properties-with-scores sc/CommercialPropertiesWithScores}}
  [{:keys [generic-fire-risks commercial-properties property-comparison]} _]
  {:commercial-properties-with-scores
   (let [comparison-scores (wds/join (ds/rename-columns generic-fire-risks
                                                        {:property-type :lfb-property-type})
                                     property-comparison
                                     [:lfb-property-type])]
     (-> commercial-properties
         (wds/add-derived-column :risk-score
                                 [:type] (fn [t] (lookup-score comparison-scores t)))
         (ds/add-column :date-last-risk-assessed (repeat (mx/row-count commercial-properties)
                                                         nil))
         (ds/select-columns [:address :name :risk-score :date-last-risk-assessed])))})

(defworkflowfn join-historical-and-new-scores-1-0-0
  {:witan/name :fire-risk/join-historical-and-new-scores
   :witan/version "1.0.0"
   :witan/input-schema {:commercial-properties-with-scores sc/CommercialPropertiesWithScores
                        :historical-fire-risk-scores sc/HistoricalFireRiskScores}
   :witan/output-schema {:historical-and-new-scores sc/HistoricalFireRiskScores}}
  [{:keys [commercial-properties-with-scores historical-fire-risk-scores]} _]
  {:historical-and-new-scores commercial-properties-with-scores})

(defworkflowfn update-score-1-0-0
  {:witan/name :fire-risk/update-score
   :witan/version "1.0.0"
   :witan/input-schema {:historical-and-new-scores sc/HistoricalFireRiskScores}
   :witan/output-schema {:new-fire-risk-scores sc/NewFireRiskScores}}
  [{:keys [historical-and-new-scores]} _]
  {:new-fire-risk-scores historical-and-new-scores})

(defworkflowoutput output-new-fire-risk-scores-1-0-0
  {:witan/name :fire-risk/output-new-fire-risk-scores
   :witan/version "1.0.0"
   :witan/input-schema {:new-fire-risk-scores sc/NewFireRiskScores}}
  [{:keys [new-fire-risk-scores]} _]
  {:new-fire-risk-scores new-fire-risk-scores})
