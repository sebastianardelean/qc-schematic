(ns app.qasm-test
  (:require [cljs.test :refer [deftest testing is]]
            [app.qasm :as qasm]))

(deftest gate-qasm-test
  (is (= "x q[0];" (qasm/gate-qasm {:type :x :wire 0})))
  (is (= "rx(pi/2) q[2];" (qasm/gate-qasm {:type :rx :wire 2 :angle "pi/2"})))
  (is (= "rz(pi/4) q[1];" (qasm/gate-qasm {:type :rz :wire 1 :angle "pi/4"})))
  (is (= "rz(pi/2) q[1];" (qasm/gate-qasm {:type :rz :wire 1})))
  (is (= "cx q[0], q[2];" (qasm/gate-qasm {:type :cx :wire 0 :target 2})))
  (is (= "mygate q[0], q[2];" (qasm/gate-qasm {:type :custom :name "mygate" :wires [0 2]})))
  (is (= "id q[3];" (qasm/gate-qasm {:type :unknown :wire 3}))))

(deftest generate-qasm-test
  (let [text (qasm/generate-qasm {:qubits 2
                                  :gates [{:type :h :wire 0 :col 0 :id 0}
                                          {:type :cx :wire 0 :target 1 :col 1 :id 1}]})]
    (is (re-find #"OPENQASM 3;" text))
    (is (re-find #"include \"stdgates.inc\";" text))
    (is (re-find #"qubit\[2\] q;" text))
    (is (re-find #"h q\[0\];" text))
    (is (re-find #"cx q\[0\], q\[1\];" text))
    (is (not (re-find #"\bbit\[" text))))
  (testing "emits defs before qubit declaration"
    (let [text (qasm/generate-qasm {:qubits 2 :gates []
                                    :defs {"foo" {:arity 1 :lines ["gate foo a { x a; }"]}}})]
      (is (re-find #"gate foo a \{ x a; \}" text)))))

(deftest generate-qasm-cbits-test
  (let [text (qasm/generate-qasm {:qubits 2 :cbits 3 :gates []})]
    (is (re-find #"\bbit\[3\] c;" text)))
  (let [text (qasm/generate-qasm {:qubits 2 :cbits 0 :gates []})]
    (is (not (re-find #"\bbit\[" text))))
  (let [text (qasm/generate-qasm {:qubits 2 :gates []})]
    (is (not (re-find #"\bbit\[" text)))))

(deftest assign-col-test
  (is (= [0 {0 1}] (qasm/assign-col {} [0])))
  (is (= [2 {0 3 1 3}] (qasm/assign-col {0 1 1 2} [0 1])))
  (is (= [0 {}] (qasm/assign-col {} []))))

(deftest parse-basic-test
  (let [res (qasm/parse-qasm "OPENQASM 3;\ninclude \"stdgates.inc\";\nqubit[3] q;\nh q[0];\ncx q[0], q[2];\n")]
    (is (true? (:ok res)))
    (is (= 3 (:qubits res)))
    (is (= 0 (:cbits res)))
    (is (= 2 (count (:gates res))))
    (let [[h cx] (:gates res)]
      (is (= :h (:type h)))
      (is (= 0 (:wire h)))
      (is (= :cx (:type cx)))
      (is (= 0 (:wire cx)))
      (is (= 2 (:target cx))))))

(deftest parse-bit-declaration-test
  (let [res (qasm/parse-qasm "qubit[2] q;\nbit[2] c;\nmeasure q[0];")]
    (is (true? (:ok res)))
    (is (= 2 (:cbits res))))
  (testing "invalid classical bit count"
    (is (false? (:ok (qasm/parse-qasm "qubit[2] q;\nbit[99] c;")))))
  (testing "round trip"
    (let [text (qasm/generate-qasm {:qubits 2 :cbits 2 :gates []})
          res (qasm/parse-qasm text)]
      (is (true? (:ok res)))
      (is (= 2 (:cbits res))))))

(deftest parse-comments-and-angles-test
  (let [res (qasm/parse-qasm "qubit[2] q;\nrx(pi/4) q[1]; // rotate\n// full line\n")]
    (is (true? (:ok res)))
    (is (= 2 (:qubits res)))
    (is (= "pi/4" (:angle (first (:gates res)))))))

(deftest parse-scheduling-test
  (let [res (qasm/parse-qasm "qubit[2] q;\nh q[0];\nh q[0];\nh q[1];")
        cols (map :col (:gates res))]
    (is (= [0 1 0] cols))))

(deftest parse-custom-gate-test
  (let [text (str "qubit[2] q;\n"
                  "gate mygate a, b { cx a, b; }\n"
                  "mygate q[0], q[1];\n")
        res (qasm/parse-qasm text)]
    (is (true? (:ok res)))
    (is (= 2 (get-in res [:defs "mygate" :arity])))
    (let [g (first (:gates res))]
      (is (= :custom (:type g)))
      (is (= "mygate" (:name g)))
      (is (= [0 1] (:wires g))))))

(deftest parse-multiline-def-test
  (let [text (str "qubit[1] q;\n"
                  "gate foo a {\n  x a;\n}\n"
                  "foo q[0];\n")
        res (qasm/parse-qasm text)]
    (is (true? (:ok res)))
    (is (= 1 (get-in res [:defs "foo" :arity])))
    (is (= :custom (:type (first (:gates res)))))))

(deftest parse-def-brace-on-next-line-test
  (testing "gate definition with the brace on its own line"
    (let [text (str "qubit[2] q;\n"
                    "gate cphase(theta) a, b\n"
                    "{\n"
                    "  U(0, 0, theta / 2) a;\n"
                    "  CX a, b;\n"
                    "}\n"
                    "cphase(pi / 2) q[0], q[1];\n")
          res (qasm/parse-qasm text)]
      (is (true? (:ok res)))
      (is (= 2 (get-in res [:defs "cphase" :arity])))
      (let [g (first (:gates res))]
        (is (= :custom (:type g)))
        (is (= "cphase" (:name g)))
        (is (= [0 1] (:wires g))))))
  (testing "TODO example with unicode parameters"
    (let [text (str "qubit[2] q;\n"
                    "gate cphase(θ) a, b\n"
                    "{\n"
                    "  U(0, 0, θ / 2) a;\n"
                    "  CX a, b;\n"
                    "  U(0, 0, -θ / 2) b;\n"
                    "  CX a, b;\n"
                    "  U(0, 0, θ / 2) b;\n"
                    "}\n"
                    "cphase(π / 2) q[0], q[1];\n")
          res (qasm/parse-qasm text)]
      (is (true? (:ok res)))
      (is (= :custom (:type (first (:gates res)))))
      (is (= [0 1] (:wires (first (:gates res))))))))

(deftest strip-comment-test
  (is (= "h q[0];" (qasm/strip-comment "h q[0]; // comment")))
  (is (= "h q[0];" (qasm/strip-comment "h q[0];")))
  (is (= "" (qasm/strip-comment "// full line comment"))))

(deftest gate-qasm-measure-test
  (is (= "measure q[3];" (qasm/gate-qasm {:type :measure :wire 3}))))

(deftest generate-qasm-measure-test
  (let [text (qasm/generate-qasm {:qubits 2
                                  :gates [{:type :measure :wire 0 :col 0 :id 0}]})]
    (is (re-find #"measure q\[0\];" text))))

(deftest parse-measure-test
  (testing "bare measure"
    (let [res (qasm/parse-qasm "qubit[2] q;\nmeasure q[1];")]
      (is (true? (:ok res)))
      (is (= :measure (:type (first (:gates res)))))
      (is (= 1 (:wire (first (:gates res)))))))
  (testing "measure with classical destination"
    (let [res (qasm/parse-qasm "qubit[2] q;\nmeasure q[0] -> c[0];")]
      (is (true? (:ok res)))
      (is (= :measure (:type (first (:gates res)))))))
  (testing "measure out of range"
    (is (false? (:ok (qasm/parse-qasm "qubit[1] q;\nmeasure q[5];"))))))

(deftest parse-invalid-test
  (testing "missing qubit declaration"
    (is (false? (:ok (qasm/parse-qasm "h q[0];")))))
  (testing "wire out of range"
    (is (false? (:ok (qasm/parse-qasm "qubit[1] q; h q[5];")))))
  (testing "unknown gate"
    (is (false? (:ok (qasm/parse-qasm "qubit[1] q; bogus q[0];")))))
  (testing "cx with same wire"
    (is (false? (:ok (qasm/parse-qasm "qubit[2] q; cx q[0], q[0];")))))
  (testing "two-qubit args on single-qubit gate"
    (is (false? (:ok (qasm/parse-qasm "qubit[2] q; h q[0], q[1];")))))
  (testing "too many qubits"
    (is (false? (:ok (qasm/parse-qasm "qubit[99] q;")))))
  (testing "custom gate call out of range"
    (is (false? (:ok (qasm/parse-qasm "qubit[1] q;\ngate foo a { x a; }\nfoo q[5];")))))
  (testing "custom gate call with no wires"
    (is (false? (:ok (qasm/parse-qasm "qubit[1] q;\ngate foo a { x a; }\nfoo;"))))))

(deftest round-trip-test
  (let [st {:qubits 3 :gates [{:type :h :wire 0 :col 0 :id 0}
                              {:type :rx :wire 1 :col 0 :id 1 :angle "pi/2"}
                              {:type :cx :wire 0 :target 2 :col 1 :id 2}]
            :defs {}}
        round (qasm/parse-qasm (qasm/generate-qasm st))]
    (is (true? (:ok round)))
    (is (= 3 (:qubits round)))
    (is (= (count (:gates st)) (count (:gates round))))
    (is (= [:h :rx :cx] (map :type (:gates round))))))
