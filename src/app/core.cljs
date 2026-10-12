(ns app.core
  (:require [clojure.string :as string]))

(def gate-types
  {:x {:qasm "x" :src "static/x.svg"}
   :y {:qasm "y" :src "static/y.svg"}
   :z {:qasm "z" :src "static/z.svg"}
   :h {:qasm "h" :src "static/hadamard.svg"}
   :cx {:qasm "cx" :src "static/cx.svg"}})

(def palette-types [:h :x :y :z :cx])

(defn gate-src [type]
  (get-in gate-types [type :src] "static/hadamard.svg"))

(def min-qubits 1)
(def max-qubits 12)
(def wire-gap 90)
(def col-gap 70)
(def top-pad 50)
(def line-start 70)
(def zero-center 35)
(def origin-x 100)
(def gate-size 46)

(def state (atom {:qubits 4 :next-id 0 :gates []}))

(def dragging (atom false))

(defn set-drag-active! [active]
  (reset! dragging active)
  (let [app (js/document.getElementById "app")
        classes (.-classList app)]
    (if active
      (.add classes "drag-active")
      (.remove classes "drag-active"))))

(declare remove-gate! render-gates! render-code!)

(def drag-type (atom nil))

(defn create-el [tag props & children]
  (let [node (js/document.createElement (name tag))
        style (.-style node)]
    (doseq [[k v] props]
      (cond
        (= k :class) (set! (.-className node) v)
        (= k :style) (set! (.-cssText style) v)
        :else (.setAttribute node (name k) (str v))))
    (doseq [c children]
      (when c
        (.appendChild node (if (string? c) (js/document.createTextNode c) c))))
    node))

(defn wire-y [wire]
  (+ top-pad (* wire wire-gap)))

(defn col-x [col]
  (+ origin-x (* col col-gap)))

(defn gate-left [col]
  (- (col-x col) (/ gate-size 2)))

(defn gate-top [wire]
  (- (wire-y wire) (/ gate-size 2)))

(defn snap-wire [y qubits]
  (int (-> (/ (- y top-pad) wire-gap) Math/round (max 0) (min (dec qubits)))))

(defn snap-col [x]
  (int (max 0 (Math/round (/ (- x origin-x) col-gap)))))

(defn gate-covers? [gate wire col]
  (and (= (:col gate) col)
       (or (= (:wire gate) wire)
           (= (:target gate) wire))))

