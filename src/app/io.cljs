(ns app.io)

(defn electron-api []
  (when (exists? js/window)
    (.-electronAPI js/window)))

(defn available? []
  (boolean (electron-api)))

(defn set-snapshot-period! [minutes]
  (when-let [api (electron-api)]
    (.setSnapshotPeriod api minutes)))

(defn result-field [result k]
  (cond
    (nil? result) nil
    (map? result) (get result k)
    :else (aget result (name k))))

(defn setup!
  [{:keys [get-qasm on-import get-svg on-settings snapshot-minutes]}]
  (when-let [api (electron-api)]
    (.onMenuExport api (fn [] (.exportQasm api (get-qasm))))
    (.onMenuImport api
                   (fn []
                     (-> (.importQasm api)
                         (.then (fn [res]
                                  (when (result-field res :ok)
                                    (on-import (result-field res :content))))))))
    (.onMenuExportSvg api (fn [] (.exportSvg api (get-svg))))
    (.onMenuSave api (fn [] (.saveQasm api (get-qasm))))
    (.onMenuSaveAs api (fn [] (.saveQasmAs api (get-qasm))))
    (.onRequestSnapshot api (fn [] (.saveSnapshot api (get-qasm))))
    (.onMenuSettings api (fn [] (when on-settings (on-settings))))
    (when (some? snapshot-minutes)
      (set-snapshot-period! snapshot-minutes))))
