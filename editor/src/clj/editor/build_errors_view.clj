(ns editor.build-errors-view
  (:require [clojure.string :as string]
            [dynamo.graph :as g]
            [editor.code.data :refer [CursorRange->line-number]]
            [editor.defold-project :as project]
            [editor.handler :as handler]
            [editor.jfx :as jfx]
            [editor.outline :as outline]
            [editor.resource :as resource]
            [editor.ui :as ui]
            [editor.workspace :as workspace])
  (:import [clojure.lang MapEntry PersistentQueue]
           [java.util Collection]
           [javafx.collections ObservableList]
           [javafx.scene.control TreeItem TreeView]
           [javafx.scene.input Clipboard ClipboardContent]
           [javafx.scene.layout HBox]
           [javafx.scene.text Text]))

(set! *warn-on-reflection* true)

(defn- pair [a b]
  (MapEntry. a b))

(defn- queue [item]
  (conj PersistentQueue/EMPTY item))

(defn- openable-resource [evaluation-context node-id]
  (when (g/node-instance? (:basis evaluation-context) resource/ResourceNode node-id)
    (when-some [resource (g/node-value node-id :resource evaluation-context)]
      (when (resource/openable-resource? resource)
        resource))))

(defn- node-id-at-override-depth [override-depth node-id]
  (if (and (some? node-id) (pos? override-depth))
    (recur (dec override-depth) (g/override-original node-id))
    node-id))

(defn- parent-resource [evaluation-context errors origin-override-depth origin-override-id]
  (or (when-some [node-id (node-id-at-override-depth origin-override-depth (:_node-id (first errors)))]
        (let [basis (:basis evaluation-context)]
          (when (or (nil? origin-override-id)
                    (not= origin-override-id (g/override-id basis node-id)))
            (when-some [resource (openable-resource evaluation-context node-id)]
              {:node-id (or (g/override-original basis node-id) node-id)
               :resource resource}))))
      (when-some [remaining-errors (next errors)]
        (recur evaluation-context remaining-errors origin-override-depth origin-override-id))))

(defn- find-override-value-origin [basis node-id label depth]
  (if (and node-id (g/override? basis node-id))
    (if-not (g/property-overridden? basis node-id label)
      (recur basis (g/override-original basis node-id) label (inc depth))
      (pair node-id depth))
    (pair node-id depth)))

(defn- error-cursor-range [error]
  (some-> error :user-data :cursor-range))

(defn- missing-resource-node? [evaluation-context node-id]
  (and (g/node-instance? (:basis evaluation-context) resource/ResourceNode node-id)
       (some? (g/node-value node-id :resource evaluation-context))
       (some? (g/node-value node-id :_output-jammers evaluation-context))))

(defn- error-item [evaluation-context root-cause]
  (let [{:keys [message severity]} (first root-cause)
        cursor-range (error-cursor-range (first root-cause))
        errors (drop-while (comp (fn [node-id]
                                   (or (nil? node-id)
                                       (missing-resource-node? evaluation-context node-id)))
                                 :_node-id)
                           root-cause)
        error (assoc (first errors) :message message)
        basis (:basis evaluation-context)
        [origin-node-id origin-override-depth] (find-override-value-origin basis (:_node-id error) (:_label error) 0)
        origin-override-id (when (some? origin-node-id) (g/override-id basis origin-node-id))
        parent (parent-resource evaluation-context errors origin-override-depth origin-override-id)
        cursor-range (or cursor-range (error-cursor-range error))]
    (cond-> {:parent parent
             :node-id origin-node-id
             :message (:message error)
             :severity (:severity error severity)}

            (some? cursor-range)
            (assoc :cursor-range cursor-range))))

(defn- push-causes [queue error path]
  (let [new-path (conj path error)]
    (reduce (fn [queue error]
              (conj queue (pair error new-path)))
            queue
            (:causes error))))

(defn- root-causes-helper [queue]
  (lazy-seq
    (when-some [[error path] (peek queue)]
      (if (seq (:causes error))
        (root-causes-helper (push-causes (pop queue) error path))
        (cons (conj path error)
              (root-causes-helper (pop queue)))))))

(defn- root-causes
  "Returns a lazy sequence of distinct error items from the causes in the supplied ErrorValue."
  [error-value]
  ;; Use a throwaway evaluation context to avoid context creation overhead.
  ;; We don't evaluate any outputs, so there is no need to update the cache.
  (let [evaluation-context (g/make-evaluation-context)]
    (sequence (comp (take 10000)
                    (map (partial error-item evaluation-context))
                    (distinct))
              (root-causes-helper (queue (pair error-value (list)))))))

(defn severity->int [severity]
  (case severity
    :info 2
    :warning 1
    :fatal 0
    0))

(defn error-pair->sort-value [[parent errors]]
  [(reduce min 1000 (map (comp severity->int :severity) errors))
   (resource/resource->proj-path (:resource parent))])

(defn- error-items [root-error]
  (->> (root-causes root-error)
       (sort-by :cursor-range)
       (group-by :parent)
       (sort-by error-pair->sort-value)
       (mapv (fn [[resource errors]]
               (if resource
                 {:type :resource
                  :value resource
                  :children errors}
                 {:type :unknown-parent
                  :children errors})))))

