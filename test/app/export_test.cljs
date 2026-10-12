(ns app.export-test
  (:require [cljs.test :refer [deftest testing is]]
            [app.export :as export]))

(deftest circuit-svg-test
  (let [svg (export/circuit->svg {:qubits 2 :gates [{:type :h :wire 0 :col 0 :id 0}]})]
    (is (re-find #"<svg" svg))
    (is (re-find #"</svg>" svg))
    (is (re-find #"<line" svg))
    (is (re-find #">H<" svg))
    (is (re-find #"fill=\"#ffffff\"" svg))))

(deftest circuit-svg-empty-test
  (let [svg (export/circuit->svg {:qubits 2 :gates []})]
    (is (re-find #"<svg" svg))))

(deftest circuit-svg-labels-test
  (let [svg (export/circuit->svg {:qubits 4
                                  :gates [{:type :rx :wire 0 :col 0 :id 0 :angle "pi/2"}
                                          {:type :id :wire 1 :col 0 :id 1}
                                          {:type :p :wire 2 :col 0 :id 2}
                                          {:type :custom :name "foo" :wires [0 2] :col 1 :id 3}]})]
    (is (re-find #">Rx<" svg))
    (is (re-find #">I<" svg))
    (is (re-find #">P<" svg))
    (is (re-find #">foo<" svg))))

(deftest circuit-svg-cx-test
  (let [svg (export/circuit->svg {:qubits 3 :gates [{:type :cx :wire 0 :target 2 :col 0 :id 0}]})]
    (is (re-find #"<circle" svg))))

(deftest circuit-svg-escapes-test
  (let [svg (export/circuit->svg {:qubits 1
                                  :gates [{:type :custom :name "a<b&c" :wires [0] :col 0 :id 0}]})]
    (is (re-find #"a&lt;b&amp;c" svg))))
