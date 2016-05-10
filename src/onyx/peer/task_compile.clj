(ns ^:no-doc onyx.peer.task-compile
  (:require [clojure.set :refer [subset?]]
            [taoensso.timbre :refer [info error warn trace fatal] :as timbre]
            [schema.core :as s]
            [onyx.schema :refer [Trigger Window TriggerState WindowExtension Event]]
            [onyx.peer.operation :refer [kw->fn]]
            [onyx.flow-conditions.fc-compile :as fc]
            [onyx.lifecycles.lifecycle-compile :as lc]
            [onyx.peer.grouping :as g]
            [onyx.static.uuid :refer [random-uuid]]
            [onyx.state.ack :as state-ack]
            [onyx.static.validation :as validation]
            [onyx.static.logging :as logging]
            [onyx.refinements]
            [onyx.windowing.window-compile :as wc]))

(defn windows->event-map [windows triggers {:keys [task-map] :as event}]
  (assoc event 
         :windows windows
         :windows-state (atom (mapv #(wc/resolve-window-state % triggers task-map) windows))))

(s/defn filter-triggers 
  [windows :- [WindowExtension]
   triggers :- [Trigger]]
  (filter #(some #{(:trigger/window-id %)}
                 (map :id windows))
          triggers))

(defn triggers->event-map [triggers {:keys [windows] :as event}]
  (assoc event 
         :triggers 
         (mapv wc/resolve-trigger triggers)))

(defn flow-conditions->event-map 
  [{:keys [flow-conditions workflow task] :as event}]
  (-> event
      (assoc :flow-conditions flow-conditions)
      (assoc :compiled-norm-fcs (fc/compile-fc-happy-path flow-conditions workflow task))
      (assoc :compiled-ex-fcs (fc/compile-fc-exception-path flow-conditions workflow task)))) 

(defn task->event-map
  [{:keys [task-map id job-id
           catalog serialized-task messenger
           monitoring state task-state
           log-prefix task-information] :as event}]
  (-> event
      (assoc :log-prefix log-prefix)
      (assoc :messenger messenger)
      (assoc :monitoring monitoring)
      (assoc :acking-state (state-ack/new-ack-state task-map task-state messenger))
      (assoc :job-id job-id)
      (assoc :id id)
      (assoc :state state)
      (assoc :bulk? (:onyx/bulk? task-map))
      (assoc :uniqueness-task? (contains? task-map :onyx/uniqueness-key))
      (assoc :uniqueness-key (:onyx/uniqueness-key task-map))
      (assoc :fn (:fn event))
      (assoc :task-type (:onyx/type task-map))
      (assoc :task-state task-state)
      (assoc :grouping-fn (g/task-map->grouping-fn task-map))
      (assoc :task->group-by-fn (g/compile-grouping-fn catalog (:egress-ids serialized-task)))
      (assoc :ingress-ids (:ingress-ids serialized-task))
      (assoc :egress-ids (keys (:egress-ids serialized-task)))
      (assoc :task-information task-information)))

(defn lifecycles->event-map [{:keys [lifecycles task] :as event}]
  (-> event
      (assoc :compiled-start-task-fn
             (lc/compile-start-task-functions lifecycles task))
      (assoc :compiled-before-task-start-fn
             (lc/compile-before-task-start-functions lifecycles task))
      (assoc :compiled-before-batch-fn
             (lc/compile-before-batch-task-functions lifecycles task))
      (assoc :compiled-after-read-batch-fn
             (lc/compile-after-read-batch-task-functions lifecycles task))
      (assoc :compiled-after-batch-fn
             (lc/compile-after-batch-task-functions lifecycles task))
      (assoc :compiled-after-task-fn
             (lc/compile-after-task-functions lifecycles task))
      (assoc :compiled-after-ack-segment-fn
             (lc/compile-after-ack-segment-functions lifecycles task))
      (assoc :compiled-after-retry-segment-fn
             (lc/compile-after-retry-segment-functions lifecycles task))
      (assoc :compiled-handle-exception-fn
             (lc/compile-handle-exception-functions lifecycles task))))

(defn task-params->event-map [{:keys [peer-opts task-map] :as event}]
  (let [fn-params (:onyx.peer/fn-params peer-opts)
        params (into (vec (get fn-params (:onyx/name task-map)))
                     (map (fn [param] (get task-map param))
                          (:onyx/params task-map)))]
    (assoc event :params params)))
