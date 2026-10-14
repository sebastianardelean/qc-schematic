(ns app.io-test
  (:require [cljs.test :refer [deftest testing is]]
            [app.io :as io]))

(defn with-api [api f]
  (when-not (exists? js/window)
    (aset js/globalThis "window" (js-obj)))
  (aset (.-window js/globalThis) "electronAPI" api)
  (try
    (f)
    (finally
      (aset (.-window js/globalThis) "electronAPI" nil))))

(deftest available-test
  (with-api nil (fn [] (is (false? (io/available?)))))
  (with-api (js-obj) (fn [] (is (true? (io/available?))))))

(deftest setup-no-api-test
  (testing "setup! is a no-op without electronAPI"
    (with-api nil (fn [] (is (nil? (io/setup! {:get-qasm (fn [] "x")})))))))

(deftest set-snapshot-period-test
  (testing "no-op without electronAPI"
    (with-api nil (fn [] (is (nil? (io/set-snapshot-period! 5))))))
  (testing "forwards the period to the main process"
    (let [calls (atom {})
          api (js-obj)]
      (aset api "setSnapshotPeriod" (fn [minutes] (swap! calls assoc :minutes minutes)))
      (with-api api (fn [] (io/set-snapshot-period! 12)))
      (is (= 12 (:minutes @calls))))))

(deftest setup-test
  (let [calls (atom {})
        api (js-obj)]
    (aset api "onMenuExport" (fn [cb] (swap! calls assoc :export cb)))
    (aset api "onMenuImport" (fn [cb] (swap! calls assoc :import cb)))
    (aset api "onMenuExportSvg" (fn [cb] (swap! calls assoc :svg cb)))
    (aset api "exportQasm" (fn [code] (swap! calls assoc :exported code)))
    (aset api "importQasm" (fn []
                             (js-obj "then"
                                     (fn [cb]
                                       (cb {:ok true :content "qubit[1] q;"})))))
    (aset api "exportSvg" (fn [svg] (swap! calls assoc :svg-out svg)))
    (aset api "onMenuSave" (fn [cb] (swap! calls assoc :save cb)))
    (aset api "saveQasm" (fn [code] (swap! calls assoc :saved code)))
    (aset api "onMenuSaveAs" (fn [cb] (swap! calls assoc :save-as cb)))
    (aset api "saveQasmAs" (fn [code] (swap! calls assoc :saved-as code)))
    (aset api "onRequestSnapshot" (fn [cb] (swap! calls assoc :snapshot cb)))
    (aset api "saveSnapshot" (fn [code] (swap! calls assoc :snapshot-out code)))
    (aset api "onMenuSettings" (fn [cb] (swap! calls assoc :settings cb)))
    (aset api "setSnapshotPeriod" (fn [minutes] (swap! calls assoc :snapshot-period minutes)))
    (with-api api
              (fn []
                (io/setup! {:get-qasm (fn [] "GENERATED")
                            :on-import (fn [content] (swap! calls assoc :imported content))
                            :get-svg (fn [] "<svg></svg>")
                            :on-settings (fn [] (swap! calls assoc :settings-open true))
                            :snapshot-minutes 7})
                ((:export @calls))
                (is (= "GENERATED" (:exported @calls)))
                ((:svg @calls))
                (is (= "<svg></svg>" (:svg-out @calls)))
                ((:import @calls))
                (is (= "qubit[1] q;" (:imported @calls)))
                ((:save @calls))
                (is (= "GENERATED" (:saved @calls)))
                ((:save-as @calls))
                (is (= "GENERATED" (:saved-as @calls)))
                ((:snapshot @calls))
                (is (= "GENERATED" (:snapshot-out @calls)))
                ((:settings @calls))
                (is (true? (:settings-open @calls)))
                (is (= 7 (:snapshot-period @calls)))))))
