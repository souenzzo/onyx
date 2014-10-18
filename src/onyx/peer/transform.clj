(ns ^:no-doc onyx.peer.transform
    (:require [clojure.data.fressian :as fressian]
              [onyx.peer.task-lifecycle-extensions :as l-ext]
              [onyx.peer.pipeline-extensions :as p-ext]
              [onyx.queue.hornetq :refer [take-segments]]
              [onyx.coordinator.planning :refer [find-task]]
              [onyx.peer.operation :as operation]
              [onyx.extensions :as extensions]
              [taoensso.timbre :refer [debug]]
              [dire.core :refer [with-post-hook!]])
    (:import [java.util UUID]
             [java.security MessageDigest]))

(defn requeue-sentinel [queue session ingress-queues uuid]
  (doseq [queue-name (vals ingress-queues)]
    (let [p (extensions/create-producer queue session queue-name)]
      (extensions/produce-message queue p session (.array (fressian/write :done)) {:uuid uuid})
      (extensions/close-resource queue p))))

(defn seal-queue [queue queue-names]
  (let [session (extensions/create-tx-session queue)]
    (doseq [queue-name queue-names]
      (let [producer (extensions/create-producer queue session queue-name)]
        (extensions/produce-message queue producer session (.array (fressian/write :done)))
        (extensions/close-resource queue producer)))
    (extensions/commit-tx queue session)
    (extensions/close-resource queue session)))

