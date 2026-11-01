(ns app.export-test
  (:require [cljs.test :refer [deftest testing is]]
            [app.export :as export]
            [app.model :as model]))

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
    (is (re-find #"<circle" svg))
    (testing "vertical line spans exactly the two wires, not beyond"
      (is (re-find (re-pattern (str "y1=\"" (model/wire-y 0) "\"")
                               ) svg))
      (is (re-find (re-pattern (str "y2=\"" (model/wire-y 2) "\"")) svg)))
    (testing "the control and target use the same symbols as the canvas"
      (is (= 2 (count (re-seq #"<circle" svg))))
      (is (re-find (re-pattern (str "fill=\"" export/gate-color "\"")) svg))
      (is (re-find #"r=\"11\" fill=\"none\"" svg))
      (is (not (re-find #"r=\"6\"" svg))))
    (testing "the symbol is monochrome"
      (is (not (re-find #"#678efa" svg))))))

(deftest circuit-svg-ccx-test
  (let [svg (export/circuit->svg {:qubits 3
                                  :gates [{:type :ccx :wire 0 :control 1 :target 2
                                           :col 0 :id 0}]})]
    (testing "two controls and one target are drawn"
      (is (= 3 (count (re-seq #"<circle" svg))))
      (is (= 2 (count (re-seq #"r=\"10\"" svg))))
      (is (re-find #"r=\"11\" fill=\"none\"" svg)))
    (testing "the vertical line spans exactly the three wires"
      (is (re-find (re-pattern (str "y1=\"" (model/wire-y 0) "\"")) svg))
      (is (re-find (re-pattern (str "y2=\"" (model/wire-y 2) "\"")) svg)))
    (testing "the symbol is monochrome"
      (is (not (re-find #"#678efa" svg))))))

(deftest circuit-svg-measure-test
  (let [svg (export/circuit->svg {:qubits 1 :gates [{:type :measure :wire 0 :col 0 :id 0}]})]
    (testing "the measurement symbol matches the canvas"
      (is (re-find #"<rect" svg))
      (is (re-find #"rx=\"6\"" svg))
      (is (re-find #"<path d=\"M " svg))
      (is (re-find #"r=\"2\"" svg))
      (is (not (re-find #">M<" svg))))
    (testing "the symbol is monochrome"
      (is (not (re-find #"#678efa" svg))))))

(deftest circuit-svg-barrier-test
  (let [svg (export/circuit->svg {:qubits 3
                                  :gates [{:type :barrier :wire 0 :wires [0 2] :col 0 :id 0}]})]
    (is (re-find #"stroke-dasharray=\"6 4\"" svg))
    (is (re-find (re-pattern (str "y1=\"" (- (model/wire-y 0) (/ model/gate-size 2)) "\"")) svg))
    (is (re-find (re-pattern (str "y2=\"" (+ (model/wire-y 2) (/ model/gate-size 2)) "\"")) svg))))

(deftest circuit-svg-escapes-test
  (let [svg (export/circuit->svg {:qubits 1
                                  :gates [{:type :custom :name "a<b&c" :wires [0] :col 0 :id 0}]})]
    (is (re-find #"a&lt;b&amp;c" svg))))
