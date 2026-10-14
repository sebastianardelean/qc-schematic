(ns app.qasm
  (:require [clojure.string :as string]
            [app.model :as model]))

(def gate-qasm-name->type
  (into {} (map (fn [[type config]] [(:qasm config) type]) model/gate-types)))

(defn header-line? [line]
  (or (string/starts-with? line "OPENQASM")
      (string/starts-with? line "include")))

(def qasm-qubit-re #"^\s*qubit\s*\[\s*(\d+)\s*\]\s*[A-Za-z_][A-Za-z0-9_]*\s*;?\s*$")

(def qasm-bit-re #"^\s*bit\s*\[\s*(\d+)\s*\]\s*[A-Za-z_][A-Za-z0-9_]*\s*;?\s*$")

(def qasm-gate-re
  #"^\s*([A-Za-z][A-Za-z0-9_]*)\s*(?:\(\s*([^)]*)\s*\))?\s*[A-Za-z_][A-Za-z0-9_]*\s*\[\s*(\d+)\s*\]\s*(?:,\s*[A-Za-z_][A-Za-z0-9_]*\s*\[\s*(\d+)\s*\])?\s*;?\s*$")

(def qasm-gate-def-re
  #"^\s*gate\s+([A-Za-z_][A-Za-z0-9_]*)\s*(?:\([^)]*\))?\s*([A-Za-z_,\s]*?)\s*(\{.*)?$")

(def qasm-call-re
  #"^\s*([A-Za-z_][A-Za-z0-9_]*)\s*(?:\([^)]*\))?\s*((?:[A-Za-z_][A-Za-z0-9_]*\s*\[\s*\d+\s*\]\s*,?\s*)+)\s*;?\s*$")

(def qasm-measure-re
  #"^\s*measure\s+[A-Za-z_][A-Za-z0-9_]*\s*\[\s*(\d+)\s*\]\s*(?:->\s*[A-Za-z_][A-Za-z0-9_]*\s*\[\s*\d+\s*\]\s*)?;?\s*$")

(def qasm-barrier-re
  #"^\s*barrier\s*([^;]*);?\s*$")

(defn strip-comment [raw]
  (let [idx (string/index-of raw "//")]
    (string/trim (if idx (subs raw 0 idx) raw))))

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
    (= type :measure) (str "measure q[" wire "];")
    (= type :custom) (str name " " (string/join ", " (map #(str "q[" % "]") wires)) ";")
    (= type :barrier) (str "barrier"
                           (when (seq wires)
                             (str " " (string/join ", " (map #(str "q[" % "]") wires))))
                           ";")
    :else
    (let [qname (get-in model/gate-types [type :qasm] "id")
          angle (or angle (get-in model/gate-types [type :angle]))]
      (str qname (when angle (str "(" angle ")")) " q[" wire "];"))))

(defn generate-qasm [{:keys [qubits cbits gates defs]}]
  (let [cbits (or cbits 0)
        def-lines (mapcat :lines (vals (or defs {})))
        body (map gate-qasm (sort-by (juxt :col :wire :id) gates))
        header (concat ["OPENQASM 3;"
                        "include \"stdgates.inc\";"
                        ""]
                       (when (seq def-lines) (concat def-lines [""]))
                       [(str "qubit[" qubits "] q;")]
                       (when (pos? cbits) [(str "bit[" cbits "] c;")]))]
    (str (string/join "\n" (concat header body)) "\n")))

(defn assign-col [nextcol affected]
  (let [col (apply max 0 (map #(get nextcol % 0) affected))]
    [col (reduce (fn [m w] (assoc m w (inc col))) nextcol affected)]))

(defn parse-qasm [text]
  (loop [lines (string/split-lines text)
         qubits nil
         cbits 0
         nextcol {}
         id 0
         gates []
         defs {}
         def-name nil
         def-arity 0
         def-lines []
         def-depth nil]
    (let [raw (first lines)]
      (cond
        (nil? raw)
        (let [result (model/ensure-measure-cbits {:qubits qubits :cbits cbits
                                                  :gates gates :defs defs})]
          (if (and qubits (<= model/min-qubits qubits model/max-qubits)
                   (<= model/min-cbits (:cbits result) model/max-cbits))
            (assoc result :ok true)
            {:ok false}))

        def-name
        (let [raw-line (strip-comment raw)
              has-brace? (string/includes? raw-line "{")
              depth (if (nil? def-depth)
                      (when has-brace? (brace-delta raw-line))
                      (+ def-depth (brace-delta raw-line)))
              def-lines' (conj def-lines raw)]
          (if (and (some? depth) (<= depth 0))
            (recur (rest lines) qubits cbits nextcol id gates
                   (assoc defs def-name {:arity def-arity :lines def-lines'})
                   nil 0 [] nil)
            (recur (rest lines) qubits cbits nextcol id gates defs def-name def-arity def-lines' depth)))

        :else
        (let [line (strip-comment raw)]
          (cond
            (or (empty? line) (header-line? line))
            (recur (rest lines) qubits cbits nextcol id gates defs nil 0 [] nil)

            (re-matches qasm-qubit-re line)
            (let [[_ n] (re-matches qasm-qubit-re line)]
              (recur (rest lines) (js/parseInt n 10) cbits nextcol id gates defs nil 0 [] nil))

            (re-matches qasm-bit-re line)
            (let [[_ n] (re-matches qasm-bit-re line)
                  n (js/parseInt n 10)]
              (if (<= model/min-cbits n model/max-cbits)
                (recur (rest lines) qubits n nextcol id gates defs nil 0 [] nil)
                {:ok false}))

            (re-find qasm-gate-def-re line)
            (let [[_ dname dargs] (re-find qasm-gate-def-re line)
                  has-brace? (string/includes? line "{")
                  start-depth (when has-brace? (brace-delta line))]
              (if (and (some? start-depth) (<= start-depth 0))
                (recur (rest lines) qubits cbits nextcol id gates
                       (assoc defs dname {:arity (count-arity dargs) :lines [raw]})
                       nil 0 [] nil)
                (recur (rest lines) qubits cbits nextcol id gates defs
                       dname (count-arity dargs) [raw] start-depth)))

            (re-matches qasm-measure-re line)
            (let [[_ n] (re-matches qasm-measure-re line)
                  wire (js/parseInt n 10)]
              (if (and (some? qubits) (<= 0 wire (dec qubits)))
                (let [candidate {:type :measure :wire wire :target nil :angle nil}
                      [col nextcol'] (assign-col nextcol (model/affected-wires candidate))]
                  (recur (rest lines) qubits cbits nextcol' (inc id)
                         (conj gates (assoc candidate :col col :id id)) defs nil 0 [] nil))
                {:ok false}))

            (re-matches qasm-barrier-re line)
            (let [[_ args] (re-matches qasm-barrier-re line)
                  ws (vec (sort (distinct (wire-indices args))))
                  ws (if (seq ws) ws (when qubits (vec (range qubits))))]
              (if (and qubits (seq ws) (every? #(<= 0 % (dec qubits)) ws))
                (let [candidate {:type :barrier :wire (first ws) :wires ws :target nil :angle nil}
                      [col nextcol'] (assign-col nextcol (model/affected-wires candidate))]
                  (recur (rest lines) qubits cbits nextcol' (inc id)
                         (conj gates (assoc candidate :col col :id id)) defs nil 0 [] nil))
                {:ok false}))

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
                      (recur (rest lines) qubits cbits nextcol' (inc id)
                             (conj gates (assoc candidate :col col :id id)) defs nil 0 [] nil))
                    {:ok false}))

                (= (get gate-qasm-name->type cname) :cx)
                (let [wire (when i1 (js/parseInt i1 10))
                      target (when i2 (js/parseInt i2 10))]
                  (if (and target (not= target wire)
                           (some? qubits)
                           (<= 0 wire (dec qubits)) (<= 0 target (dec qubits)))
                    (let [candidate {:type :cx :wire wire :target target :angle nil}
                          [col nextcol'] (assign-col nextcol (model/affected-wires candidate))]
                      (recur (rest lines) qubits cbits nextcol' (inc id)
                             (conj gates (assoc candidate :col col :id id)) defs nil 0 [] nil))
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
                      (recur (rest lines) qubits cbits nextcol' (inc id)
                             (conj gates (assoc candidate :col col :id id)) defs nil 0 [] nil))
                    :else {:ok false}))

                :else {:ok false}))))))))