(defn read-batch [queue consumers task-map]
  ;; Multi-consumer not yet implemented.
  (let [consumer (first (vals consumers))
        f #(extensions/consume-message queue consumer)]
    (doall (take-segments f (:onyx/batch-size task-map)))))

(defn decompress-segment [queue message]
  (extensions/read-message queue message))

(defn hash-value [x]
  (let [md5 (MessageDigest/getInstance "MD5")]
    (apply str (.digest md5 (.getBytes (pr-str x) "UTF-8")))))

(defn group-message [segment catalog task]
  (let [t (find-task catalog task)]
    (if-let [k (:onyx/group-by-key t)]
      (hash-value (get segment k))
      (when-let [f (:onyx/group-by-fn t)]
        (hash-value ((operation/resolve-fn {:onyx/fn f}) segment))))))

(defn compress-segment [next-tasks catalog segment]
  {:compressed (.array (fressian/write segment))
   :hash-group (reduce (fn [groups t]
                         (assoc groups t (group-message segment catalog t)))
                       {} next-tasks)})

(defn write-batch [queue session producers msgs catalog queue-name->task]
  (dorun
   (for [p producers msg msgs]
     (let [queue-name (extensions/producer->queue-name queue p)
           task (get queue-name->task queue-name)]
       (if-let [group (get (:hash-group msg) task)]
         (extensions/produce-message queue p session (:compressed msg) {:group group})
         (extensions/produce-message queue p session (:compressed msg))))))
  {:onyx.core/written? true})

(defn read-batch-shim
  [{:keys [onyx.core/queue onyx.core/session onyx.core/ingress-queues
           onyx.core/catalog onyx.core/task-map] :as event}]
  (let [consumer-f (partial extensions/create-consumer queue session)
        consumers (reduce-kv (fn [all k v] (assoc all k (consumer-f v))) {} ingress-queues)
        batch (read-batch queue consumers task-map)]
    (merge event {:onyx.core/batch batch :onyx.core/consumers consumers})))

(defn decompress-batch-shim [{:keys [onyx.core/queue onyx.core/batch] :as event}]
  (let [decompressed-msgs (map (partial decompress-segment queue) batch)]
    (merge event {:onyx.core/decompressed decompressed-msgs})))

(defn strip-sentinel-shim [event]
  (operation/on-last-batch
   event
   (fn [{:keys [onyx.core/queue onyx.core/session onyx.core/ingress-queues]}]
     (extensions/n-messages-remaining queue session (first (vals ingress-queues))))))

(defn requeue-sentinel-shim
  [{:keys [onyx.core/queue onyx.core/session onyx.core/ingress-queues] :as event}]
  (let [uuid (or (extensions/message-uuid queue (last (:onyx.core/batch event)))
                 (UUID/randomUUID))]
    (requeue-sentinel queue session ingress-queues uuid))
  (merge event {:onyx.core/requeued? true}))

(defn acknowledge-batch-shim [{:keys [onyx.core/queue onyx.core/batch] :as event}]
  (doseq [message batch]
    (extensions/ack-message queue message))
  (merge event {:onyx.core/acked (count batch)}))

(defn apply-fn-shim
  [{:keys [onyx.core/decompressed onyx.transform/fn onyx.core/params
           onyx.core/task-map] :as event}]
  (if (:onyx/transduce? task-map)
    (merge event {:onyx.core/results (sequence (apply fn params) decompressed)})
    (let [results (flatten (map (partial operation/apply-fn fn params) decompressed))]
      (merge event {:onyx.core/results results}))))

(defn compress-batch-shim
  [{:keys [onyx.core/results onyx.core/catalog onyx.core/serialized-task] :as event}]
  (let [next-tasks (keys (:task/children-links serialized-task))
        compressed-msgs (map (partial compress-segment next-tasks catalog) results)]
    (merge event {:onyx.core/compressed compressed-msgs})))

(defn write-batch-shim
  [{:keys [onyx.core/queue onyx.core/egress-queues onyx.core/serialized-task
           onyx.core/catalog onyx.core/session onyx.core/compressed] :as event}]
  (let [queue-name->task (clojure.set/map-invert (:task/children-links serialized-task))
        producers (map (partial extensions/create-producer queue session) egress-queues)
        batch (write-batch queue session producers compressed catalog queue-name->task)]
    (merge event {:onyx.core/producers producers})))

(defn seal-resource-shim [{:keys [onyx.core/queue onyx.core/egress-queues] :as event}]
  (merge event (seal-queue queue egress-queues)))

(defmethod l-ext/start-lifecycle? :transformer
  [_ event]
  {:onyx.core/start-lifecycle? (operation/start-lifecycle? event)})

(defmethod l-ext/inject-lifecycle-resources :transformer
  [_ {:keys [onyx.core/task-map]}]
  {:onyx.transform/fn (operation/resolve-fn task-map)})

(defmethod p-ext/read-batch :default
  [event] (read-batch-shim event))

(defmethod p-ext/decompress-batch :default
  [event] (decompress-batch-shim event))

(defmethod p-ext/strip-sentinel :default
  [event] (strip-sentinel-shim event))

(defmethod p-ext/requeue-sentinel :default
  [event] (requeue-sentinel-shim event))

(defmethod p-ext/ack-batch :default
  [event] (acknowledge-batch-shim event))

(defmethod p-ext/apply-fn :default
  [event] (apply-fn-shim event))

(defmethod p-ext/compress-batch :default
  [event] (compress-batch-shim event))

(defmethod p-ext/write-batch :default
  [event] (write-batch-shim event))

(defmethod p-ext/seal-resource :default
  [event] (seal-resource-shim event))

(with-post-hook! #'read-batch-shim
  (fn [{:keys [onyx.core/id onyx.core/batch onyx.core/consumers]}]
    (debug (format "[%s] Read batch of %s segments" id (count batch)))))

(with-post-hook! #'decompress-batch-shim
  (fn [{:keys [onyx.core/id onyx.core/decompressed]}]
    (debug (format "[%s] Decompressed %s segments" id (count decompressed)))))

(with-post-hook! #'requeue-sentinel-shim
  (fn [{:keys [onyx.core/id]}]
    (debug (format "[%s] Requeued sentinel value" id))))

(with-post-hook! #'acknowledge-batch-shim
  (fn [{:keys [onyx.core/id onyx.core/acked]}]
    (debug (format "[%s] Acked %s segments" id acked))))

(with-post-hook! #'apply-fn-shim
  (fn [{:keys [onyx.core/id onyx.core/results]}]
    (debug (format "[%s] Applied fn to %s segments" id (count results)))))

(with-post-hook! #'compress-batch-shim
  (fn [{:keys [onyx.core/id onyx.core/compressed]}]
    (debug (format "[%s] Compressed %s segments" id (count compressed)))))

(with-post-hook! #'write-batch-shim
  (fn [{:keys [onyx.core/id onyx.core/producers]}]
    (debug (format "[%s] Wrote batch to %s outputs" id (count producers)))))

(with-post-hook! #'seal-resource-shim
  (fn [{:keys [onyx.core/id]}]
    (debug (format "[%s] Sealing resource" id))))

