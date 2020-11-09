(ns metabase.driver.http.query-processor
  (:refer-clojure :exclude [==])
  (:require [cheshire.core :as json]
            [clojure.walk :as walk]
            [clj-http.client :as client])
  (:import [com.jayway.jsonpath JsonPath Predicate]))

(declare compile-expression compile-function)

(defn json-path
  [query body]
  (JsonPath/read body query (into-array Predicate [])))

(defn compile-function
  [[operator & arguments]]
  (case (keyword operator)
    :count count
    :sum   #(reduce + (map (compile-expression (first arguments)) %))
    :float #(Float/parseFloat ((compile-expression (first arguments)) %))
    (throw (Exception. (str "Unknown operator: " operator)))))

(defn compile-expression
  [expr]
  (cond
    (string? expr)  (partial json-path expr)
    (number? expr)  (constantly expr)
    (vector? expr)  (compile-function expr)
    :else           (throw (Exception. (str "Unknown expression: " expr)))))

(defn aggregate
  [rows metrics breakouts]
  (let [breakouts-fns (map compile-expression breakouts)
        breakout-fn   (fn [row] (for [breakout breakouts-fns] (breakout row)))
        metrics-fns   (map compile-expression metrics)]
    (for [[breakout-key breakout-rows] (group-by breakout-fn rows)]
      (concat breakout-key (for [metrics-fn metrics-fns]
                             (metrics-fn breakout-rows))))))

(defn extract-fields
  [rows fields]
  (let [fields-fns (map compile-expression fields)]
    (for [row rows]
      (for [field-fn fields-fns]
        (field-fn row)))))

(defn field-names
  [fields]
  (vec (for [field fields]
         (if (string? field)
           {:name field}
           {:name (json/generate-string field)}))))

(defn api-query [query rows respond]
  (let [fields        (or (:fields (:result query)) (keys (first rows)))
        aggregations  (or (:aggregation (:result query)) [])
        breakouts     (or (:breakout (:result query)) [])
        raw           (and (= (count breakouts) 0) (= (count aggregations) 0))
        columns       (if raw
                        (field-names fields)
                        (field-names (concat breakouts aggregations)))
        result         (if raw
                         (extract-fields rows fields)
                         (aggregate rows aggregations breakouts))]
    (respond {:cols columns}
             result)))

(defn execute-http-request [native-query respond]
  (let [query         (if (string? (:query native-query))
                        (json/parse-string (:query native-query) keyword)
                        (:query native-query))
        body          (if (:body query) (json/generate-string (:body query)))
        result        (client/request {:method  (or (:method query) :get)
                                       :url     (:url query)
                                       :headers (:headers query)
                                       :body    body
                                       :accept  :json
                                       :as      :json
                                       :content-type "application/json"})
        rows-path     (or (:path (:result query)) "$")
        rows          (json-path rows-path (walk/stringify-keys (:body result)))]
    (api-query query rows respond)))