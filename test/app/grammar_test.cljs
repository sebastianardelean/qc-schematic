(ns app.grammar-test
  (:require [cljs.test :refer [deftest testing is]]
            [app.grammar :as grammar]
            [app.qasm :as qasm]))

(defn- ok? [text] (true? (:ok (grammar/validate text))))

(defn- messages [text]
  (map :message (:errors (grammar/validate text))))

(deftest tokenize-basic-test
  (let [{:keys [tokens errors]} (grammar/tokenize "qubit[2] q; // c\nbit[2] c;")]
    (is (empty? errors))
    (is (= [:keyword :symbol :number :symbol :ident :symbol :keyword :symbol :number :symbol :ident :symbol :eof]
           (map :type tokens)))
    (is (= [1 1 1 1 1 1 2 2 2 2 2 2 2] (map :line tokens)))))

(deftest tokenize-comments-and-strings-test
  (testing "block comments spanning lines are skipped"
    (let [{:keys [tokens errors]} (grammar/tokenize "/* a\nb */ x")]
      (is (empty? errors))
      (is (= [:ident :eof] (map :type tokens)))
      (is (= [2 2] (map :line tokens)))))
  (testing "a line comment does not consume the newline"
    (let [{:keys [tokens]} (grammar/tokenize "x // note\ny")]
      (is (= [1 2 2] (map :line tokens)))))
  (testing "string literals"
    (let [{:keys [tokens]} (grammar/tokenize "include \"stdgates.inc\";")]
      (is (= :string (:type (second tokens))))
      (is (= "stdgates.inc" (:value (second tokens)))))))

(deftest tokenize-errors-test
  (testing "unterminated block comment"
    (is (= ["unterminated block comment"]
           (map :message (:errors (grammar/tokenize "/* nope"))))))
  (testing "unterminated string"
    (is (= ["unterminated string literal"]
           (map :message (:errors (grammar/tokenize "include \"oops;"))))))
  (testing "stray character"
    (is (= ["unexpected character '?'"] (map :message (:errors (grammar/tokenize "?")))))
    (is (= 1 (count (:errors (grammar/validate "?")))))))

(deftest empty-program-test
  (is (ok? ""))
  (is (ok? "   \n\t  ")))

(deftest version-and-include-test
  (is (ok? "OPENQASM 3;"))
  (is (ok? "OPENQASM 3.1;"))
  (is (ok? "OPENQASM 3;\ninclude \"stdgates.inc\";"))
  (testing "version number is required"
    (is (false? (ok? "OPENQASM;"))))
  (testing "semicolons are required"
    (is (false? (ok? "OPENQASM 3")))
    (is (false? (ok? "include \"stdgates.inc\"")))))

(deftest register-declaration-test
  (is (ok? "qubit[3] q;"))
  (is (ok? "bit[3] c;"))
  (is (ok? "qubit q;"))
  (testing "malformed declarations"
    (is (false? (ok? "qubit[3];")))
    (is (false? (ok? "qubit[3] q")))
    (is (false? (ok? "qubit[3 q;")))))

(deftest gate-call-test
  (is (ok? "qubit[2] q;\nh q[0];"))
  (is (ok? "qubit[2] q;\ncx q[0], q[1];"))
  (is (ok? "qubit[2] q;\nrx(pi/2) q[0];"))
  (is (ok? "qubit[2] q;\nrz(3*pi/4) q[1];"))
  (is (ok? "qubit[2] q;\nu(0, 0, theta / 2) q[0];"))
  (testing "control modifiers"
    (is (ok? "qubit[2] q;\nctrl @ x q[0], q[1];"))
    (is (ok? "qubit[2] q;\npow(2) @ x q[0];"))
    (is (ok? "qubit[2] q;\ninv @ cx q[0], q[1];")))
  (testing "hardware qubits"
    (is (ok? "qubit[1] q;\nh $0;")))
  (testing "a bare call with no operands is invalid"
    (is (false? (ok? "qubit[1] q;\nh;"))))
  (testing "modifiers require the at sign"
    (is (false? (ok? "qubit[1] q;\ninv x q[0];")))))

