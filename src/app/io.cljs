(ns app.io)

(defn electron-api []
  (when (exists? js/window)
    (.-electronAPI js/window)))

(defn available? []
  (boolean (electron-api)))

(defn setup!
  [{:keys [get-qasm on-import get-svg]}]
  (when-let [api (electron-api)]
    (.onMenuExport api (fn [] (.exportQasm api (get-qasm))))
    (.onMenuImport api
                   (fn []
                     (-> (.importQasm api)
                         (.then (fn [res]
                                  (when (:ok res)
                                    (on-import (:content res))))))))
    (.onMenuExportSvg api (fn [] (.exportSvg api (get-svg))))))
