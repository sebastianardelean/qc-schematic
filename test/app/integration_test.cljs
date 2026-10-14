(ns app.integration-test
  (:require [cljs.test :refer [deftest testing is use-fixtures]]
            ["jsdom" :refer [JSDOM]]
            [clojure.string :as string]
            [app.model :as model]
            [app.qasm :as qasm]
            [app.export :as export]
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

(def program
  (str "OPENQASM 3;\n"
       "include \"stdgates.inc\";\n"
       "qubit[3] q;\n"
       "bit[3] c;\n"
       "h q[0];\n"
       "cx q[0], q[2];\n"
       "barrier q[0], q[1], q[2];\n"
       "measure q[0];\n"))

(def program-types [:h :cx :barrier :measure])

(deftest qasm-round-trip-integration-test
  (testing "a full program survives generate -> parse -> generate"
    (let [parsed (qasm/parse-qasm program)]
      (is (true? (:ok parsed)))
      (is (= 3 (:qubits parsed)))
      (is (= 3 (:cbits parsed)))
      (is (= program-types (map :type (:gates parsed))))
      (let [regenerated (qasm/generate-qasm parsed)
            reparsed (qasm/parse-qasm regenerated)]
        (is (true? (:ok reparsed)))
        (is (= (:qubits parsed) (:qubits reparsed)))
        (is (= (:cbits parsed) (:cbits reparsed)))
        (is (= (map :type (:gates parsed)) (map :type (:gates reparsed))))
        (is (= (map :col (:gates parsed)) (map :col (:gates reparsed))))
        (is (= (map :wires (:gates parsed)) (map :wires (:gates reparsed))))))))

(deftest multi-module-round-trip-test
  (testing "model -> qasm -> export stays consistent for custom gates"
    (let [state {:qubits 2 :cbits 0
                 :defs {"mygate" {:arity 2 :lines ["gate mygate a, b { cx a, b; }"]}}
                 :gates [{:type :custom :name "mygate" :wire 0 :wires [0 1] :col 0 :id 0}
                         {:type :barrier :wire 0 :wires [0 1] :col 1 :id 1}]}
          text (qasm/generate-qasm state)
          parsed (qasm/parse-qasm text)]
      (is (true? (:ok parsed)))
      (is (= [:custom :barrier] (map :type (:gates parsed))))
      (is (= [0 1] (:wires (first (:gates parsed)))))
      (is (= [0 1] (:wires (second (:gates parsed)))))
      (let [svg (export/circuit->svg parsed)]
        (is (re-find #"<svg" svg))
        (is (re-find #">mygate<" svg))
        (is (re-find #"stroke-dasharray" svg))))))

(deftest editor-to-canvas-integration-test
  (testing "an imported program renders across every canvas layer"
    (ui/init)
    (ui/apply-qasm! program)
    (is (= 3 (:qubits @model/state)))
    (is (= 3 (:cbits @model/state)))
    (is (= program-types (map :type (:gates @model/state))))
    (is (= 4 (.-length (.querySelectorAll (el "gates-layer") ".gate"))))
    (is (= 1 (.-length (.querySelectorAll (el "gates-layer") ".barrier-line"))))
    (is (= 1 (.-length (.querySelectorAll (el "gates-layer") ".measure-link"))))
    (is (= 3 (.-length (.querySelectorAll (el "wires-layer") ".wire"))))
    (is (= 1 (.-length (.querySelectorAll (el "wires-layer") ".cwire"))))
    (is (= "3" (.-value (el "qubits"))))))

(deftest editor-textarea-blur-integration-test
  (testing "editing the textarea and blurring rebuilds the canvas and canonicalises the code"
    (ui/init)
    (let [editor (el "qasm")]
      (set! (.-value editor) program)
      (.dispatchEvent editor (ev "blur" {})))
    (is (= program-types (map :type (:gates @model/state))))
    ;; the barrier and measure each claim their own column
    (is (= [0 1 2 3] (map :col (:gates @model/state))))
    (is (re-find #"barrier q\[0\], q\[1\], q\[2\];" (.-value (el "qasm"))))))

(deftest ui-export-integration-test
  (testing "the SVG produced from the live UI state contains every element"
    (ui/init)
    (ui/apply-qasm! program)
    (let [svg (export/circuit->svg @model/state)]
      (is (re-find #"<svg" svg))
      (is (re-find #">H<" svg))
      (is (re-find #"<circle" svg))
      (is (re-find #">M<" svg))
      (is (re-find #"stroke-dasharray" svg)))))

(deftest bridge-save-open-integration-test
  (testing "renderer, io bridge and file round-trip through the electronAPI contract"
    (let [store (atom nil)
          calls (atom {})
          api (js-obj)]
      (aset api "onMenuSave" (fn [cb] (swap! calls assoc :save cb)))
      (aset api "saveQasm" (fn [code] (reset! store code)))
      (aset api "onMenuImport" (fn [cb] (swap! calls assoc :import cb)))
      (aset api "importQasm" (fn []
                               (js-obj "then"
                                       (fn [cb] (cb {:ok true :content @store})))))
      (aset api "onMenuExport" (fn [_] nil))
      (aset api "exportQasm" (fn [_] nil))
      (aset api "onMenuExportSvg" (fn [_] nil))
      (aset api "exportSvg" (fn [_] nil))
      (aset api "onMenuSaveAs" (fn [_] nil))
      (aset api "saveQasmAs" (fn [_] nil))
      (aset api "onRequestSnapshot" (fn [_] nil))
      (aset api "saveSnapshot" (fn [_] nil))
      (aset api "onMenuSettings" (fn [_] nil))
      (aset api "setSnapshotPeriod" (fn [_] nil))
      (aset (.-window js/globalThis) "electronAPI" api)
      (ui/init)
      (set! (.-value (el "qasm")) program)
      ((:save @calls))
      (is (string? @store))
      (is (re-find #"barrier q\[0\], q\[1\], q\[2\];" @store))
      (testing "a different circuit is replaced when the saved file is opened"
        (ui/apply-qasm! "qubit[1] q;\nx q[0];")
        (is (= [:x] (map :type (:gates @model/state)))))
      ((:import @calls))
      (is (= program-types (map :type (:gates @model/state))))
      (is (= 3 (:qubits @model/state)))
      (is (= "3" (.-value (el "qubits"))))
      (is (re-find #"barrier q\[0\], q\[1\], q\[2\];" (.-value (el "qasm"))))
      (aset (.-window js/globalThis) "electronAPI" nil))))

(deftest invalid-edit-keeps-last-valid-integration-test
  (testing "an invalid textarea edit is ignored while the canvas keeps the last good state"
    (ui/init)
    (ui/apply-qasm! program)
    (set! (.-value (el "qasm")) "qubit[1] q;\nbogus q[0];")
    (.dispatchEvent (el "qasm") (ev "blur" {}))
    (is (= program-types (map :type (:gates @model/state))))
    (is (string/includes? (.-value (el "qasm")) "bogus"))))
