(ns app.ui-test
  (:require [cljs.test :refer [deftest testing is use-fixtures]]
            ["jsdom" :refer [JSDOM]]
            [app.model :as model]
            [app.ui :as ui]))

(defn setup-dom []
  (let [dom (JSDOM. "<!DOCTYPE html><html><body><div id=\"app\"></div></body></html>")
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

(deftest init-builds-ui-test
  (ui/init)
  (is (some? (el "operations")))
  (is (some? (el "canvas")))
  (is (some? (el "code-panel")))
  (is (= "TEXTAREA" (.-tagName (el "qasm"))))
  (is (= 4 (.-length (.querySelectorAll (el "wires-layer") ".wire"))))
  (is (= 4 (.-length (.querySelectorAll (el "wires-layer") ".zero-state"))))
  (is (= (count model/palette-types)
         (.-length (.querySelectorAll (el "palette") ".palette-gate")))))

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
    (aset (.-window js/globalThis) "electronAPI" api)
    (ui/init)
    (ui/add-gate! {:type :h :wire 0 :col 0})
    ((:export @calls))
    (is (re-find #"h q\[0\];" (:exported @calls)))
    ((:svg @calls))
    (is (re-find #"<svg" (:svg-out @calls)))
    ((:import @calls))
    (is (= [:x] (map :type (:gates @model/state))))
    (aset (.-window js/globalThis) "electronAPI" nil)))
