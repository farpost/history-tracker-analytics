(ns history-tracker-analytics.convert
  (:gen-class :main true)
  (:require [clojure.contrib.sql :as sql]
            [clojure.contrib.seq :as seq]
            [clj-time.local :as time]
            [clojure.contrib.profile :as profile])
  (:use [clojureql.core :only (table select where)]
        [clojure.data.json :only (json-str read-json)]
        [clojure.contrib.prxml :only (prxml)]
        [clojure.contrib.duck-streams :only (with-out-writer)]
        [clojure.contrib.profile :only (prof)])
  (:import [java.io StringReader]
           [javax.xml.parsers DocumentBuilderFactory]
           [org.xml.sax InputSource]
           [java.text.SimpleDateFormat]
           [javax.xml.transform.dom DOMResult]))

(def df (java.text.SimpleDateFormat. "yyyy-MM-dd HH:mm:ss"))
(def df3 (java.text.SimpleDateFormat. "yyy-MM-dd'T'HH:mm:ss"))
(def do-display true)

(defn- get-int [value]
  (try (Integer/parseInt value) (catch Exception e)))


(defn- get-double [value]
  (try (Double/parseDouble value) (catch Exception e)))

(defn- get-date [value]
;;  (try (->> value (.parse df) (.format df))
;;       (catch Exception e
         (try (->> value (.parse df3) (.format df))
                               (catch Exception e value)))

(def convert-attr)

(defn- get-array [attribute]
  (let [elements (-> attribute
                     (.getElementsByTagName "array")
                     (.item 0)
                     (.getElementsByTagName "element"))]
    (map #(->> % (.item elements) convert-attr) (range (.getLength elements)))))
;;xml to clojure data structures
(defn get-value [attribute]
  (let [field (->> "value" (. attribute getAttribute))]
    (if (-> field empty? not)
      field
      (let [text (-> (. attribute getTextContent))]
        (if (-> text empty? not)
          text
          "")))))

(defn- convert-attr [attribute]
 (let [value (get-value attribute)
       type (.getAttribute attribute "type")]
   (case type
     "string" (get-date value)
      "integer" (Integer/parseInt value)
      "float" (Float/parseFloat value)
      "datetime" (get-date value)
      "array" (get-array attribute)
      "boolean" (Boolean/parseBoolean value)
      "removed" nil
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

(def request
  (str "select * from history where type=? and user_space_id between ? and ?"))

(defn- get-objects [db type start end]
  (sql/with-connection db
    (sql/with-query-results rs
      [request type start (dec end)]
      (->> rs
           (sort-by :user_space_id #(compare %2 %1))
           (sort-by :local_revision #(compare %2 %1))
           (reduce
            (fn [map entry]
              (update-in map [[(:type entry) (:user_space_id entry)]] #(cons entry %)))
            {})
           vals))))

(defn- dump-object [db objects]
  (sql/with-connection db
    (apply sql/insert-values :history2 [:type :user_space_id :history] objects)))
(defn- create-object [db create-object-from object]
  (sql/with-connection db
    (sql/with-query-results rs
      [(str "select * from history "
            "where user_space_id = ? and type = ? "
            "order by user_space_revision asc, local_revision asc")
       (object :user_space_id) (object :type)]
      (create-object-from rs))))


(defn create-document-builder []
  (.newDocumentBuilder
   (doto (DocumentBuilderFactory/newInstance)
     (.setNamespaceAware false)
     (.setValidating false)
     (.setFeature "http://xml.org/sax/features/namespaces" false)
     (.setFeature "http://xml.org/sax/features/validation" false)
     (.setFeature "http://apache.org/xml/features/nonvalidating/load-dtd-grammar" false)
     (.setFeature "http://apache.org/xml/features/nonvalidating/load-external-dtd" false))))

(def ^:dynamic document-builder (create-document-builder))

(defn get-root [text document-builder]
  (let [source
        (InputSource. (StringReader. text))]
    (.. document-builder (parse source) getDocumentElement)))
(defn parse-json-attribute [json-map attribute]
  (assoc json-map (. attribute getAttribute "name") (convert-attr attribute)))
(defn parse-json-attributes [attributes]
  (let [attributes (map #(.item attributes %) (range (.getLength attributes)))]
    (reduce parse-json-attribute (array-map) attributes)))


(defn- xml-to-json [document-builder xml]
  (->
   xml
   (get-root document-builder)
   (. getElementsByTagName "attribute") parse-json-attributes))

(defn- join-json [history
                  {context :context state :state
                   state-type :state_type
                   state-time :state_time
                  user-space-revision :user_space_revision}]
(let [state (xml-to-json document-builder state)
      context (xml-to-json document-builder context)]
  (merge
   history
   (assoc
       (if (nil? user-space-revision) {} {:user-space-revision user-space-revision})
     :state-type state-type
     :state-time (->> state-time .getTime (java.util.Date.) (.format df))
     :context context
     :state state))))


(defmulti history-merge (fn [x y] (every? map? [x y])))
(defmethod history-merge false [x y] y)
(defmethod history-merge true [x y]
  (dissoc
   (if (-> :state-type y (= "snapshot")) y
       (->>
        (merge-with history-merge x y)
        (filter (comp not nil? val))
        (into (array-map))))
   :state-type))
(defn history-reductions [increments]
  (->> increments
       (reductions history-merge (array-map))
       rest))


;;history to increments
(defn state-diff [a b]
  (if (not-every? (partial instance? java.util.Map) [a b])
    b
    (reduce
     (fn [diff key]
       (let [av (a key) bv (b key)]
         (if (= av bv)
           diff
           (assoc diff key (if (and av bv) (state-diff av bv) bv)))))
     (array-map) (concat (keys a) (keys b)))))
(defn history-to-increments [history]
  (loop [prev (array-map), history history, increments []]
    (if (empty? history) increments
        (recur
         (first history)
         (rest history)
         (->> history first (state-diff prev) (conj increments))))))

(defn typed-pack [type rs]
  (if (= type "bulletin")
    {:ol rs :ul []}
    {:ul rs :ol []}))

(defn send-json[x] (json-str x :escape-unicode false))
(defn- create-json-object-from [rs]
  (let [a (first rs)]
    [(a :type) (a :user_space_id)
     (->>
      (reduce join-json [] rs)
      history-reductions
      history-to-increments
      (typed-pack (a :type))
      send-json)]))


(defn convert [create-object-from type start end]
  (let [{db :remote-db} (configure)
        objects (get-objects db type start end)
        new-objects (vec (map create-object-from objects))]
    (dump-object db new-objects)))

(defn parts[start step times]
  (->> times
       range
       (map (partial * step))
       (map (partial + start))
       (map #(list % (+ % step)))))

(defn- delete-objects [db]
  (sql/with-connection db
    (sql/delete-rows
     :history2
     ["id > 0"])))

(def do-display false)
(def ^:dynamic df3)
(def ^:dynamic df)
(defn convert-json [type [start end]]
  (println type start end)
(time  (binding [document-builder (create-document-builder)
            df (java.text.SimpleDateFormat. "yyyy-MM-dd HH:mm:ss")
            df3 (java.text.SimpleDateFormat. "yyy-MM-dd'T'HH:mm:ss")]
    (let [a  (convert create-json-object-from type start end)]
      (if (seq? a) true false)))))

(defn do-profile [amount]
  (delete-objects (:local-db (configure)))
  (profile/profile (convert-json "bulletin" 7000000 (+ 7000000 amount))))



(defn convert-json-parallel [type start step times threads]
  (->>
   times
   (parts start step)
   (partition-all threads)
   (map (comp doall (partial pmap (partial convert-json type))))
;;ls   (pmap (partial convert-json type))
   doall))
   


(defn -main [type start step times threads & args]
  (convert-json-parallel
   type
   (Integer/parseInt start)
   (Integer/parseInt step)
   (Integer/parseInt times)
   (Integer/parseInt threads))
  (shutdown-agents))

;;8 threads

;;1. calculate, then update +11% 
;;2. batch insert


(defn crazy [type id]
  (sql/with-connection (:remote-db (configure))
    (sql/with-query-results rs ["select history from history2 where type=? and user_space_id=?"
                                type id]
      (sql/update-or-insert-values
       :history2
       ["type=? and user_space_id= ?" type id]
       {:history
        (-> rs
            first
            :history
            read-json
            (assoc-in [:ol 33 :state-time] "2012-11-11 16:35:23")
            json-str)}))))

(defn crazy-2 []
  (sql/with-connection (:remote-db (configure))
    (sql/with-query-results rs ["select history from history2 where type=? and user_space_id=?"
                                "bulletin" 2839686]
      (-> rs first :history read-json :ol (nth 0) println))))

(defn crazy-3 [amount]
  (sql/with-connection (:remote-db (configure))
    (sql/with-query-results rs ["select user_space_id, type from history limit ?" amount]
      (-> rs count println))))


;;read with batch
;;use pmap
;;recalculate 36000 - 1 hour
;;local performance?
;;continue profiling