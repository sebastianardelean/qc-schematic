(ns app.model-test
  (:require [cljs.test :refer [deftest testing is]]
            [app.model :as model]))

(deftest gate-src-test
  (testing "known type returns its asset"
    (is (= "static/hadamard.svg" (model/gate-src :h)))
    (is (= "static/x.svg" (model/gate-src :x)))
    (is (= "static/measure.svg" (model/gate-src :measure)))
    (is (= "static/barrier.svg" (model/gate-src :barrier))))
  (testing "unknown type falls back"
    (is (= "static/hadamard.svg" (model/gate-src :nope)))))

(deftest palette-types-test
  (is (some #{:measure} model/palette-types))
  (is (some #{:barrier} model/palette-types)))

(deftest geometry-test
  (is (= (+ model/top-pad (* 2 model/wire-gap)) (model/wire-y 2)))
  (is (= (+ model/origin-x (* 3 model/col-gap)) (model/col-x 3)))
  (is (= (- (model/col-x 3) (/ model/gate-size 2)) (model/gate-left 3)))
  (is (= (- (model/wire-y 1) (/ model/gate-size 2)) (model/gate-top 1))))

(deftest cwire-y-test
  (is (= (model/wire-y 2) (model/cwire-y 0 2)))
  (is (= (model/wire-y 3) (model/cwire-y 1 2))))

(deftest snap-wire-test
  (is (= 0 (model/snap-wire 0 4)))
  (is (= 0 (model/snap-wire -500 4)))
  (is (= 1 (model/snap-wire (+ model/top-pad model/wire-gap) 4)))
  (is (= 3 (model/snap-wire 100000 4))))

(deftest snap-col-test
  (is (= 0 (model/snap-col 0)))
  (is (= 0 (model/snap-col -50)))
  (is (= 2 (model/snap-col (+ model/origin-x (* 2 model/col-gap))))))

(deftest gate-wires-test
  (is (= [1] (model/gate-wires {:type :x :wire 1})))
  (is (= '(1 2 3) (model/gate-wires {:type :cx :wire 1 :target 3})))
  (is (= [2 4] (model/gate-wires {:type :custom :wires [2 4]})))
  (is (= [0 1 2] (model/gate-wires {:type :barrier :wire 0 :wires [0 1 2]})))
  (is (= [2] (model/gate-wires {:type :measure :wire 2}))))

(deftest target-cell-measure-test
  (let [st {:qubits 4 :gates []}
        cell (model/target-cell st :measure model/origin-x model/top-pad)]
    (is (= :measure (:type cell)))
    (is (= 0 (:wire cell)))
    (is (nil? (:target cell)))))

(deftest blocks-cell-test
  (let [g {:type :x :wire 1 :col 2}]
    (is (true? (model/blocks-cell? g 1 2)))
    (is (false? (model/blocks-cell? g 2 2)))
    (is (false? (model/blocks-cell? g 1 3))))
  (let [cx {:type :cx :wire 0 :target 3 :col 1}]
    (is (true? (model/blocks-cell? cx 0 1)))
    (is (true? (model/blocks-cell? cx 1 1)))
    (is (true? (model/blocks-cell? cx 3 1)))
    (is (false? (model/blocks-cell? cx 4 1))))
  (let [custom {:type :custom :wires [1 3] :col 0}]
    (is (true? (model/blocks-cell? custom 3 0)))
    (is (not (model/blocks-cell? custom 2 0))))
  (let [barrier {:type :barrier :wire 0 :wires [0 1 2] :col 2}]
    (is (true? (model/blocks-cell? barrier 1 2)))
    (is (true? (model/blocks-cell? barrier 2 2)))
    (is (not (model/blocks-cell? barrier 3 2)))
    (is (not (model/blocks-cell? barrier 1 3)))))

(deftest blocked-test
  (is (true? (model/blocked? [{:type :x :wire 0 :col 0}] 0 0)))
  (is (false? (model/blocked? [{:type :x :wire 0 :col 0}] 0 1))))

(deftest affected-wires-test
  (is (= [1] (model/affected-wires {:type :h :wire 1})))
  (is (= [1 2 3] (model/affected-wires {:type :cx :wire 3 :target 1}))))

(deftest free-col-test
  (let [gates [{:type :x :wire 0 :col 0}]]
    (is (true? (model/free-col? gates 1 [0])))
    (is (false? (model/free-col? gates 0 [0])))))

(deftest free-wire-span-test
  (let [gates [{:type :x :wire 1 :col 0}]]
    (is (false? (model/free-wire-span? gates 0 0 2)))
    (is (true? (model/free-wire-span? gates 1 0 2)))))

(deftest nearest-free-col-test
  (is (= 5 (model/nearest-free-col [] 5 [0])))
  (let [gates [{:type :x :wire 0 :col 3}]]
    (is (= 4 (model/nearest-free-col gates 3 [0])))
    (is (= 3 (model/nearest-free-col gates 3 [1])))))

(deftest cx-target-test
  (is (= 1 (model/cx-target 0 2)))
  (is (= 0 (model/cx-target 1 2)))
  (is (= 1 (model/cx-target 2 3))))

(deftest target-cell-test
  (let [st {:qubits 4 :gates []}]
    (is (= {:type :h :wire 0 :target nil :angle nil :col 0}
           (model/target-cell st :h model/origin-x model/top-pad))))
  (testing "cx gets an adjacent target"
    (let [cell (model/target-cell {:qubits 4 :gates []} :cx model/origin-x model/top-pad)]
      (is (= 1 (:target cell)))))
  (testing "placed gate avoids occupied column"
    (let [st {:qubits 4 :gates [{:type :x :wire 0 :col 0 :id 0}]}
          cell (model/target-cell st :h model/origin-x model/top-pad)]
      (is (= 1 (:col cell)))))
  (testing "barrier spans every qubit"
    (let [cell (model/target-cell {:qubits 3 :gates []} :barrier model/origin-x model/top-pad)]
      (is (= :barrier (:type cell)))
      (is (= [0 1 2] (:wires cell))))))

(deftest cx-geometry-test
  (is (= {:above 1 :below 3 :span (- (model/wire-y 3) (model/wire-y 1)) :control-at-top? true}
         (model/cx-geometry 1 3)))
  (is (= true (:control-at-top? (model/cx-geometry 0 2))))
  (is (= false (:control-at-top? (model/cx-geometry 2 0)))))

(deftest nearest-free-wire-test
  (testing "desired wire kept when free"
    (is (= 2 (model/nearest-free-wire [] 0 2 0 4))))
  (testing "occupied span moves target"
    (let [gates [{:type :x :wire 2 :col 0}]]
      (is (= 1 (model/nearest-free-wire gates 0 2 0 4))))))

(deftest canvas-size-test
  (let [{:keys [width height]} (model/canvas-size {:qubits 4 :gates []})]
    (is (= model/min-canvas-width width))
    (is (= (+ model/top-pad (* 4 model/wire-gap) model/bottom-pad) height)))
  (testing "grows with more columns"
    (let [base (:width (model/canvas-size {:qubits 1 :gates []}))
          wide (:width (model/canvas-size {:qubits 1
                                          :gates (map #(hash-map :col % :wire 0) (range 12))}))]
      (is (> wide base))))
  (testing "grows with more qubits"
    (is (< (:height (model/canvas-size {:qubits 1 :gates []}))
           (:height (model/canvas-size {:qubits 8 :gates []})))))
  (testing "adds a single classical wire row, independent of the declared count"
    (let [plain (:height (model/canvas-size {:qubits 2 :cbits 0 :gates []}))
          both (:height (model/canvas-size {:qubits 2 :cbits 3 :gates []}))
          more (:height (model/canvas-size {:qubits 2 :cbits 9 :gates []}))]
      (is (= model/wire-gap (- both plain)))
      (is (= both more)))))

(deftest state-helpers-test
  (let [st (model/add-gate-to-state model/initial-state {:type :x :wire 0 :col 0})]
    (is (= 1 (:next-id st)))
    (is (= 0 (:id (first (:gates st)))))
    (is (empty? (:gates (model/remove-gate-from-state st 0))))
    (is (= 1 (:next-id st)))))

(deftest set-qubits-in-state-test
  (let [st {:qubits 4 :gates [{:type :x :wire 3 :col 0}
                              {:type :cx :wire 3 :target 3 :col 1}]}]
    (is (= 2 (:qubits (model/set-qubits-in-state st 2))))
    (is (empty? (:gates (model/set-qubits-in-state st 2)))))
  (testing "clamping"
    (is (= model/min-qubits (:qubits (model/set-qubits-in-state model/initial-state 0))))
    (is (= model/max-qubits (:qubits (model/set-qubits-in-state model/initial-state 99))))))

(deftest ensure-measure-cbits-test
  (testing "measurement without classical bits adds one per qubit"
    (let [st {:qubits 3 :cbits 0 :gates [{:type :measure :wire 0}]}]
      (is (= 3 (:cbits (model/ensure-measure-cbits st))))))
  (testing "no measurement leaves classical bits untouched"
    (let [st {:qubits 3 :cbits 0 :gates [{:type :h :wire 0}]}]
      (is (= 0 (:cbits (model/ensure-measure-cbits st))))))
  (testing "existing classical bits are never grown"
    (let [st {:qubits 3 :cbits 2 :gates [{:type :measure :wire 0}]}]
      (is (= 2 (:cbits (model/ensure-measure-cbits st))))))
  (testing "quantum bit count is clamped to the maximum"
    (let [st {:qubits 99 :cbits 0 :gates [{:type :measure :wire 0}]}]
      (is (= model/max-cbits (:cbits (model/ensure-measure-cbits st)))))))
