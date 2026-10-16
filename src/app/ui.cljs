(ns app.ui
  (:require [app.dom :as dom :refer [create-el]]
            [app.model :as model]
            [app.qasm :as qasm]
            [app.export :as export]
            [app.io :as io]))

(def dragging (atom false))
(def drag-type (atom nil))
(def target-drag (atom nil))

;; Last OpenQASM text shown in the editor window (or the generated program
;; until an editor window has sent its first change). The main window owns the
;; circuit model; this is the text of record for saving and for republishing.
(def editor-source (atom nil))

(defn set-drag-active! [active]
  (reset! dragging active)
  (let [app (js/document.getElementById "app")
        classes (.-classList app)]
    (if active
      (.add classes "drag-active")
      (.remove classes "drag-active"))))

(declare remove-gate! render-gates! render-code! build-wires!)

(defn local-xy [event node]
  (let [rect (.getBoundingClientRect node)]
    [(- (.-clientX event) (.-left rect) (.-scrollLeft node))
     (- (.-clientY event) (.-top rect) (.-scrollTop node))]))

(defn apply-cx-layout! [container control target]
  (let [{:keys [above span control-at-top?]} (model/cx-geometry control target)
        style (.-style container)
        control-part (.. container (querySelector ".cx-control"))
        target-part (.. container (querySelector ".cx-target"))
        line (.. container (querySelector ".cx-line"))]
    (set! (.-top style) (str (- (model/wire-y above) (/ model/gate-size 2)) "px"))
    (set! (.-height style) (str (+ span model/gate-size) "px"))
    (set! (.-top (.-style control-part)) (str (if control-at-top? 0 span) "px"))
    (set! (.-top (.-style target-part)) (str (if control-at-top? span 0) "px"))
    (set! (.-height (.-style line)) (str span "px"))))

(defn start-target-drag! [event {:keys [id wire target]}]
  (when-not @drag-type
    (.preventDefault event)
    (let [part (.-target event)]
      (try
        (.setPointerCapture part (.-pointerId event))
        (catch js/Error _))
      (reset! target-drag {:id id :control wire :wire target}))))

(defn target-drag-move [event]
  (when-let [{:keys [control] :as drag} @target-drag]
    (let [[_ y] (local-xy event (js/document.getElementById "canvas"))
          qubits (:qubits @model/state)
          wire (model/snap-wire y qubits)]
      (when (and (not= wire control) (<= 0 wire (dec qubits)))
        (.preventDefault event)
        (reset! target-drag (assoc drag :wire wire))
        (apply-cx-layout! (.-parentElement (.-currentTarget event)) control wire)))))

