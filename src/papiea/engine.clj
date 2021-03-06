(ns papiea.engine
  (:require [com.rpl.specter :refer :all]
            [slingshot.slingshot :refer [throw+ try+]]
            [orchestra.core :refer [defn-spec]]
            [orchestra.spec.test :as st]
            [clojure.set :as set]
            [papiea.specs]
            [papiea.core :as c]
            [tracks.core :as t]
            [papiea.tasks :as task]
            [papiea.core :refer [call-api fixed-rate ->timer]]
            [papiea.db.spec :as spdb]
            [papiea.db.status :as stdb]))
;;(map #(ns-unmap *ns* %) (keys (ns-interns *ns*)))
(set! *warn-on-reflection* true)

(defn unidirectional-diff
  "Check diff only on items defined in the reference map"
  [reference target]
  (cond
    (map? reference)        (every? (fn[key] (unidirectional-diff (get reference key) (get target key))) (keys reference))
    (sequential? reference) (every? (fn[key] (unidirectional-diff (get reference key) (get target key))) reference)
    :default                (= reference target)))

(defn state-settled?
  "returns true iff every key that is defined in the `spec` (recursively defined) has the same value as the one in the `status`"
  [entity] (unidirectional-diff (:spec entity) (:status entity)))

(defn handleable-diffs-for [state prefix]
  (let [prefix-diffs (select (into prefix [ALL (complement state-settled?)]) state)]
    (map (fn[{:keys [spec status] :as w}]
           (cond
             (and spec (not status)) {:added w :prefix prefix}
             (and status (not spec)) {:removed w :prefix prefix}
             (and spec status)       {:changed w :prefix prefix}))
         prefix-diffs)))


(defn handleable-diffs [state prefixes]
  (remove (comp empty? :diffs)
          (map (fn[prefix] {:prefix prefix
                           :diffs (handleable-diffs-for state prefix)}) prefixes)))


(defn merge-entities-part [old-entities new-entities part]
  (let [new-entities-map (reduce (fn[o n] (assoc o (-> n :metadata :uuid) n)) {} new-entities)]
    (loop [old-entities old-entities new-entities-map new-entities-map merged-entities []]
      (cond (and (empty? old-entities)
                 (empty? new-entities-map)) merged-entities 
            (empty? new-entities-map)       (into merged-entities (transform [ALL] (fn[x] (dissoc x part)) old-entities))
            (empty? old-entities)           (into merged-entities (vals new-entities-map))
            :else                           (let [old (peek old-entities)
                                                  id  (-> old :metadata :uuid)
                                                  new (get new-entities-map id)]
                                              (recur (pop old-entities)
                                                     (dissoc new-entities-map id)
                                                     (conj merged-entities (assoc old part (get new part)))))))))

(defn-spec merge-entities-status :papiea.entity.list/statuses
  "Merges two lists of entities's status, based on uuid "
  [old-entities :papiea.entity.list/statuses new-entities :papiea.entity.list/statuses]
  (merge-entities-part old-entities new-entities :status))

(defn-spec merge-entities-specs :papiea.entity.list/specs
  [old-entities :papiea.entity.list/specs new-entities :papiea.entity.list/specs]
  (-> (merge-entities-part old-entities new-entities :spec)
      (merge-entities-part new-entities :metadata)))

(defn ensure-entity-map [m ks]
  (if (empty? ks) m
      (let [[k & ks] ks]
        (assoc m k (ensure-entity-map (get m k (if (empty? ks) [] {})) ks)))))

(defn refresh-status
  "Based on the registered transformers, ask each one to supply its view of its entities status"
  ([transformers] (refresh-status {} transformers))
  ([state transformers]
   (reduce (fn[o [prefix {:keys [status-fn]}]]
             (if status-fn
               (transform prefix
                          (fn[x] (let [db-statuses (stdb/get-entities prefix)
                                      statuses    (call-api status-fn db-statuses)
                                      removed     (map (fn[e] {:metadata (:metadata e)} #_(dissoc e :status :spec))
                                                       (set/difference (set db-statuses)
                                                                       (set statuses)))]
                                  (doseq [entity (concat statuses removed)]
                                    (stdb/update-entity-status! prefix entity))                                       
                                  (merge-entities-status x statuses)))
                          (ensure-entity-map o prefix))
               (do(println "Error: Cant refresh" prefix " - no `status-fn` defined. Unsafely ignoring..")
                  (ensure-entity-map o prefix))))
    state
    transformers)))

(declare prefix) ;; bug in cider while debugging..
(defn refresh-specs
  "Based on the registered transformers, ask each entity type for its entities specs in our specs-db"
  ([transformers] (refresh-specs {} transformers))
  ([state transformers]
   (reduce (fn[o [prefix _]]
             (transform prefix (fn[prefix-entities]
                                 (merge-entities-specs prefix-entities
                                                       (spdb/get-entities prefix)))
                        (ensure-entity-map o prefix)))
           state
           transformers)))

(defn previous-spec-version [entity]
  (transform [:metadata :spec_version] dec entity))

(defn unspec-version
  "Returns an entity composed only from the spec and the uuid part of the metadata. Should only be used internally"
  [entity] {:metadata {:uuid (-> entity :metadata :uuid)}
            :spec (-> entity :spec)})

(defn ensure-spec-version
  ([prefix entity default-value]
   (if (-> entity :metadata :spec_version)
     entity
     (let [e (spdb/get-entity prefix (-> entity :metadata :uuid))
           spec-ver (if e (-> e :metadata :spec_version) default-value)]
       (setval [:metadata :spec_version] spec-ver entity)))))

(defn insert-spec-change!
  "Inserts a spec change. Every spec change induces an increment in [:metadata :spec_version].
   Most secure method is to supply the right :spec_version this change intends to modify.
   If none is provided, the system queries for the last one and auto-assigns it. Using this
   default mechanism might cause a race condition"
  [prefix entity]
  (spdb/swap-entity-spec! prefix (ensure-spec-version prefix entity -1)))

(defn turn-spec-to-status [transformers prefix tasked-fn success-fn failed-fn {:keys [added removed changed]}]
  (let [{:keys [add-fn del-fn change-fn] :as w} (get transformers prefix)]
    (let [[modify data op tasked] (cond added   [add-fn added :added (:add-tasked? w)]
                                        removed [del-fn removed :removed (:del-tasked? w)]
                                        changed [change-fn changed :changed (:change-tasked? w)])
          task-id (when tasked (tasked-fn op data))]
      ;;(println "Performing: " op data)
      (let [r (try+ (c/call-api modify data)
                    (catch Object o
                      {:status :failed
                       :error  o}))]
        (if tasked
          (if (= :failed (:status r))
            (task/update-task (:uuid task-id) {:status "FAILED"})
            (do (stdb/update-entity-status! prefix r) ;; Save the state
                (task/update-task (:uuid task-id) {:status "COMPLETED"})
                (when-not (:status r)
                  (spdb/remove-entity prefix (-> r :metadata :uuid))
                  (stdb/remove-entity prefix (-> r :metadata :uuid)))))
          (if (= :failed (:status r))
            (failed-fn op data)
            (do
              (stdb/update-entity-status! prefix r)
              (when (nil? (:status r))
                (spdb/remove-entity prefix (-> r :metadata :uuid))
                (stdb/remove-entity prefix (-> r :metadata :uuid)))
               ;; Save the state
                (success-fn op data))))))))

(defn tasked-op [change-watch]
  (fn[op entity]
    (let [previous-entity (unspec-version (dissoc entity :status))]
      (when-let [done (get @change-watch previous-entity)]
        (swap! change-watch dissoc previous-entity)
        (let [task (task/register-new-task entity)]
          (deliver done task)
          task)))))

(defn change-succeeded [change-watch]
  (fn[op entity]
    (let [previous-entity (unspec-version (dissoc entity :status))]
      (println "watch:"  @change-watch)
      (println "lookup:" previous-entity)
      (when-let [done (get @change-watch previous-entity)]
        (swap! change-watch dissoc previous-entity)
        (deliver done {:status :ok})))))

(defn change-failed [change-watch]
  (fn[op entity]
    (let [previous-entity (unspec-version (dissoc entity :status))]
      (when-let [done (get @change-watch previous-entity)]
        (swap! change-watch dissoc previous-entity)
        (deliver done {:status :failed})))))

(defn handle-diffs
  "apply the diffs"
  ([transformers]
   (let [change-watch (atom {})]
     (handle-diffs transformers
                   (tasked-op change-watch)
                   (change-succeeded change-watch)
                   (change-failed change-watch))))
  ([transformers tasked-fn success-fn failed-fn]
   (println "Thread:" (.getName (Thread/currentThread)))
   (let [diffs (-> (refresh-specs transformers)
                   (refresh-status transformers)
                   (handleable-diffs (keys transformers)))]
     (when-not (empty? diffs)
       (println "\tFound" (count(:diffs (first diffs))) "diffs")
       (time(doseq [{:keys [prefix diffs]} diffs
                    diff diffs]
              
              (turn-spec-to-status transformers prefix tasked-fn success-fn failed-fn diff)))))))

;; We model the async call as a watch on an atom. The watch is triggered every time the atom
;; value is changed, causing handle-diffs to be called with the registered transformers


(defprotocol PapieaEngine 
  (start-engine [this transformers diff-interval])
  (stop-engine [this])
  (notify-change [this])
  (change-spec! [this prefix entity])
  (get-entity [this prefix entity]))


;;(require '[com.climate.claypoole :as cp])

(defrecord DefaultEngine [change-watch handle-diff-notify started state]
  PapieaEngine
  (start-engine [this transformers diff-interval]
    (add-watch handle-diff-notify :process-diffs
               (fn[key a o n]
                 (println n "Looking for diffs")
                 (try+
                  (handle-diffs transformers
                                (tasked-op change-watch)
                                (change-succeeded change-watch)
                                (change-failed change-watch))
                  (catch Object o
                    (println o)))))

    (when (compare-and-set! started false true)
      (println "Starting engine...")
      (let [timer (->timer)]
        (swap! state assoc
               :diff-interval diff-interval
               ;;:diff-cleanup-cancel-fn (fixed-rate (fn[] (cleanup-change-watch )) timer (* 2.5 diff-interval))
               :tranformers (:transformers this)
               :interval-notify-cancel-fn (fixed-rate (partial notify-change this) timer diff-interval))))
    this)
  
  (stop-engine [this]
    (when (compare-and-set! started true false)
      (println "Stopping engine..")
      ((:interval-notify-cancel-fn @state)) ;; stop the timer
      (swap! state dissoc :interval-notify-cancel-fn :tranformers))
    this)
  
  (notify-change [this]
    ;; This function simply changes the value of the atom, causing the function to execute
    (swap! handle-diff-notify inc)
    this)

  (change-spec! [this prefix entity]
    (let [done          (promise)
          speced-entity (ensure-spec-version prefix entity -1)]
      (try+
       (swap! change-watch assoc (unspec-version speced-entity) done)
       (insert-spec-change! prefix speced-entity)
       ;;(notify-change this)
       done
       (catch Object e
         (println "Error processing spec change." e)
         (deliver done (if (map? e)
                         (merge {:status :failure} e)
                         {:status :failure
                          :cause  e}))
         (swap! change-watch dissoc (unspec-version speced-entity))
         done))))

  (get-entity [this prefix uuid]
    (let [all-ents (get-in (-> (refresh-status (:transformers this))
                               (refresh-specs (:transformers this)))
                           prefix)]
      (some-> (filter (fn[x] (= uuid (-> x :metadata :uuid)))  all-ents)
              first))))

(defn new-papiea-engine []
  (println "Created new engine")
  (->DefaultEngine (atom {}) (atom 0) (atom false) (atom {})))