(defn build-resource-tree [root-error]
  {:label "root"
   :children (error-items root-error)})

(defmulti make-tree-cell
  (fn [tree-item] (:type tree-item)))

(defmethod make-tree-cell :resource
  [tree-item]
  (let [resource (-> tree-item :value :resource)]
    {:text (resource/proj-path resource)
     :icon (workspace/resource-icon resource)
     :style (resource/style-classes resource)}))

(defmethod make-tree-cell :unknown-parent
  [tree-item]
  {:text "Unknown source"
   :icon "icons/32/Icons_29-AT-Unknown.png"
   :style #{"severity-info"}})

(defmethod make-tree-cell :default
  [error-item]
  (let [cursor-range (:cursor-range error-item)
        message (cond->> (:message error-item)
                         (some? cursor-range)
                         (str "Line " (CursorRange->line-number cursor-range) ": "))
        icon (case (:severity error-item)
               :info "icons/32/Icons_E_00_info.png"
               :warning "icons/32/Icons_E_01_warning.png"
               "icons/32/Icons_E_02_error.png")
        style (case (:severity error-item)
                :info #{"severity-info"}
                :warning #{"severity-warning"}
                #{"severity-error"})
        image (jfx/get-image-view icon 16)
        text (Text. message)]
    {:graphic (HBox. (ui/node-array [image text]))
     :style style}))

(defn- error-selection
  [node-id resource]
  (when node-id
    (if (g/node-instance? outline/OutlineNode node-id)
      [node-id]
      (let [project (project/get-project node-id)]
        (when-some [resource-node (project/get-resource-node project resource)]
          [resource-node])))))

(defn- open-error [open-resource-fn selection]
  (when-some [error-item (first selection)]
    (if (= :resource (:type error-item))
      (let [{:keys [node-id resource]} (:value error-item)]
        (when (and (resource/openable-resource? resource) (resource/exists? resource))
          (ui/run-later
            (open-resource-fn resource [node-id] {}))))
      (let [resource (-> error-item :parent :resource)
            node-id (:node-id error-item)
            selection (error-selection node-id resource)
            opts (select-keys error-item [:cursor-range])]
        (when (and (resource/openable-resource? resource) (resource/exists? resource))
          (ui/run-later
            (open-resource-fn resource selection opts)))))))

(defn- error-line-for-clipboard [error-item]
  (let [message (:message error-item)
        line (when-some [cursor-range (:cursor-range error-item)]
               (str "Line " (CursorRange->line-number cursor-range) ": "))]
    (str line message)))

(defn- error-text-for-clipboard [selection]
  (let [children    (:children selection)
        resource    (or (get-in selection [:value :resource])
                        (get-in selection [:parent :resource]))
        next-line   (str \newline \tab)
        proj-path   (if-let [res-path (not-empty (resource/resource->proj-path resource))]
                      (str res-path next-line)
                      "")
        error-lines (if (not-empty children)
                      (string/join next-line (map error-line-for-clipboard children))
                      (error-line-for-clipboard selection))]
    (str proj-path error-lines)))

(handler/defhandler :copy :build-errors-view
  (active? [selection] (not-empty selection))
  (enabled? [selection] (not-empty selection))
  (run [build-errors-view]
    (let [clipboard (Clipboard/getSystemClipboard)
          content    (ClipboardContent.)
          selection  (first (ui/selection build-errors-view))
          error-text (error-text-for-clipboard selection)]
      (.putString content error-text)
      (.setContent clipboard content))))

(ui/extend-menu ::build-errors-menu nil
                [{:label "Copy"
                  :command :copy}])

(defn make-build-errors-view [^TreeView errors-tree open-resource-fn]
  (doto errors-tree
    (.setShowRoot false)
    (ui/cell-factory! make-tree-cell)
    (ui/on-double! (fn [_]
                     (when-let [selection (ui/selection errors-tree)]
                       (open-error open-resource-fn selection))))
    (ui/register-context-menu ::build-errors-menu)
    (ui/context! :build-errors-view
                 {:build-errors-view errors-tree}
                 (ui/->selection-provider errors-tree))))

(declare tree-item)

(defn- list-children
  ^Collection [parent]
  (map tree-item (:children parent)))

(defn- tree-item [parent]
  (let [cached (atom false)]
    (proxy [TreeItem] [parent]
      (isLeaf []
        (empty? (:children (.getValue ^TreeItem this))))
      (getChildren []
        (let [this ^TreeItem this
              ^ObservableList children (proxy-super getChildren)]
          (when-not @cached
            (reset! cached true)
            (.setAll children (list-children (.getValue this))))
          children)))))

(defn update-build-errors [^TreeView errors-tree build-errors]
  (let [res-tree (build-resource-tree build-errors)
        new-root (tree-item res-tree)]
    (.setRoot errors-tree new-root)
    (doseq [^TreeItem item (ui/tree-item-seq (.getRoot errors-tree))]
      (.setExpanded item true))
    (when-let [first-error (->> (ui/tree-item-seq (.getRoot errors-tree))
                                (filter (fn [^TreeItem item] (.isLeaf item)))
                                first)]
      (.select (.getSelectionModel errors-tree) first-error))))

(defn clear-build-errors [^TreeView errors-tree]
  (.setRoot errors-tree nil))
