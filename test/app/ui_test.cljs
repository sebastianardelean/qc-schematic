(ns app.ui-test
  (:require [cljs.test :refer [deftest testing is use-fixtures]]
            ["jsdom" :refer [JSDOM]]
            [clojure.string :as string]
            [app.model :as model]
            [app.ui :as ui]))

(defn setup-dom []
  (let [dom (JSDOM. "<!DOCTYPE html><html><body><div id=\"app\"></div></body></html>"
                    (js-obj "url" "http://localhost/"))
        window (.-window dom)]
    (aset js/globalThis "window" window)
    (aset js/globalThis "document" (.-document window))
    (aset js/globalThis "Event" (.-Event window))
    (reset! model/state model/initial-state)
    (reset! ui/dragging false)
    (reset! ui/drag-type nil)
    (reset! ui/target-drag nil)))

(use-fixtures :each (fn [f] (setup-dom) (f)))

(defn el [id] (js/document.getElementById id))

(defn ev
  ([type] (ev type {}))
  ([type props]
   (let [e (js/Event. type)]
     (doseq [[k v] props] (aset e (name k) v))
     e)))

(defn data-transfer [m]
  (let [dt (js-obj)]
    (aset dt "getData" (fn [_] (aget m "text/plain")))
    (aset dt "setData" (fn [k v] (aset m k v)))
    (aset dt "dropEffect" "")
    (aset dt "effectAllowed" "")
    dt))

(deftest canvas-layout-test
  (ui/init)
  (let [wires (el "wires-layer")]
    (is (= "480px" (.-width (.-style wires))))
    (is (= "470px" (.-height (.-style wires)))))
  (testing "layers grow when gates extend the content"
    (ui/add-gate! {:type :h :wire 0 :col 20})
    (is (> (js/parseInt (.-width (.-style (el "gates-layer"))) 10) 480))
    (is (> (js/parseInt (.-width (.-style (el "wires-layer"))) 10) 480))))

(deftest init-builds-ui-test
  (ui/init)
  (is (some? (el "operations")))
  (is (some? (el "canvas")))
  (is (some? (el "code-panel")))
  (is (= "TEXTAREA" (.-tagName (el "qasm"))))
  (is (= 4 (.-length (.querySelectorAll (el "wires-layer") ".wire"))))
  (is (= 4 (.-length (.querySelectorAll (el "wires-layer") ".zero-state"))))
  (is (nil? (el "cbits")))
  (is (= (count model/palette-types)
         (.-length (.querySelectorAll (el "palette") ".palette-gate")))))

(deftest resize-layout-test
  (ui/init)
  (let [canvas (el "canvas")]
    (testing "layers fill the viewport when it is wider than the content"
      (js/Object.defineProperty canvas "clientWidth" (js-obj "value" 900 "configurable" true))
      (.dispatchEvent js/window (ev "resize" {}))
      (is (= "900px" (.-width (.-style (el "wires-layer")))))
      (is (= "900px" (.-width (.-style (el "gates-layer"))))))
    (testing "content wider than the viewport is preserved"
      (js/Object.defineProperty canvas "clientWidth" (js-obj "value" 200 "configurable" true))
      (ui/layout-canvas!)
      (is (= (str (:width (model/canvas-size @model/state)) "px")
             (.-width (.-style (el "wires-layer"))))))))

