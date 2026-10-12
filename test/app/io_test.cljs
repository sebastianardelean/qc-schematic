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
    (with-api api
              (fn []
                (io/setup! {:get-qasm (fn [] "GENERATED")
                            :on-import (fn [content] (swap! calls assoc :imported content))
                            :get-svg (fn [] "<svg></svg>")})
                ((:export @calls))
                (is (= "GENERATED" (:exported @calls)))
                ((:svg @calls))
                (is (= "<svg></svg>" (:svg-out @calls)))
                ((:import @calls))
                (is (= "qubit[1] q;" (:imported @calls)))))))
