(ns app.editor
  (:require [clojure.string :as string]
            [app.dom :as dom]
            [app.grammar :as grammar]
            [app.io :as io]))

(def change-delay-ms 250)
(def change-timer (atom nil))

(defn editor-node []
  (js/document.getElementById "qasm"))

(defn editor-text []
  (when-let [node (editor-node)]
    (.-value node)))

(defn line-number-string [text]
  (let [lines (inc (count (re-seq #"\n" (or text ""))))]
    (string/join "\n" (map inc (range lines)))))

(defn sync-editor-scroll! []
  (let [gutter (js/document.getElementById "line-numbers")
        editor (editor-node)]
    (when (and gutter editor)
      (set! (.-scrollTop gutter) (.-scrollTop editor)))))

(defn update-line-numbers! []
  (when-let [gutter (js/document.getElementById "line-numbers")]
    (set! (.-textContent gutter) (line-number-string (editor-text)))
    (sync-editor-scroll!)))

(defn update-validation! []
  (when-let [status (js/document.getElementById "qasm-status")]
    (let [result (grammar/validate (editor-text))
          node (editor-node)
          classes (.-classList status)]
      (if (:ok result)
        (do
          (set! (.-textContent status) "valid")
          (.remove classes "invalid")
          (when node (.remove (.-classList node) "invalid")))
        (let [errs (:errors result)
              first (first errs)
              more (dec (count errs))]
          (set! (.-textContent status)
                (str "invalid: line " (:line first) ": " (:message first)
                     (when (pos? more) (str " (+" more " more)"))))
          (.add classes "invalid")
          (when node (.add (.-classList node) "invalid")))))))

(defn cancel-change! []
  (when @change-timer
    (js/clearTimeout @change-timer)
    (reset! change-timer nil)))

(defn flush-change! []
  (cancel-change!)
  (when-let [text (editor-text)]
    (io/send-editor-change! text))
  (update-validation!))

(defn schedule-change! []
  (cancel-change!)
  (reset! change-timer (js/setTimeout flush-change! change-delay-ms)))

(defn set-code! [text]
  (when (string? text)
    (cancel-change!)
    (when-let [node (editor-node)]
      (when (not= (.-value node) text)
        (set! (.-value node) text))))
  (update-line-numbers!)
  (update-validation!))

(defn code-panel []
  (let [editor (dom/create-el :textarea
                              {:id "qasm"
                               :spellcheck "false"
                               :autocapitalize "off"
                               :autocomplete "off"
                               :autocorrect "off"
                               :wrap "off"})
        gutter (dom/create-el :div
                              {:id "line-numbers"
                               :class "line-numbers"
                               :aria-hidden "true"})]
    (.addEventListener editor "input"
                       (fn [_]
                         (update-line-numbers!)
                         (schedule-change!)))
    (.addEventListener editor "scroll" (fn [_] (sync-editor-scroll!)))
    (.addEventListener editor "blur" (fn [_] (flush-change!)))
    (dom/create-el :aside
                   {:id "code-panel"}
                   (dom/create-el :div
                                  {:class "panel-section code-header"}
                                  (dom/create-el :h2 {} "Editor")
                                  (dom/create-el :span {:id "qasm-status" :class "code-hint"} "valid"))
                   (dom/create-el :div
                                  {:class "code-body"}
                                  gutter
                                  editor))))

(defn init []
  (let [root (js/document.getElementById "app")]
    (.add (.-classList root) "editor-window")
    (.appendChild root (code-panel)))
  (dom/apply-dark! (dom/dark-enabled?))
  (update-line-numbers!)
  (update-validation!)
  (io/setup-editor! {:on-code set-code!}))
