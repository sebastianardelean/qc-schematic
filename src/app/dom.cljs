(ns app.dom)

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

(def dark-pref-key "qc-schematic-dark")

(defn dark-enabled? []
  (try
    (= "true" (.getItem (.-localStorage js/window) dark-pref-key))
    (catch js/Error _ false)))

(defn apply-dark! [enabled]
  (.toggle (.-classList (.-body js/document)) "dark" enabled))
