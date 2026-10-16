(ns app.editor-test
  (:require [cljs.test :refer [deftest testing is use-fixtures async]]
            ["jsdom" :refer [JSDOM]]
            [clojure.string :as string]
            [app.editor :as editor]))

(defn setup-dom []
  (let [dom (JSDOM. "<!DOCTYPE html><html><body><div id=\"app\"></div></body></html>"
                    (js-obj "url" "http://localhost/"))
        window (.-window dom)]
    (aset js/globalThis "window" window)
    (aset js/globalThis "document" (.-document window))
    (aset js/globalThis "Event" (.-Event window))
    (editor/cancel-change!)
    (reset! editor/change-timer nil)
    (aset (.-window js/globalThis) "electronAPI" nil)))

(use-fixtures :each {:before setup-dom})

(defn el [id] (js/document.getElementById id))

(defn ev
  ([type] (ev type {}))
  ([type props]
   (let [e (js/Event. type)]
     (doseq [[k v] props] (aset e (name k) v))
     e)))

(defn stub-api [calls]
  (let [api (js-obj)]
    (aset api "sendEditorChange"
          (fn [code] (swap! calls update :sent (fnil conj []) code)))
    (aset api "onCircuitCode" (fn [cb] (swap! calls assoc :on-code cb)))
    (aset api "editorReady" (fn [] (swap! calls assoc :ready true)))
    api))

(defn with-editor-api [f]
  (let [calls (atom {})]
    (aset (.-window js/globalThis) "electronAPI" (stub-api calls))
    (try
      (f calls)
      (finally
        (aset (.-window js/globalThis) "electronAPI" nil)))))

(defn- line-count [s] (inc (count (re-seq #"\n" s))))

(deftest init-builds-editor-test
  (editor/init)
  (is (some? (el "code-panel")))
  (is (= "TEXTAREA" (.-tagName (el "qasm"))))
  (is (= "Editor" (.-textContent (.. (el "code-panel") (querySelector "h2")))))
  (is (= "valid" (.-textContent (el "qasm-status"))))
  (is (= "1" (.-textContent (el "line-numbers"))))
  (is (true? (.contains (.-classList (js/document.getElementById "app")) "editor-window")))
  (is (= 1 (.-length (.querySelectorAll (js/document.getElementById "app") "aside")))))

(deftest init-announces-ready-test
  (with-editor-api
    (fn [calls]
      (editor/init)
      (is (true? (:ready @calls)))
      (is (fn? (:on-code @calls))))))

(deftest line-numbers-test
  (editor/init)
  (let [editor (el "qasm")
        gutter (el "line-numbers")]
    (is (some? gutter))
    (testing "the gutter mirrors the editor contents"
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
  (is (= "1" (editor/line-number-string "")))
  (is (= "1\n2\n3" (editor/line-number-string "a\nb\nc")))
  (is (= "1\n2" (editor/line-number-string "a\n"))))

(deftest validation-status-test
  (editor/init)
  (let [editor (el "qasm")
        status (el "qasm-status")]
    (is (= "valid" (.-textContent status)))
    (testing "a grammatical program is reported as valid"
      (set! (.-value editor) "qubit[1] q;\nh q[0];")
      (editor/update-validation!)
      (is (= "valid" (.-textContent status)))
      (is (false? (.contains (.-classList status) "invalid"))))
    (testing "a malformed program is reported with a line number"
      (set! (.-value editor) "qubit[1] q;\nh q[0]")
      (editor/update-validation!)
      (is (true? (.contains (.-classList status) "invalid")))
      (is (re-find #"invalid: line 2" (.-textContent status)))
      (is (true? (.contains (.-classList editor) "invalid"))))))

(deftest input-schedules-change-test
  (editor/init)
  (let [editor (el "qasm")]
    (is (nil? @editor/change-timer))
    (set! (.-value editor) "qubit[1] q;")
    (.dispatchEvent editor (ev "input" {}))
    (is (some? @editor/change-timer))
    (testing "a new keystroke replaces the pending timer"
      (let [timer @editor/change-timer]
        (.dispatchEvent editor (ev "input" {}))
        (is (not= timer @editor/change-timer))))))

(deftest blur-flushes-change-test
  (with-editor-api
    (fn [calls]
      (editor/init)
      (let [editor (el "qasm")]
        (set! (.-value editor) "qubit[1] q;\nx q[0];")
        (.dispatchEvent editor (ev "input" {}))
        (is (nil? (:sent @calls)))
        (.dispatchEvent editor (ev "blur" {}))
        (is (= ["qubit[1] q;\nx q[0];"] (:sent @calls)))
        (is (nil? @editor/change-timer))
        (is (= "valid" (.-textContent (el "qasm-status"))))))))

(deftest debounce-sends-change-test
  (async done
    (let [calls (atom {})
          api (stub-api calls)]
      (aset (.-window js/globalThis) "electronAPI" api)
      (with-redefs [editor/change-delay-ms 10]
        (editor/init)
        (let [editor (el "qasm")]
          (set! (.-value editor) "qubit[1] q;")
          (.dispatchEvent editor (ev "input" {}))
          (is (nil? (:sent @calls)))
          (js/setTimeout
           (fn []
             (is (= ["qubit[1] q;"] (:sent @calls)))
             (is (nil? @editor/change-timer))
             (aset (.-window js/globalThis) "electronAPI" nil)
             (done))
           60))))))

(deftest flush-without-api-test
  (editor/init)
  (set! (.-value (el "qasm")) "h q[0]")
  (editor/flush-change!)
  (is (nil? @editor/change-timer))
  (is (true? (.contains (.-classList (el "qasm-status")) "invalid"))))

(deftest circuit-code-updates-editor-test
  (with-editor-api
    (fn [calls]
      (editor/init)
      ((:on-code @calls) "qubit[2] q;\nh q[0];")
      (is (= "qubit[2] q;\nh q[0];" (.-value (el "qasm"))))
      (is (= "1\n2" (.-textContent (el "line-numbers"))))
      (is (= "valid" (.-textContent (el "qasm-status"))))
      (testing "incoming code cancels a pending change so stale text is not sent"
        (set! (.-value (el "qasm")) "typed")
        (.dispatchEvent (el "qasm") (ev "input" {}))
        (is (some? @editor/change-timer))
        ((:on-code @calls) "qubit[1] q;")
        (is (nil? @editor/change-timer))
        (is (= "qubit[1] q;" (.-value (el "qasm")))))
      (testing "non-string payloads leave the editor untouched"
        ((:on-code @calls) 42)
        (is (= "qubit[1] q;" (.-value (el "qasm"))))))))
