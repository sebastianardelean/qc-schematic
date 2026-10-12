(ns app.qasm
  (:require [clojure.string :as string]
            [app.model :as model]))

(def gate-qasm-name->type
  (into {} (map (fn [[type config]] [(:qasm config) type]) model/gate-types)))

(defn header-line? [line]
  (or (string/starts-with? line "OPENQASM")
      (string/starts-with? line "include")))

(def qasm-qubit-re #"^\s*qubit\s*\[\s*(\d+)\s*\]\s*[A-Za-z_][A-Za-z0-9_]*\s*;?\s*$")

(def qasm-gate-re
  #"^\s*([A-Za-z][A-Za-z0-9_]*)\s*(?:\(\s*([^)]*)\s*\))?\s*[A-Za-z_][A-Za-z0-9_]*\s*\[\s*(\d+)\s*\]\s*(?:,\s*[A-Za-z_][A-Za-z0-9_]*\s*\[\s*(\d+)\s*\])?\s*;?\s*$")

(def qasm-gate-def-re
  #"^\s*gate\s+([A-Za-z_][A-Za-z0-9_]*)\s*(?:\([^)]*\))?\s*([A-Za-z_,\s]*?)\s*\{")

(def qasm-call-re
  #"^\s*([A-Za-z_][A-Za-z0-9_]*)\s*(?:\([^)]*\))?\s*((?:[A-Za-z_][A-Za-z0-9_]*\s*\[\s*\d+\s*\]\s*,?\s*)+)\s*;?\s*$")

(defn wire-indices [text]
  (mapv #(js/parseInt (second %) 10) (re-seq #"\[\s*(\d+)\s*\]" text)))

(defn count-arity [args]
  (let [trimmed (string/trim (or args ""))]
    (if (empty? trimmed)
      0
      (count (remove string/blank? (string/split trimmed #","))))))

(defn brace-delta [line]
  (- (count (re-seq #"\{" line))
     (count (re-seq #"\}" line))))

(defn gate-qasm [{:keys [type name wire target wires angle]}]
  (cond
    (= type :cx) (str "cx q[" wire "], q[" target "];")
    (= type :custom) (str name " " (string/join ", " (map #(str "q[" % "]") wires)) ";")
    :else
    (let [qname (get-in model/gate-types [type :qasm] "id")
          angle (or angle (get-in model/gate-types [type :angle]))]
      (str qname (when angle (str "(" angle ")")) " q[" wire "];"))))

(defn generate-qasm [{:keys [qubits gates defs]}]
  (let [def-lines (mapcat :lines (vals (or defs {})))
        body (map gate-qasm (sort-by (juxt :col :wire :id) gates))
        header (concat ["OPENQASM 3;"
                        "include \"stdgates.inc\";"
                        ""]
                       (when (seq def-lines) (concat def-lines [""]))
                       [(str "qubit[" qubits "] q;")])]
    (str (string/join "\n" (concat header body)) "\n")))

(defn assign-col [nextcol affected]
  (let [col (apply max 0 (map #(get nextcol % 0) affected))]
    [col (reduce (fn [m w] (assoc m w (inc col))) nextcol affected)]))

(defn parse-qasm [text]
  (loop [lines (string/split-lines text)
         qubits nil
         nextcol {}
         id 0
         gates []
         defs {}
         def-name nil
         def-arity 0
         def-lines []
         def-depth 0]
    (let [raw (first lines)]
      (cond
        (nil? raw)
        (if (and qubits (<= model/min-qubits qubits model/max-qubits))
          {:ok true :qubits qubits :gates gates :defs defs}
          {:ok false})

        def-name
        (let [depth (+ def-depth (brace-delta raw))
              def-lines' (conj def-lines raw)]
          (if (<= depth 0)
            (recur (rest lines) qubits nextcol id gates
                   (assoc defs def-name {:arity def-arity :lines def-lines'})
                   nil 0 [] 0)
            (recur (rest lines) qubits nextcol id gates defs def-name def-arity def-lines' depth)))

        :else
        (let [idx (string/index-of raw "//")
              line (string/trim (if idx (subs raw 0 idx) raw))]
          (cond
            (or (empty? line) (header-line? line))
            (recur (rest lines) qubits nextcol id gates defs nil 0 [] 0)

            (re-matches qasm-qubit-re line)
            (let [[_ n] (re-matches qasm-qubit-re line)]
              (recur (rest lines) (js/parseInt n 10) nextcol id gates defs nil 0 [] 0))

            (re-find qasm-gate-def-re line)
            (let [[_ dname dargs] (re-find qasm-gate-def-re line)
                  start-depth (brace-delta line)]
              (if (<= start-depth 0)
                (recur (rest lines) qubits nextcol id gates
                       (assoc defs dname {:arity (count-arity dargs) :lines [raw]})
                       nil 0 [] 0)
                (recur (rest lines) qubits nextcol id gates defs
                       dname (count-arity dargs) [raw] start-depth)))

            :else
            (let [[_ cname _ i1 i2] (re-matches qasm-gate-re line)
                  [_ call-name args] (re-matches qasm-call-re line)]
              (cond
                (and call-name (contains? defs call-name))
                (let [wires (wire-indices args)
                      valid (and qubits (seq wires)
                                 (every? #(<= 0 % (dec qubits)) wires))]
                  (if valid
                    (let [candidate {:type :custom :name call-name :wire (first wires)
                                     :wires wires :target nil :angle nil}
                          [col nextcol'] (assign-col nextcol (model/affected-wires candidate))]
                      (recur (rest lines) qubits nextcol' (inc id)
                             (conj gates (assoc candidate :col col :id id)) defs nil 0 [] 0))
                    {:ok false}))

                (= (get gate-qasm-name->type cname) :cx)
                (let [wire (when i1 (js/parseInt i1 10))
                      target (when i2 (js/parseInt i2 10))]
                  (if (and target (not= target wire)
                           (some? qubits)
                           (<= 0 wire (dec qubits)) (<= 0 target (dec qubits)))
                    (let [candidate {:type :cx :wire wire :target target :angle nil}
                          [col nextcol'] (assign-col nextcol (model/affected-wires candidate))]
                      (recur (rest lines) qubits nextcol' (inc id)
                             (conj gates (assoc candidate :col col :id id)) defs nil 0 [] 0))
                    {:ok false}))

                (some? (get gate-qasm-name->type cname))
                (let [type (get gate-qasm-name->type cname)
                      wire (when i1 (js/parseInt i1 10))
                      arg (nth (re-matches qasm-gate-re line) 2 nil)]
                  (cond
                    (some? i2) {:ok false}
                    (and (some? qubits) wire (<= 0 wire (dec qubits)))
                    (let [angle (when (and arg (not (string/blank? arg))) (string/trim arg))
                          candidate {:type type :wire wire :target nil :angle angle}
                          [col nextcol'] (assign-col nextcol (model/affected-wires candidate))]
                      (recur (rest lines) qubits nextcol' (inc id)
                             (conj gates (assoc candidate :col col :id id)) defs nil 0 [] 0))
                    :else {:ok false}))

                :else {:ok false}))))))))
