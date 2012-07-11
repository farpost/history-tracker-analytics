(ns history-tracker-analytics.convert
  (:require [clojure.contrib.sql :as sql]
            [clojure.contrib.seq :as seq]
            [clj-time.local :as time])
  (:use [clojureql.core :only (table select where)]
        [clojure.data.json :only (json-str)]
        [clojure.contrib.prxml :only (prxml)]
        [clojure.contrib.duck-streams :only (with-out-writer)])
  (:import [java.io StringReader]
           [javax.xml.parsers DocumentBuilderFactory]
           [org.xml.sax InputSource]
           [java.text.SimpleDateFormat]
           [javax.xml.transform.dom DOMResult]))


(def df (java.text.SimpleDateFormat. "yyyy-MM-dd HH:mm:ss"))
(def do-display true)

(defn- get-int [value]
  (try (Integer/parseInt value) (catch Exception e)))

(defn- get-double [value]
  (try (Double/parseDouble value) (catch Exception e)))

(defn- get-date [value]
  (try (let [f (java.text.SimpleDateFormat. "yyyy-MM-dd'T'HH:mm:SS")]
         (->> value (.parse f) (.format f)))
       (catch Exception e)))

(defn- convert-attr [attribute]
  (let [value (get-value attribute)
        type (.getAttribute attribute "type")]
    (case type
      "string" value
      "integer" (Integer/parseInt value)
      "float" (Float/parseFloat value)
      "datetime" (get-date value)
      "boolean" (Boolean/parseBoolean value)
      value)))


;;database settings
(def remote-mysql-config "remote.ini")
(def local-mysql-config "local.ini")
(def mysql-settings {:classname "com.mysql.jdbc.Driver" :subprotocol "mysql"})
(defn- add-row [parameters row]
  (let [pair (.split row "=")]
    (->> pair
         second
         (assoc parameters (-> pair first keyword)))))
(defn- init-db [file]
    (reduce add-row mysql-settings (.split file "\n")))
(defn- configure []
  {:remote-db (init-db (slurp remote-mysql-config))
   :local-db (init-db (slurp local-mysql-config))})
(defn- get-objects [db type amount]
  (sql/with-connection db
    (sql/with-query-results rs ["select distinct user_space_id, type from history where type=? and (local_revision > 2 or user_space_revision > 2) limit ?" type amount]
      (vec rs))))
(defn- dump-object [db object]
  (sql/with-connection db
    (sql/insert-records :history2 object)))
(defn- create-object [db create-object-from object]
  (sql/with-connection db
    (sql/with-query-results rs
      [(str "select * from history "
            "where user_space_id = ? and type = ? "
            "order by user_space_revision asc, local_revision asc")
       (object :user_space_id) (object :type)]
      (println " [" (count rs) "]")
      (create-object-from rs))))



;;xml to clojure data structures
(defn get-value [attribute]
  (let [field (->> "value" (. attribute getAttribute))]
    (if (-> field empty? not)
      field
      (let [text (-> (. attribute getTextContent))]
        (if (-> text empty? not)
          text
          nil)))))
(defn get-root [text]
  (let [source (InputSource. (StringReader. text))]
    (.. DocumentBuilderFactory
        newInstance newDocumentBuilder
        (parse source) getDocumentElement)))
(defn parse-json-attribute [json-map attribute]
  (assoc json-map (. attribute getAttribute "name") (convert-attr attrubute)))
(defn parse-json-attributes [attributes]
  (let [attributes (map #(.item attributes %) (range (.getLength attributes)))]
    (reduce parse-json-attribute {} attributes)))
(defn- xml-to-json [xml]
 (-> xml get-root (. getElementsByTagName "attribute") parse-json-attributes))
(defn- join-json [history
                  {context :context state :state
                   state-type :state_type
                   state-time :state_time
                  user-space-revision :user_space_revision}]
  (let [state (xml-to-json (. state substring 54))
        context (xml-to-json (. context substring 54))]
    (let [revision (:user-space-revision user-space-revision)]
      (conj
       history
       (assoc
           {(if (nil? revision) {} {:user-space-revision revision})}
         :state-type state-type
         :state-time (->> state-time .getTime (java.util.Date.) (.format df))
         :context context
         :state state)))))


(defmulti history-merge (fn [x y] (every? map? [x y])))
(defmethod history-merge false [x y] y)
(defmethod history-merge true [x y]
  (dissoc
   (if (-> :state-type y (= "snapshot")) y
       (->>
        (merge-with history-merge x y)
        (filter (comp not nil? val))
        (into {})))
   :state-type))
(defn history-reductions [increments]
  (->> increments
       (reductions history-merge {})
       rest))


;;history to increments
(defn state-diff [a b]
  (if (every? (partial instance? java.util.Map) [a b])
    (reduce
     (fn [diff key]
       (let [av (a key) bv (b key)]
         (if (= av bv)
           diff
           (assoc diff key (if (and av bv) (state-diff av bv) bv)))))
     {} (concat (keys a) (keys b)))
    b))
(defn history-to-increments [history]
  (loop [prev {}, history history, increments []]
    (if (empty? history) increments
        (recur
         (first history)
         (rest history)
         (->> history first (state-diff prev) (conj increments))))))

(defn typed-pack [type rs]
  (if (= type "bulletin")
    {:ol rs :ul []}
    {:ul rs :ol []}))

(defn display[x] (if do-display (println x)) x)
(defn send-json[x] (json-str x :escape-unicode false))
(defn- create-json-object-from [rs]
  (-> rs
      first
      (select-keys [:type :user_space_id])
      (assoc :history (->> rs
                           (reduce join-json [])
                           history-reductions
                           history-to-increments
                           (typed-pack (-> rs first :type))
                           send-json
                           display))))
                          




(defn convert [create-object-from type amount]
  (let [{local-db :remote-db} (configure)
        objects (get-objects local-db type amount)]
    (println "objects total" objects)
    (doseq [index (-> objects count range)]
      (print index)
      (->> index
           (nth objects)
           (create-object local-db create-object-from)
           (dump-object local-db)))))

(def do-display false)
(defn convert-json [type amount]
  (convert create-json-object-from type amount))