(deftest render-code-test
  (ui/init)
  (ui/add-gate! {:type :h :wire 0 :col 0})
  (is (re-find #"h q\[0\];" (.-value (el "qasm"))))
  (is (= 1 (.-length (.querySelectorAll (el "gates-layer") ".gate")))))

(deftest remove-gate-test
  (ui/init)
  (ui/add-gate! {:type :h :wire 0 :col 0})
  (let [id (:id (first (:gates @model/state)))]
    (ui/remove-gate! id)
    (is (empty? (:gates @model/state)))
    (is (= 0 (.-length (.querySelectorAll (el "gates-layer") ".gate"))))))

(deftest set-qubits-test
  (ui/init)
  (ui/set-qubits! 2)
  (is (= 2 (:qubits @model/state)))
  (is (= 2 (.-length (.querySelectorAll (el "wires-layer") ".wire")))))

(deftest set-qubits-clamp-test
  (ui/init)
  (ui/set-qubits! 99)
  (is (= model/max-qubits (:qubits @model/state))))

(deftest apply-qasm-test
  (ui/init)
  (ui/apply-qasm! "qubit[2] q;\nh q[0];\ncx q[0], q[1];")
  (is (= 2 (:qubits @model/state)))
  (is (= [:h :cx] (map :type (:gates @model/state))))
  (is (= "2" (.-value (el "qubits")))))

(deftest apply-qasm-cbits-test
  (ui/init)
  (ui/apply-qasm! "qubit[2] q;\nbit[2] c;\nh q[0];")
  (is (= 2 (:cbits @model/state)))
  (is (= 1 (.-length (.querySelectorAll (el "wires-layer") ".cwire")))))

(deftest current-qasm-test
  (ui/init)
  (is (re-find #"OPENQASM 3;" (ui/current-qasm)))
  (ui/add-gate! {:type :x :wire 0 :col 0})
  (is (re-find #"x q\[0\];" (ui/current-qasm))))

(deftest drag-drop-test
  (ui/init)
  (let [gate-img (first (array-seq (.querySelectorAll (el "palette") ".palette-gate")))
        start (ev "dragstart" {"dataTransfer" (data-transfer (js-obj))})]
    (.dispatchEvent gate-img start)
    (is (= :h @ui/drag-type))
    (is (true? @ui/dragging))
    (let [canvas (el "canvas")
          drop (ev "drop" {"dataTransfer" (data-transfer (js-obj))
                           "clientX" 100 "clientY" 50})]
      (aset drop "currentTarget" canvas)
      (.dispatchEvent canvas drop)
      (is (= 1 (count (:gates @model/state))))
      (is (nil? @ui/drag-type))
      (is (false? @ui/dragging)))))

(deftest dragover-preview-test
  (ui/init)
  (reset! ui/drag-type :h)
  (let [canvas (el "canvas")
        e (ev "dragover" {"dataTransfer" (data-transfer (js-obj))
                          "clientX" 120 "clientY" 60})]
    (aset e "currentTarget" canvas)
    (.dispatchEvent canvas e)
    (is (false? (.contains (.-classList (el "preview")) "hidden")))
    (ui/hide-preview!)
    (is (true? (.contains (.-classList (el "preview")) "hidden")))))

(deftest cx-target-drag-test
  (ui/init)
  (ui/add-gate! (model/target-cell @model/state :cx model/origin-x model/top-pad))
  (let [gate-node (first (array-seq (.querySelectorAll (el "gates-layer") ".gate")))
        target-part (.querySelector gate-node ".cx-target")
        gid (:id (first (:gates @model/state)))
        down (ev "pointerdown" {"pointerId" 1 "target" target-part})]
    (ui/start-target-drag! down {:id gid :wire 0 :target 1})
    (is (= gid (:id @ui/target-drag)))
    (let [canvas (el "canvas")
          move (ev "pointermove" {"clientX" 100 "clientY" 230
                                  "currentTarget" target-part})]
      (aset move "currentTarget" target-part)
      (.dispatchEvent target-part move)
      (is (= 2 (:wire @ui/target-drag)))
      (let [up (ev "pointerup" {})]
        (ui/target-drag-up up)
        (is (nil? @ui/target-drag))
        (is (= 2 (:target (first (:gates @model/state)))))))))

(deftest measure-drop-test
  (ui/init)
  (reset! ui/drag-type :measure)
  (ui/set-drag-active! true)
  (let [canvas (el "canvas")
        drop (ev "drop" {"dataTransfer" (data-transfer (js-obj))
                         "clientX" 100 "clientY" 50})]
    (aset drop "currentTarget" canvas)
    (.dispatchEvent canvas drop)
    (is (= :measure (:type (first (:gates @model/state)))))
    (is (= 1 (.-length (.querySelectorAll (el "gates-layer") ".gate"))))
    (testing "classical bits default to one per qubit with a single canvas wire"
      (is (= (:qubits @model/state) (:cbits @model/state)))
      (is (= 1 (.-length (.querySelectorAll (el "wires-layer") ".cwire"))))
      (is (re-find #"\bbit\[4\] c;" (.-value (el "qasm")))))
    (testing "measurement links to the classical wire"
      (is (= 1 (.-length (.querySelectorAll (el "gates-layer") ".measure-link")))))))

(deftest barrier-drop-test
  (ui/init)
  (reset! ui/drag-type :barrier)
  (ui/set-drag-active! true)
  (let [canvas (el "canvas")
        drop (ev "drop" {"dataTransfer" (data-transfer (js-obj))
                         "clientX" 100 "clientY" 50})]
    (aset drop "currentTarget" canvas)
    (.dispatchEvent canvas drop)
    (is (= :barrier (:type (first (:gates @model/state)))))
    (is (= [0 1 2 3] (:wires (first (:gates @model/state)))))
    (is (= 1 (.-length (.querySelectorAll (el "gates-layer") ".barrier-line"))))
    (is (re-find #"barrier q\[0\], q\[1\], q\[2\], q\[3\];" (.-value (el "qasm"))))))

(deftest handle-qubits-input-test
  (ui/init)
  (let [input (el "qubits")]
    (set! (.-value input) "3")
    (.dispatchEvent input (ev "input" {"target" input}))
    (is (= 3 (:qubits @model/state)))))

(deftest palette-gate-dragend-test
  (ui/init)
  (let [gate-img (first (array-seq (.querySelectorAll (el "palette") ".palette-gate")))]
    (.dispatchEvent gate-img (ev "dragstart" {"dataTransfer" (data-transfer (js-obj))}))
    (.dispatchEvent gate-img (ev "dragend" {}))
    (is (nil? @ui/drag-type))
    (is (false? @ui/dragging))))

(deftest code-panel-input-test
  (ui/init)
  (let [editor (el "qasm")]
    (set! (.-value editor) "qubit[1] q;\nx q[0];")
    (.dispatchEvent editor (ev "input" {}))
    (.dispatchEvent editor (ev "blur" {}))
    (is (= [:x] (map :type (:gates @model/state))))))

(defn- line-count [s] (inc (count (re-seq #"\n" s))))

(deftest line-numbers-test
  (ui/init)
  (let [editor (el "qasm")
        gutter (el "line-numbers")]
    (is (some? gutter))
    (testing "the gutter mirrors the generated program"
      (is (= "1" (first (string/split-lines (.-textContent gutter)))))
      (is (= (line-count (.-value editor)) (line-count (.-textContent gutter)))))
    (testing "editing the editor updates the gutter"
      (set! (.-value editor) "qubit[2] q;\nh q[0];")
      (.dispatchEvent editor (ev "input" {}))
      (is (= "1\n2" (.-textContent gutter))))
    (testing "a trailing newline adds a numbered line"
      (set! (.-value editor) "a\n")
      (.dispatchEvent editor (ev "input" {}))
      (is (= "1\n2" (.-textContent gutter))))
    (testing "vertical scrolling is mirrored onto the gutter"
      (set! (.-scrollTop editor) 40)
      (.dispatchEvent editor (ev "scroll" {}))
      (is (= 40 (.-scrollTop gutter))))))

(deftest line-number-string-test
  (is (= "1" (ui/line-number-string "")))
  (is (= "1\n2\n3" (ui/line-number-string "a\nb\nc")))
  (is (= "1\n2" (ui/line-number-string "a\n"))))

(deftest qasm-validation-status-test
  (ui/init)
  (let [editor (el "qasm")
        status (el "qasm-status")]
    (is (= "valid" (.-textContent status)))
    (testing "a grammatical program is reported as valid"
      (set! (.-value editor) "qubit[1] q;\nh q[0];")
      (ui/apply-qasm! (.-value editor))
      (is (= "valid" (.-textContent status)))
      (is (false? (.contains (.-classList status) "invalid"))))
    (testing "a malformed program is reported with a line number"
      (set! (.-value editor) "qubit[1] q;\nh q[0]")
      (ui/apply-qasm! (.-value editor))
      (is (true? (.contains (.-classList status) "invalid")))
      (is (re-find #"invalid: line 2" (.-textContent status)))
      (is (true? (.contains (.-classList editor) "invalid"))))))

(deftest setup-ipc-test
  (let [calls (atom {})
        api (js-obj)]
    (aset api "onMenuExport" (fn [cb] (swap! calls assoc :export cb)))
    (aset api "onMenuImport" (fn [cb] (swap! calls assoc :import cb)))
    (aset api "onMenuExportSvg" (fn [cb] (swap! calls assoc :svg cb)))
    (aset api "exportQasm" (fn [code] (swap! calls assoc :exported code)))
    (aset api "importQasm" (fn []
                             (js-obj "then"
                                     (fn [cb]
                                       (cb {:ok true
                                            :content "qubit[1] q;\nx q[0];"})))))
    (aset api "exportSvg" (fn [svg] (swap! calls assoc :svg-out svg)))
    (aset api "onMenuSave" (fn [cb] (swap! calls assoc :save cb)))
    (aset api "saveQasm" (fn [code] (swap! calls assoc :saved code)))
    (aset api "onMenuSaveAs" (fn [cb] (swap! calls assoc :save-as cb)))
    (aset api "saveQasmAs" (fn [code] (swap! calls assoc :saved-as code)))
    (aset api "onRequestSnapshot" (fn [cb] (swap! calls assoc :snapshot cb)))
    (aset api "saveSnapshot" (fn [code] (swap! calls assoc :snapshot-out code)))
    (aset api "onMenuSettings" (fn [cb] (swap! calls assoc :settings cb)))
    (aset api "setSnapshotPeriod" (fn [minutes] (swap! calls assoc :snapshot-period minutes)))
    (aset (.-window js/globalThis) "electronAPI" api)
    (ui/init)
    (is (= ui/default-snapshot-minutes (:snapshot-period @calls)))
    (ui/add-gate! {:type :h :wire 0 :col 0})
    ((:export @calls))
    (is (re-find #"h q\[0\];" (:exported @calls)))
    ((:svg @calls))
    (is (re-find #"<svg" (:svg-out @calls)))
    ((:save @calls))
    (is (re-find #"h q\[0\];" (:saved @calls)))
    ((:save-as @calls))
    (is (re-find #"h q\[0\];" (:saved-as @calls)))
    ((:snapshot @calls))
    (is (re-find #"h q\[0\];" (:snapshot-out @calls)))
    ((:import @calls))
    (is (= [:x] (map :type (:gates @model/state))))
    (aset (.-window js/globalThis) "electronAPI" nil)))

(deftest settings-toggle-test
  (ui/init)
  (let [panel (el "settings-panel")]
    (is (true? (.contains (.-classList panel) "hidden")))
    (ui/toggle-settings!)
    (is (false? (.contains (.-classList panel) "hidden")))
    (.dispatchEvent (el "settings-close") (ev "click" {}))
    (is (true? (.contains (.-classList panel) "hidden")))))

(deftest dark-mode-test
  (ui/init)
  (let [toggle (el "dark-toggle")
        body (.-body js/document)]
    (is (some? toggle))
    (is (false? (.-checked toggle)))
    (is (false? (.contains (.-classList body) "dark")))
    (set! (.-checked toggle) true)
    (.dispatchEvent toggle (ev "change" {}))
    (is (true? (.contains (.-classList body) "dark")))
    (set! (.-checked toggle) false)
    (.dispatchEvent toggle (ev "change" {}))
    (is (false? (.contains (.-classList body) "dark")))))

(deftest snapshot-period-test
  (ui/init)
  (let [input (el "snapshot-period")]
    (is (some? input))
    (is (= "1" (.-value input)))
    (is (= ui/default-snapshot-minutes (ui/snapshot-minutes)))
    (set! (.-value input) "5")
    (.dispatchEvent input (ev "change" {}))
    (is (= 5 (ui/snapshot-minutes)))
    (is (= "5" (.-value input)))
    (testing "values are clamped to the allowed range"
      (set! (.-value input) "9999")
      (.dispatchEvent input (ev "change" {}))
      (is (= ui/max-snapshot-minutes (ui/snapshot-minutes)))
      (is (= (str ui/max-snapshot-minutes) (.-value input)))
      (set! (.-value input) "-3")
      (.dispatchEvent input (ev "change" {}))
      (is (= ui/min-snapshot-minutes (ui/snapshot-minutes))))
    (testing "non numeric input falls back to the default"
      (set! (.-value input) "")
      (.dispatchEvent input (ev "change" {}))
      (is (= ui/default-snapshot-minutes (ui/snapshot-minutes))))))
