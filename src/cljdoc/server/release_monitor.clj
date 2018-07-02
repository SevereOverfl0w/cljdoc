(ns cljdoc.server.release-monitor
  (:require [cljdoc.util.repositories :as repositories])
  (:require [integrant.core :as ig]
            [clj-http.lite.client :as http]
            [clojure.java.jdbc :as sql]
            [clojure.tools.logging :as log]
            [tea-time.core :as tt])
  (:import (java.time Instant Duration)))

(defn- last-release-ts [db-spec]
  (some-> (sql/query db-spec ["SELECT * FROM releases ORDER BY datetime(created_ts) DESC LIMIT 1"])
          first
          :created_ts
          Instant/parse))

(defn- oldest-not-built
  "Return the oldest not yet built release, excluding releases younger than 10 minutes.

  The 10min delay has been put into place to avoid building projects before people had a chance
  to push the respective commits or tags to their git repositories."
  [db-spec]
  (first (sql/query db-spec ["SELECT * FROM releases WHERE build_id IS NULL AND datetime(created_ts) < datetime('now', '-10 minutes') ORDER BY datetime(created_ts) DESC LIMIT 1"])))

(defn- update-build-id
  [db-spec release-id build-id]
  (sql/update! db-spec
               "releases"
               {:build_id build-id}
               ["id = ?" release-id]))

(defn- trigger-build
  [release]
  {:pre [(:id release) (:group_id release) (:artifact_id release) (:version release)]}
  ;; I'm really not liking that this makes it all very tied to the HTTP server... - martin
  (let [req (http/post (str "http://localhost:" (get-in (cljdoc.config/config) [:cljdoc/server :port]) "/api/request-build2")
                       {:follow-redirects false
                        :form-params {:project (str (:group_id release) "/" (:artifact_id release))
                                      :version (:version release)}
                        :content-type "application/x-www-form-urlencoded"})
        build-id (some->> (get-in req [:headers "location"])
                          (re-find #"/builds/(\d+)")
                          (second))]
    (assert build-id "Could not extract build-id from response")
    build-id))

(defn release-fetch-job-fn [db-spec]
  (let [ts (or (some-> (last-release-ts db-spec)
                       (.plus (Duration/ofSeconds 1)))
               (.minus (Instant/now) (Duration/ofHours 24)))
        cljsjs?   #(= "cljsjs" (:group_id %))
        snapshot? #(.endsWith (:version %) "-SNAPSHOT")
        releases (->> (repositories/releases-since ts)
                      (remove snapshot?)
                      (remove cljsjs?))]
    (when (seq releases)
      (log/infof "Storing %s new releases in releases table" (count releases))
      (sql/insert-multi! db-spec "releases" releases))))

(defn build-queuer-job-fn [db-spec dry-run?]
  (when-let [to-build (oldest-not-built db-spec)]
    (if dry-run?
      (log/infof "Dry-run mode: not triggering build for %s/%s %s"
                 (:group_id to-build) (:artifact_id to-build) (:version to-build))
      (do (log/infof "Queuing build for %s" to-build)
          (update-build-id db-spec (:id to-build) (trigger-build to-build))))))

(defmethod ig/init-key :cljdoc/release-monitor [_ {:keys [db-spec dry-run?]}]
  (log/info "Starting ReleaseMonitor" (if dry-run? "(dry-run mode)" ""))
  (tt/start!)
  {:release-fetcher (tt/every! 60 #(release-fetch-job-fn db-spec))
   :build-queuer    (tt/every! (* 10 60) 10 #(build-queuer-job-fn db-spec dry-run?))})

(defmethod ig/halt-key! :cljdoc/release-monitor [_ release-monitor]
  (log/info "Stopping ReleaseMonitor")
  (tt/cancel! (:release-fetcher release-monitor))
  (tt/cancel! (:build-queuer release-monitor)))

(comment
  (def db-spec (cljdoc.config/build-log-db))

  (build-queuer-job-fn db-spec true)

  (def rm
    (ig/init-key :cljdoc/release-monitor db-spec))

  (ig/halt-key! :cljdoc/release-monitor rm)

  (doseq [r (repositories/releases-since (.minus (Instant/now) (Duration/ofDays 2)))]
    (insert db-spec r))

  (last (sql/query db-spec ["SELECT * FROM releases"]))

  (trigger-build db-spec (first (sql/query db-spec ["SELECT * FROM releases"])))

  (clojure.pprint/pprint
   (->> (repositories/releases-since (last-release-ts db-spec))
        (map #(select-keys % [:created_ts]))))

  (oldest-not-built db-spec)

  

  )