(deftest measure-barrier-reset-test
  (is (ok? "qubit[1] q;\nmeasure q[0];"))
  (is (ok? "qubit[1] q;\nbit[1] c;\nmeasure q[0] -> c[0];"))
  (is (ok? "qubit[2] q;\nbarrier;"))
  (is (ok? "qubit[2] q;\nbarrier q[0], q[1];"))
  (is (ok? "qubit[1] q;\nreset q[0];"))
  (testing "malformed statements"
    (is (false? (ok? "qubit[1] q;\nmeasure;")))
    (is (false? (ok? "qubit[1] q;\nmeasure q[0] -> ;")))
    (is (false? (ok? "qubit[1] q;\nreset;")))))

(deftest gate-definition-test
  (testing "single-line definition"
    (is (ok? "qubit[2] q;\ngate mygate a, b { cx a, b; }\nmygate q[0], q[1];")))
  (testing "definition with parameters"
    (is (ok? (str "qubit[2] q;\n"
                  "gate cphase(theta) a, b\n"
                  "{\n"
                  "  U(0, 0, theta / 2) a;\n"
                  "  CX a, b;\n"
                  "  U(0, 0, -theta / 2) b;\n"
                  "}\n"
                  "cphase(pi / 2) q[0], q[1];"))))
  (testing "unicode parameters"
    (is (ok? (str "qubit[2] q;\n"
                  "gate cphase(θ) a, b\n"
                  "{\n"
                  "  U(0, 0, θ / 2) a;\n"
                  "  CX a, b;\n"
                  "}\n"
                  "cphase(π / 2) q[0], q[1];"))))
  (testing "definition must be well formed"
    (is (false? (ok? "qubit[1] q;\ngate foo a { x a;")))
    (is (false? (ok? "qubit[1] q;\ngate foo { x a; }")))))

(deftest expression-precedence-test
  (testing "precedence and associativity are accepted"
    (is (ok? "qubit[1] q;\nrx(1 + 2 * 3) q[0];"))
    (is (ok? "qubit[1] q;\nrx(2 ** 3 ** 4) q[0];"))
    (is (ok? "qubit[1] q;\nrx(-pi / 2) q[0];"))
    (is (ok? "qubit[1] q;\nrx((pi + pi) / 4) q[0];"))
    (is (ok? "qubit[1] q;\nrx(0x1f) q[0];"))
    (is (ok? "qubit[1] q;\nrx(1e-3) q[0];"))))

(deftest assignment-and-call-statements-test
  (is (ok? "OPENQASM 3;\nint[32] i = 4;\ni += 1;"))
  (is (ok? "qubit[1] q;\nx(pi);"))
  (is (ok? "qubit[1] q;\nc = measure q[0];")))

(deftest unsupported-statement-test
  (testing "statements outside the modelled subset are reported"
    (let [res (grammar/validate "if (x) { y; }")]
      (is (false? (:ok res)))
      (is (re-find #"not supported" (:message (first (:errors res))))))))

(deftest error-location-test
  (let [res (grammar/validate "qubit[2] q;\nh q[0];\nqubit[2 q;")]
    (is (false? (:ok res)))
    (is (= 3 (:line (first (:errors res)))))))

(deftest multiple-errors-test
  (let [res (grammar/validate "qubit[1] q;\nif (x) { y; }\nfor (i in [1:2]) { z; }")]
    (is (false? (:ok res)))
    (is (>= (count (:errors res)) 2))))

(deftest valid-predicate-test
  (is (true? (grammar/valid? "qubit[1] q;\nh q[0];")))
  (is (false? (grammar/valid? "qubit[1] q;\nh q[0]")))
  (is (nil? (grammar/first-error "qubit[1] q;\nh q[0];")))
  (is (string? (:message (grammar/first-error "qubit[1] q; h q[0]")))))

(deftest generated-code-is-grammatical-test
  (testing "every program the editor generates validates"
    (let [state {:qubits 3 :cbits 3
                 :defs {"mygate" {:arity 2 :lines ["gate mygate a, b { cx a, b; }"]}}
                 :gates [{:type :h :wire 0 :col 0 :id 0}
                         {:type :rx :wire 1 :col 0 :id 1 :angle "pi/2"}
                         {:type :cx :wire 0 :target 2 :col 1 :id 2}
                         {:type :custom :name "mygate" :wire 0 :wires [0 1] :col 2 :id 3}
                         {:type :barrier :wire 0 :wires [0 1 2] :col 3 :id 4}
                         {:type :measure :wire 0 :col 4 :id 5}]}]
      (is (true? (:ok (grammar/validate (qasm/generate-qasm state)))))))
  (testing "a default empty circuit validates"
    (is (true? (:ok (grammar/validate (qasm/generate-qasm {:qubits 4 :gates []})))))))