(defn occupied? [gates wire col]
  (boolean (some #(gate-covers? % wire col) gates)))

(defn free-col?
  [gates col wires]
  (every? #(not (occupied? gates % col)) wires))

(defn nearest-free-col [gates col wires]
  (if (free-col? gates col wires)
    col
    (let [candidates (mapcat (fn [d] [(+ col d) (- col d)]) (range 1 64))]
      (or (first (filter #(free-col? gates % wires) candidates))
          col))))

(defn cx-target [control qubits]
  (if (< control (dec (dec qubits)))
    (inc control)
    (dec control)))

(defn target-cell [type x y]
  (let [{:keys [qubits gates]} @state
        wire (snap-wire y qubits)
        target (when (= type :cx) (cx-target wire qubits))
        wires (if target [wire target] [wire])
        col (nearest-free-col gates (snap-col x) wires)]
    {:type type :wire wire :col col :target target}))

(defn local-xy [event node]
  (let [rect (.getBoundingClientRect node)]
    [(- (.-clientX event) (.-left rect) (.-scrollLeft node))
     (- (.-clientY event) (.-top rect) (.-scrollTop node))]))

(defn gate-shape [{:keys [type wire target]}]
  (if (= type :cx)
    (let [above (min wire target)
          below (max wire target)
          control-at-top? (= wire above)
          span (- (wire-y below) (wire-y above))
          line (create-el :div
                          {:class "cx-line"
                           :style (str "top:" (/ gate-size 2) "px;height:" span "px;")})
          control (create-el :img
                             {:class "cx-part"
                              :src "static/control.svg"
                              :width gate-size
                              :height gate-size
                              :style (str "top:" (if control-at-top? 0 span) "px;")})
          target (create-el :img
                            {:class "cx-part"
                             :src "static/target.svg"
                             :width gate-size
                             :height gate-size
                             :style (str "top:" (if control-at-top? span 0) "px;")})]
      [line control target])
    [(create-el :img
                {:class "gate-img"
                 :src (gate-src type)
                 :width gate-size
                 :height gate-size})]))

(defn outer-style [{:keys [type wire target col]}]
  (if (= type :cx)
    (let [above (min wire target)
          below (max wire target)
          top-y (wire-y above)]
      (str "left:" (gate-left col) "px;top:" (- top-y (/ gate-size 2)) "px;"
           "width:" gate-size "px;height:" (+ (- (wire-y below) top-y) gate-size) "px;"))
    (str "left:" (gate-left col) "px;top:" (gate-top wire) "px;"
         "width:" gate-size "px;height:" gate-size "px;")))

(defn gate-node [{:keys [id] :as gate}]
  (let [node (create-el :div
                        {:class "gate"
                         :title "Double-click to remove"
                         :style (outer-style gate)})]
    (doseq [part (gate-shape gate)]
      (.appendChild node part))
    (.addEventListener node "dblclick" (fn [_] (remove-gate! id)))
    node))

(defn remove-gate! [id]
  (swap! state update :gates (fn [gates] (filterv #(not= (:id %) id) gates)))
  (render-gates!)
  (render-code!))

(defn render-gates! []
  (let [layer (js/document.getElementById "gates-layer")]
    (set! (.-innerHTML layer) "")
    (doseq [gate (:gates @state)]
      (.appendChild layer (gate-node gate)))))

(defn gate-qasm [{:keys [type wire target]}]
  (if (= type :cx)
    (str "cx q[" wire "], q[" target "];")
    (str (get-in gate-types [type :qasm] "id") " q[" wire "];")))

(defn generate-qasm [{:keys [qubits gates]}]
  (let [header ["OPENQASM 3;"
                "include \"stdgates.inc\";"
                ""
                (str "qubit[" qubits "] q;")]
        body (map gate-qasm (sort-by (juxt :col :wire :id) gates))]
    (str (string/join "\n" (concat header body)) "\n")))

(defn render-code! []
  (let [node (js/document.getElementById "qasm")]
    (set! (.-textContent node) (generate-qasm @state))))

(defn add-gate! [{:keys [type wire col target]}]
  (swap! state
         (fn [current]
           (-> current
               (update :next-id inc)
               (update :gates conj {:id (:next-id current)
                                    :type type
                                    :wire wire
                                    :col col
                                    :target target}))))
  (render-gates!)
  (render-code!))

(defn preview-node []
  (js/document.getElementById "preview"))

(defn hide-preview! []
  (.add (.-classList (preview-node)) "hidden"))

(defn show-preview! [type x y]
  (let [target (target-cell type x y)
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
          type (if (contains? gate-types drop-type) drop-type @drag-type)
          [x y] (local-xy event (.-currentTarget event))]
      (add-gate! (target-cell type x y))))
  (hide-preview!)
  (set-drag-active! false))

(defn build-wires! []
  (let [{:keys [qubits]} @state
        layer (js/document.getElementById "wires-layer")
        layer-style (.-style layer)]
    (set! (.-height layer-style) (str (+ top-pad (* qubits wire-gap)) "px"))
    (set! (.-innerHTML layer) "")
    (doseq [wire (range qubits)]
      (.appendChild layer
                    (create-el :div
                               {:class "wire"
                                :style (str "left:" line-start "px;right:0;top:" (- (wire-y wire) 20) "px;")}))
      (.appendChild layer
                    (create-el :img
                               {:class "zero-state"
                                :src "static/zero-state.svg"
                                :width gate-size
                                :height gate-size
                                :style (str "left:" (- zero-center (/ gate-size 2)) "px;"
                                            "top:" (gate-top wire) "px;")})))))

(defn set-qubits! [raw]
  (when (and (number? raw) (not (js/isNaN raw)))
    (let [qubits (max min-qubits (min max-qubits (int raw)))]
      (swap! state
             (fn [current]
               (-> current
                   (assoc :qubits qubits)
                   (update :gates (fn [gates] (filterv #(< (:wire %) qubits) gates))))))
      (build-wires!)
      (render-gates!)
      (render-code!))))

(defn palette-gate [type]
  (let [gate (create-el :img
                        {:class "palette-gate"
                         :src (gate-src type)
                         :width gate-size
                         :height gate-size
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
    (set! (.-value input) (str (:qubits @state)))))

(defn operations-panel []
  (let [qubits-input (create-el :input
                                {:id "qubits"
                                 :type "number"
                                 :min min-qubits
                                 :max max-qubits
                                 :step 1
                                 :value (:qubits @state)})]
    (.addEventListener qubits-input "input" handle-qubits-input)
    (.addEventListener qubits-input "change" handle-qubits-change)
    (create-el :aside
               {:id "operations"}
               (create-el :section
                          {:class "panel-section palette-section"}
                          (create-el :h2 {} "Operations")
                          (apply create-el :div {:id "palette"} (map palette-gate palette-types)))
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

(defn code-panel []
  (create-el :aside
             {:id "code-panel"}
             (create-el :div
                        {:class "panel-section code-header"}
                        (create-el :h2 {} "OpenQASM 3"))
             (create-el :pre {:id "qasm"})))

(defn init []
  (let [root (js/document.getElementById "app")]
    (.appendChild root (operations-panel))
    (.appendChild root (canvas-panel))
    (.appendChild root (code-panel)))
  (build-wires!)
  (let [canvas (js/document.getElementById "canvas")]
    (.addEventListener canvas "dragover" handle-dragover)
    (.addEventListener canvas "dragleave" (fn [_] (hide-preview!)))
    (.addEventListener canvas "drop" handle-drop))
  (render-gates!)
  (render-code!))

(init)