(defn target-drag-up [event]
  (when-let [{:keys [id control wire]} @target-drag]
    (reset! target-drag nil)
    (when (and wire (not= wire control))
      (let [{:keys [qubits gates]} @model/state
            gate (first (filter #(= (:id %) id) gates))
            target (model/nearest-free-wire gates (:col gate) wire control qubits)
            target (if (= target control) (:target gate) target)]
        (model/save-history!)
        (model/clear-future!)
        (swap! model/state update :gates
               (fn [gs] (mapv #(if (= (:id %) id) (assoc % :target target) %) gs)))
        (render-gates!)
        (render-code!)))))

(defn gate-shape [{:keys [type wire target name]}]
  (case type
    :cx
    (let [{:keys [span control-at-top?]} (model/cx-geometry wire target)
          line (create-el :div
                          {:class "cx-line"
                           :style (str "top:" (/ model/gate-size 2) "px;height:" span "px;")})
          control (create-el :img
                             {:class "cx-part cx-control"
                              :src "static/control.svg"
                              :width model/gate-size
                              :height model/gate-size
                              :style (str "top:" (if control-at-top? 0 span) "px;")})
          target (create-el :img
                            {:class "cx-part cx-target"
                             :src "static/target.svg"
                             :title "Drag to move target"
                             :width model/gate-size
                             :height model/gate-size
                             :style (str "top:" (if control-at-top? span 0) "px;")})]
      [line control target])

    :custom
    [(create-el :span {:class "custom-label"} name)]

    :barrier
    [(create-el :div {:class "barrier-line"})]

    [(create-el :img
                {:class "gate-img"
                 :src (model/gate-src type)
                 :width model/gate-size
                 :height model/gate-size})]))

(defn span-style [{:keys [col wires]}]
  (let [above (apply min wires)
        below (apply max wires)]
    (str "left:" (model/gate-left col) "px;top:" (- (model/wire-y above) (/ model/gate-size 2)) "px;"
         "width:" model/gate-size "px;height:" (+ (- (model/wire-y below) (model/wire-y above)) model/gate-size) "px;")))

(defn outer-style [{:keys [type wire target col wires]}]
  (cond
    (= type :cx)
    (let [{:keys [above span]} (model/cx-geometry wire target)]
      (str "left:" (model/gate-left col) "px;top:" (- (model/wire-y above) (/ model/gate-size 2)) "px;"
           "width:" model/gate-size "px;height:" (+ span model/gate-size) "px;"))

    (contains? #{:custom :barrier} type)
    (span-style {:col col :wires wires})

    :else
    (str "left:" (model/gate-left col) "px;top:" (model/gate-top wire) "px;"
         "width:" model/gate-size "px;height:" model/gate-size "px;")))

(defn measure-link [gate]
  (let [{:keys [qubits cbits]} @model/state
        cbits (or cbits 0)]
    (when (pos? cbits)
      (let [y0 (model/wire-y (:wire gate))
            y1 (model/cwire-y 0 qubits)]
        (create-el :div
                   {:class "measure-link"
                    :style (str "left:" (/ model/gate-size 2) "px;"
                                "top:" model/gate-size "px;"
                                "height:" (- y1 y0 (/ model/gate-size 2)) "px;")})))))

(defn gate-node [{:keys [id type] :as gate}]
  (let [node (create-el :div
                        {:class (str "gate" (when (= type :custom) " gate-custom"))
                         :title "Double-click to remove"
                         :style (outer-style gate)})]
    (when (= type :measure)
      (when-let [link (measure-link gate)]
        (.appendChild node link)))
    (doseq [part (gate-shape gate)]
      (.appendChild node part))
    (when (= type :cx)
      (let [target-part (.. node (querySelector ".cx-target"))]
        (.addEventListener target-part "pointerdown" (fn [event] (start-target-drag! event gate)))
        (.addEventListener target-part "pointermove" target-drag-move)
        (.addEventListener target-part "pointerup" target-drag-up)
        (.addEventListener target-part "pointercancel" target-drag-up)))
    (.addEventListener node "dblclick" (fn [_] (remove-gate! id)))
    node))

(defn remove-gate! [id]
  (model/save-history!)
  (model/clear-future!)
  (swap! model/state model/remove-gate-from-state id)
  (render-gates!)
  (render-code!))

(defn canvas-viewport-width []
  (if-let [canvas (js/document.getElementById "canvas")]
    (.-clientWidth canvas)
    0))

(defn layout-canvas! []
  (let [{:keys [width height]} (model/canvas-size @model/state)
        width (max width (canvas-viewport-width))]
    (doseq [id ["wires-layer" "gates-layer"]]
      (when-let [layer (js/document.getElementById id)]
        (let [style (.-style layer)]
          (set! (.-width style) (str width "px"))
          (set! (.-height style) (str height "px")))))))

(defn render-gates! []
  (let [layer (js/document.getElementById "gates-layer")]
    (set! (.-innerHTML layer) "")
    (doseq [gate (:gates @model/state)]
      (.appendChild layer (gate-node gate)))
    (layout-canvas!)))

(defn render-code! []
  (let [text (qasm/generate-qasm @model/state)]
    (reset! editor-source text)
    (io/publish-code! text)))

(defn sync-qubits-input! []
  (when-let [input (js/document.getElementById "qubits")]
    (set! (.-value input) (str (:qubits @model/state)))))

(defn apply-qasm! [text]
  (let [parsed (qasm/parse-qasm text)]
    (when (:ok parsed)
      (model/save-history!)
      (model/clear-future!)
      (swap! model/state assoc
             :qubits (:qubits parsed)
             :cbits (or (:cbits parsed) 0)
             :next-id (count (:gates parsed))
             :defs (:defs parsed)
             :gates (:gates parsed))
      (build-wires!)
      (sync-qubits-input!)
      (render-gates!))))

(defn current-qasm []
  (or @editor-source (qasm/generate-qasm @model/state)))

(defn publish-code! []
  (io/publish-code! (current-qasm)))

(defn on-editor-change! [text]
  (when (string? text)
    (reset! editor-source text)
    (apply-qasm! text)))

(defn add-gate! [gate]
  (model/save-history!)
  (model/clear-future!)
  (swap! model/state model/add-gate-to-state gate)
  (swap! model/state model/ensure-measure-cbits)
  (build-wires!)
  (render-gates!)
  (render-code!))

(defn preview-node []
  (js/document.getElementById "preview"))

(defn hide-preview! []
  (.add (.-classList (preview-node)) "hidden"))

(defn show-preview! [type x y]
  (let [target (model/target-cell @model/state type x y)
        preview (preview-node)]
    (set! (.-innerHTML preview) "")
    (set! (.-cssText (.-style preview)) (outer-style target))
    (doseq [part (gate-shape target)]
      (.appendChild preview part))
    (.remove (.-classList preview) "hidden")))

(defn handle-dragover [event]
  (.preventDefault event)
  (let [data-transfer (.-dataTransfer event)]
    (set! (.-dropEffect data-transfer) "copy"))
  (let [[x y] (local-xy event (.-currentTarget event))]
    (show-preview! @drag-type x y)))

(defn handle-drop [event]
  (.preventDefault event)
  (when @dragging
    (let [data-transfer (.-dataTransfer event)
          drop-type (keyword (.getData data-transfer "text/plain"))
          type (if (contains? model/gate-types drop-type) drop-type @drag-type)
          [x y] (local-xy event (.-currentTarget event))]
      (add-gate! (model/target-cell @model/state type x y))))
  (hide-preview!)
  (reset! drag-type nil)
  (set-drag-active! false))

(defn build-wires! []
  (let [{:keys [qubits cbits]} @model/state
        cwires (if (pos? (or cbits 0)) 1 0)
        layer (js/document.getElementById "wires-layer")]
    (set! (.-innerHTML layer) "")
    (doseq [wire (range qubits)]
      (.appendChild layer
                    (create-el :div
                               {:class "wire"
                                :style (str "left:" model/line-start "px;right:0;top:" (- (model/wire-y wire) 20) "px;")}))
      (.appendChild layer
                    (create-el :img
                               {:class "zero-state"
                                :src "static/zero-state.svg"
                                :width model/gate-size
                                :height model/gate-size
                                :style (str "left:" (- model/zero-center (/ model/gate-size 2)) "px;"
                                            "top:" (model/gate-top wire) "px;")})))
    (doseq [i (range cwires)]
      (.appendChild layer
                    (create-el :div
                               {:class "cwire"
                                :style (str "left:" model/line-start "px;right:0;top:" (- (model/cwire-y i qubits) 20) "px;")})))
    (layout-canvas!)))

(defn set-qubits! [raw]
  (when (and (number? raw) (not (js/isNaN raw)))
    (model/save-history!)
    (model/clear-future!)
    (swap! model/state model/set-qubits-in-state raw)
    (build-wires!)
    (render-gates!)
    (render-code!)))

(defn palette-gate [type]
  (let [gate (create-el :img
                        {:class "palette-gate"
                         :src (model/gate-src type)
                         :width model/gate-size
                         :height model/gate-size
                         :draggable "true"})]
    (.addEventListener gate "dragstart"
                       (fn [event]
                         (reset! drag-type type)
                         (set-drag-active! true)
                         (let [data-transfer (.-dataTransfer event)]
                           (.setData data-transfer "text/plain" (name type))
                           (set! (.-effectAllowed data-transfer) "copy"))))
    (.addEventListener gate "dragend"
                       (fn [_]
                         (hide-preview!)
                         (reset! drag-type nil)
                         (set-drag-active! false)))
    gate))

(defn handle-qubits-input [event]
  (set-qubits! (js/parseInt (.-value (.-target event)) 10)))

(defn handle-qubits-change [event]
  (let [input (.-target event)]
    (set-qubits! (js/parseInt (.-value input) 10))
    (set! (.-value input) (str (:qubits @model/state)))))

(defn operations-panel []
  (let [qubits-input (create-el :input
                                {:id "qubits"
                                 :type "number"
                                 :min model/min-qubits
                                 :max model/max-qubits
                                 :step 1
                                 :value (:qubits @model/state)})]
    (.addEventListener qubits-input "input" handle-qubits-input)
    (.addEventListener qubits-input "change" handle-qubits-change)
    (create-el :aside
               {:id "operations"}
               (create-el :section
                          {:class "panel-section palette-section"}
                          (create-el :h2 {} "Operations")
                          (apply create-el :div {:id "palette"} (map palette-gate model/palette-types)))
               (create-el :section
                          {:class "panel-section qubits-section"}
                          (create-el :label {:for "qubits"} "Number of qubits")
                          qubits-input))))

(defn canvas-panel []
  (create-el :div
             {:id "canvas"}
             (create-el :div {:id "wires-layer"})
             (create-el :div {:id "gates-layer"})
             (create-el :div
                        {:id "preview"
                         :class "gate-preview hidden"})))

(defn toggle-settings! []
  (when-let [panel (js/document.getElementById "settings-panel")]
    (.toggle (.-classList panel) "hidden")))

(def dark-pref-key dom/dark-pref-key)

(defn dark-enabled? []
  (dom/dark-enabled?))

(defn apply-dark! [enabled]
  (dom/apply-dark! enabled))

(defn set-dark! [enabled]
  (dom/apply-dark! enabled)
  (try
    (.setItem (.-localStorage js/window) dark-pref-key (str enabled))
    (catch js/Error _ nil)))

(def snapshot-pref-key "qc-schematic-snapshot-minutes")
(def default-snapshot-minutes 1)
(def min-snapshot-minutes 1)
(def max-snapshot-minutes 1440)

(defn clamp-snapshot-minutes [raw]
  (let [n (js/parseInt raw 10)]
    (if (js/isNaN n)
      default-snapshot-minutes
      (max min-snapshot-minutes (min max-snapshot-minutes n)))))

(defn snapshot-minutes []
  (try
    (let [raw (.getItem (.-localStorage js/window) snapshot-pref-key)]
      (if (nil? raw) default-snapshot-minutes (clamp-snapshot-minutes raw)))
    (catch js/Error _ default-snapshot-minutes)))

(defn set-snapshot-minutes! [raw]
  (let [minutes (clamp-snapshot-minutes raw)]
    (try
      (.setItem (.-localStorage js/window) snapshot-pref-key (str minutes))
      (catch js/Error _ nil))
    (io/set-snapshot-period! minutes)
    minutes))

(defn settings-panel []
  (let [dark (create-el :input {:id "dark-toggle" :type "checkbox"})
        period (create-el :input
                          {:id "snapshot-period"
                           :type "number"
                           :min min-snapshot-minutes
                           :max max-snapshot-minutes
                           :step 1
                           :value (snapshot-minutes)})
        close (create-el :button {:id "settings-close" :type "button"} "Close")
        panel (create-el :div
                         {:id "settings-panel"
                          :class "settings-overlay hidden"}
                         (create-el :div
                                    {:class "settings-card"}
                                    (create-el :h2 {} "Settings")
                                    (create-el :label
                                               {:class "settings-row"}
                                               dark
                                               (create-el :span {} "Dark mode"))
                                    (create-el :label
                                               {:class "settings-row"}
                                               (create-el :span {} "Snapshot period (minutes)")
                                               period)
                                    close))]
    (let [enabled (dark-enabled?)]
      (set! (.-checked dark) enabled)
      (apply-dark! enabled))
    (set! (.-value period) (str (snapshot-minutes)))
    (.addEventListener dark "change" (fn [_] (set-dark! (.-checked dark))))
    (.addEventListener period "change"
                       (fn [_]
                         (set! (.-value period) (str (set-snapshot-minutes! (.-value period))))))
    (.addEventListener close "click" (fn [_] (toggle-settings!)))
    panel))

(defn undo! []
  (model/undo!)
  (build-wires!)
  (render-gates!)
  (sync-qubits-input!)
  (render-code!))

(defn redo! []
  (model/redo!)
  (build-wires!)
  (render-gates!)
  (sync-qubits-input!)
  (render-code!))

(defn setup-ipc! []
  (io/setup! {:get-qasm current-qasm
              :on-import (fn [content]
                           (apply-qasm! content)
                           (render-code!))
              :get-svg (fn [] (export/circuit->svg @model/state))
              :on-settings toggle-settings!
              :snapshot-minutes (snapshot-minutes)
              :on-editor-change on-editor-change!
              :on-request-editor-code publish-code!
              :on-undo undo!
              :on-redo redo!}))

(defn init []
  (let [root (js/document.getElementById "app")]
    (.appendChild root (operations-panel))
    (.appendChild root (canvas-panel))
    (.appendChild root (settings-panel)))
  (build-wires!)
  (let [canvas (js/document.getElementById "canvas")]
    (.addEventListener canvas "dragover" handle-dragover)
    (.addEventListener canvas "dragleave" (fn [_] (hide-preview!)))
    (.addEventListener canvas "drop" handle-drop))
  (.addEventListener js/window "resize" (fn [_] (layout-canvas!)))
  (render-gates!)
  (render-code!)
  (setup-ipc!))
