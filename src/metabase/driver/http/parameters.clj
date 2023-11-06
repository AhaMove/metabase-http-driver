(ns metabase.driver.http.parameters
  (:require [clojure
             [string :as str]
             [walk :as walk]]
            [clojure.tools.logging :as log]
            [java-time :as t]
            [metabase.driver.common.parameters :as params]
            [metabase.driver.common.parameters
              [dates :as date-params]
              [parse :as parse]
              [values :as values]
             ]
            [metabase.query-processor
             [error-type :as error-type]]
            [metabase.util :as u]
            [metabase.util
             [date-2 :as u.date]
             [i18n :refer [tru]]])
  (:import java.time.temporal.Temporal
           [metabase.driver.common.parameters Date]))

(defn- ->utc-instant [t]
  (t/instant
   (condp instance? t
     java.time.LocalDate     (t/zoned-date-time t (t/local-time "00:00") (t/zone-id "UTC"))
     java.time.LocalDateTime (t/zoned-date-time t (t/zone-id "UTC"))
     t)))

(defn- param-value->str
  [{special-type :special_type, :as field} x]
  (println "param-value->str" x)
  (cond
      ;; sequences get converted to `$in`
    (sequential? x)
    (format "%s" (str/join ", " (map (partial param-value->str field) x)))

      ;; Date = the Parameters Date type, not an java.util.Date or java.sql.Date type
      ;; convert to a `Temporal` instance and recur
    (instance? Date x)
    (param-value->str field (u.date/parse (:s x)))

    (and (instance? Temporal x)
         (isa? special-type :type/UNIXTimestampSeconds))
    (long (/ (t/to-millis-from-epoch (->utc-instant x)) 1000))

    (and (instance? Temporal x)
         (isa? special-type :type/UNIXTimestampMilliseconds))
    (t/to-millis-from-epoch (->utc-instant x))

      ;; convert temporal types to ISODate("2019-12-09T...") (etc.)
    (instance? Temporal x)
    (format "%s" (u.date/format x))

      ;; for everything else, splice it in as its string representation
    :else
    x))

(defn- field->name [field]
  (:name field))

(defn- substitute-one-field-filter-date-range [{field :field, {value :value} :value}]
  (let [{:keys [start end]} (date-params/date-string->range value {:inclusive-end? false})
        start-condition     (when start
                              (format "{%s: {$gte: %s}}" (field->name field) (param-value->str field (u.date/parse start))))
        end-condition       (when end
                              (format "{%s: {$lt: %s}}" (field->name field) (param-value->str field (u.date/parse end))))]
    (if (and start-condition end-condition)
      (format "{$and: [%s, %s]}" start-condition end-condition)
      (or start-condition
          end-condition))))

  ;; Field filter value is either params/no-value (handled in `substitute-param`, a map with `:type` and `:value`, or a
  ;; sequence of those maps.
(defn- substitute-one-field-filter [{field :field, {param-type :type, value :value} :value, :as field-filter}]
    ;; convert relative dates to approprate date range representations
  (cond
    (date-params/not-single-date-type? param-type)
    (substitute-one-field-filter-date-range field-filter)

      ;; a `date/single` like `2020-01-10`
    (and (date-params/date-type? param-type)
         (string? value))
    (let [t (u.date/parse value)]
      (format "{$and: [%s, %s]}"
              (format "{%s: {$gte: %s}}" (field->name field) (param-value->str field t))
              (format "{%s: {$lt: %s}}"  (field->name field) (param-value->str field (u.date/add t :day 1)))))

    :else
    (format "%s" (param-value->str field value))))

(defn- substitute-field-filter [{field :field, {:keys [value]} :value, :as field-filter}]
  (if (sequential? value)
    (format "%s" (param-value->str field value))
    (substitute-one-field-filter field-filter)))

(defn- substitute-param [param->value [acc missing] in-optional? {:keys [k], :as param}]
  (println "param" param)
  (let [v (get param->value k)]
    (cond
      (not (contains? param->value k))
      [acc (conj missing k)]

      (params/FieldFilter? v)
      (let [no-value? (= (:value v) params/no-value)]
        (cond
            ;; no-value field filters inside optional clauses are ignored and omitted entirely
          (and no-value? in-optional?) [acc (conj missing k)]
            ;; otherwise replace it with a {} which is the $match equivalent of 1 = 1, i.e. always true
          no-value?                    [(conj acc "{}") missing]
          :else                        [(conj acc (substitute-field-filter v))
                                        missing]))

      (= v params/no-value)
      [acc (conj missing k)]

      :else
      [(conj acc (param-value->str nil v)) missing])))

(declare substitute*)

(defn- substitute-optional [param->value [acc missing] {subclauses :args}]
  (let [[opt-acc opt-missing] (substitute* param->value subclauses true)]
    (if (seq opt-missing)
      [acc missing]
      [(into acc opt-acc) missing])))

(defn- substitute*
  "Returns a sequence of `[[replaced...] missing-parameters]`."
  [param->value xs in-optional?]
  (reduce
   (fn [[acc missing] x]
     (cond
       (string? x)
       [(conj acc x) missing]

       (params/Param? x)
       (substitute-param param->value [acc missing] in-optional? x)

       (params/Optional? x)
       (substitute-optional param->value [acc missing] x)

       :else
       (throw (ex-info (tru "Don''t know how to substitute {0} {1}" (.getName (class x)) (pr-str x))
                       {:type error-type/driver}))))
   [[] nil]
   xs))

(defn- substitute [param->value xs]
  (let [[replaced missing] (substitute* param->value xs false)]
    (when (seq missing)
      (throw (ex-info (tru "Cannot run query: missing required parameters: {0}" (set missing))
                      {:type error-type/invalid-query})))
    (when (seq replaced)
      (str/join replaced))))

(defn- parse-and-substitute [param->value x]
  (if-not (string? x)
    x
    (u/prog1 (substitute param->value (parse/parse x))
             (when-not (= x <>)
               (log/debug (tru "Substituted {0} -> {1}" (pr-str x) (pr-str <>)))))))

(defn substitute-native-parameters
  [_ inner-query]
  (let [param->value (values/query->params-map inner-query)]
    (println "parse-inner-query" inner-query)
    (update inner-query :query (partial walk/postwalk (partial parse-and-substitute param->value)))))