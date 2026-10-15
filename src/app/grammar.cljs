(ns app.grammar
  "Syntactic validation of OpenQASM 3 source.

   The reference grammar is published at https://openqasm.com/grammar/index.html
   (an ANTLR grammar). This module implements a hand-written tokeniser and
   recursive-descent parser for the subset of the grammar the editor produces
   and consumes:

     * the version statement and include statements
     * qubit and bit declarations
     * gate definitions (with or without parameters)
     * gate calls, including control modifiers, and quantum/function calls
     * measure (with or without a classical target), barrier and reset
     * the full expression grammar (arithmetic, comparison, bitwise, logical,
       casts and calls)

   The check is purely syntactic: it performs no semantic analysis (unknown
   gates, out-of-range indices and so on are handled by app.qasm). Statements
   that are part of OpenQASM 3 but outside the modelled subset (control flow,
   pulse calibration, ...) are reported as unsupported rather than accepted.

   validate returns {:ok true :errors []} for a well-formed program, or
   {:ok false :errors [{:line n :col n :message \"...\"}]} otherwise."
  (:require [clojure.string :as string]))

;; ---------------------------------------------------------------------------
;; Tokeniser
;; ---------------------------------------------------------------------------

(def keywords
  #{"OPENQASM" "include" "defcalgrammar" "def" "cal" "defcal" "gate" "extern" "box" "let"
    "break" "continue" "if" "else" "end" "return" "for" "while" "in" "switch" "case" "default"
    "nop" "pragma" "input" "output" "const" "readonly" "mutable"
    "qreg" "qubit" "creg" "bool" "bit" "int" "uint" "float" "angle" "complex" "array" "void"
    "duration" "stretch" "gphase" "inv" "pow" "ctrl" "negctrl" "durationof"
    "delay" "reset" "measure" "barrier" "true" "false"})

(def type-keywords
  #{"bit" "int" "uint" "float" "angle" "bool" "duration" "stretch" "complex" "array" "qubit"})

(def scalar-decl-keywords
  #{"bit" "int" "uint" "float" "angle" "bool" "duration" "stretch"})

(def literal-keywords #{"true" "false"})

(def multi-symbols
  ["**=" "<<=" ">>=" "->" "++" "**" "||" "&&" "==" "!=" ">=" "<=" ">>" "<<"
   "+=" "-=" "*=" "/=" "&=" "|=" "^=" "%="])

(def single-symbols
  (set (map str "[]{}();:,.=+-*/%|&^@~!<>#")))

(defn- digit? [c] (and (>= c 48) (<= c 57)))
(defn- letter? [c] (or (and (>= c 65) (<= c 90)) (and (>= c 97) (<= c 122))))
(defn- ident-start? [c] (or (letter? c) (= c 95) (> c 127)))
(defn- ident-char? [c] (or (ident-start? c) (digit? c)))
(defn- number-char? [c] (or (digit? c) (letter? c) (= c 46) (= c 95)))

(defn- end-of-line [text i]
  (or (string/index-of text "\n" i) (count text)))

(defn tokenize
  "Splits `text` into {:tokens [...] :errors [...]}.

   Each token is {:type :value :line :col}; line numbers are 1-based. Lexical
   errors (unterminated comments and strings, stray characters) are collected
   in :errors so that parsing can continue."
  [text]
  (let [n (count text)
        tok (fn [type value line col] {:type type :value value :line line :col col})]
    (loop [i 0 line 1 line-start 0 tokens [] errors []]
      (if (>= i n)
        {:tokens (conj tokens {:type :eof :value nil :line line :col 1})
         :errors errors}
        (let [c (.charCodeAt text i)
              col (inc (- i line-start))]
          (cond
            (= c 10) (recur (inc i) (inc line) (inc i) tokens errors)
            (= c 13) (recur (inc i) line line-start tokens errors)
            (or (= c 32) (= c 9)) (recur (inc i) line line-start tokens errors)

            (and (= c 47) (< (inc i) n) (= (.charCodeAt text (inc i)) 47))
            (recur (end-of-line text i) line line-start tokens errors)

            (and (= c 47) (< (inc i) n) (= (.charCodeAt text (inc i)) 42))
            (let [end (string/index-of text "*/" (+ i 2))]
              (if (nil? end)
                (recur n line line-start tokens
                       (conj errors {:line line :col col :message "unterminated block comment"}))
                (let [segment (subs text i (+ end 2))
                      nl (count (re-seq #"\n" segment))]
                  (if (pos? nl)
                    (recur (+ end 2) (+ line nl) (+ i (string/last-index-of segment "\n") 1)
                           tokens errors)
                    (recur (+ end 2) line line-start tokens errors)))))

            (or (= c 34) (= c 39))
            (let [quote c
                  end (loop [j (inc i)]
                        (cond
                          (>= j n) nil
                          (= (.charCodeAt text j) 10) nil
                          (= (.charCodeAt text j) quote) j
                          :else (recur (inc j))))]
              (if (nil? end)
                (recur n line line-start tokens
                       (conj errors {:line line :col col :message "unterminated string literal"}))
                (recur (inc end) line line-start
                       (conj tokens (tok :string (subs text (inc i) end) line col)) errors)))

            (or (digit? c)
                (and (= c 46) (< (inc i) n) (digit? (.charCodeAt text (inc i)))))
            (let [end (loop [j i]
                        (cond
                          (>= j n) j
                          (number-char? (.charCodeAt text j)) (recur (inc j))
                          (and (or (= (.charCodeAt text j) 43) (= (.charCodeAt text j) 45))
                               (> j i)
                               (let [p (.charCodeAt text (dec j))] (or (= p 101) (= p 69))))
                          (recur (inc j))
                          :else j))]
              (recur end line line-start
                     (conj tokens (tok :number (subs text i end) line col)) errors))

            (ident-start? c)
            (let [end (loop [j (inc i)]
                        (if (and (< j n) (ident-char? (.charCodeAt text j))) (recur (inc j)) j))
                  value (subs text i end)]
              (recur end line line-start
                     (conj tokens (tok (if (contains? keywords value) :keyword :ident) value line col))
                     errors))

            (= c 36)
            (let [end (loop [j (inc i)]
                        (if (and (< j n) (digit? (.charCodeAt text j))) (recur (inc j)) j))]
              (if (> end (inc i))
                (recur end line line-start
                       (conj tokens (tok :hardware (subs text i end) line col)) errors)
                (recur (inc i) line line-start tokens
                       (conj errors {:line line :col col :message "expected digits after '$'"}))))

            :else
            (let [remaining (subs text i (min n (+ i 4)))
                  sym (first (filter #(string/starts-with? remaining %) multi-symbols))
                  single (when (contains? single-symbols (.charAt text i)) (.charAt text i))]
              (cond
                sym (recur (+ i (count sym)) line line-start
                           (conj tokens (tok :symbol sym line col)) errors)
                single (recur (inc i) line line-start
                              (conj tokens (tok :symbol single line col)) errors)
                :else (recur (inc i) line line-start tokens
                             (conj errors {:line line :col col
                                           :message (str "unexpected character '" (.charAt text i) "'")}))))))))))

;; ---------------------------------------------------------------------------
;; Parser
;; ---------------------------------------------------------------------------

(def binary-precedence
  {"||" 1 "&&" 2 "|" 3 "^" 4 "&" 5
   "==" 6 "!=" 6
   "<" 7 ">" 7 "<=" 7 ">=" 7
   "<<" 8 ">>" 8
   "+" 9 "-" 9
   "*" 10 "/" 10 "%" 10
   "**" 11})

(def unary-operators #{"-" "+" "~" "!"})
(def gate-modifiers #{"inv" "pow" "ctrl" "negctrl"})
(def assignment-ops
  #{"=" "+=" "-=" "*=" "/=" "&=" "|=" "^=" "<<=" ">>=" "%=" "**="})

(declare parse-expression parse-call-args parse-index parse-measure-expression
         parse-statement parse-scope parse-statement-or-scope)

(defn- current [st] (nth (:tokens st) (:pos st)))
(defn- current-value [st] (:value (current st)))
(defn- current-type [st] (:type (current st)))
(defn- at? [st value] (= value (:value (current st))))
(defn- at-type? [st type] (= type (:type (current st))))
(defn- advance [st] (update st :pos inc))

(defn- add-error [st message]
  (let [t (current st)]
    (update st :errors conj {:line (:line t) :col (:col t) :message message})))

(defn- expect-symbol [st sym message]
  (if (at? st sym) (advance st) (add-error st message)))

;; --- expressions ---

(defn- parse-primary [st]
  (let [t (current st)
        v (:value t)
        tp (:type t)]
    (cond
      (contains? #{:number :string :hardware} tp) (advance st)
      (and (= :keyword tp) (contains? literal-keywords v)) (advance st)
      (and (= :keyword tp) (contains? type-keywords v))
      (let [st (advance st)]
        (if (at? st "(")
          (parse-call-args st)
          (add-error st (str "expected '(' after type cast '" v "'"))))
      (= :ident tp)
      (let [st (advance st)]
        (if (at? st "(") (parse-call-args st) st))
      (= "(" v)
      (let [st (advance st)
            st (parse-expression st)]
        (expect-symbol st ")" "expected ')'"))
      :else (add-error st "expected an expression"))))

(defn- parse-postfix [st]
  (loop [st st]
    (if (at? st "[") (recur (parse-index st)) st)))

(defn- parse-unary [st]
  (if (and (= :symbol (current-type st)) (contains? unary-operators (current-value st)))
    (parse-unary (advance st))
    (parse-postfix (parse-primary st))))

(defn- parse-binary [st min-prec]
  (loop [st st]
    (let [op (current-value st)
          prec (get binary-precedence op)]
      (if (and prec (>= prec min-prec))
        (let [st (advance st)
              next-min (if (= op "**") prec (inc prec))]
          (recur (parse-binary (parse-unary st) next-min)))
        st))))

(defn- parse-expression [st]
  (parse-binary (parse-unary st) 1))

(defn- parse-index-item [st]
  (let [st (if (at? st ":") st (parse-expression st))]
    (if (at? st ":")
      (let [st (advance st)
            st (if (or (at? st ":") (at? st "]") (at? st ",")) st (parse-expression st))]
        (if (at? st ":")
          (let [st (advance st)]
            (if (or (at? st "]") (at? st ",")) st (parse-expression st)))
          st))
      st)))

(defn- parse-index [st]
  (let [st (advance st)]
    (let [st (if (at? st "]")
               st
               (loop [st (parse-index-item st)]
                 (if (at? st ",")
                   (let [st (advance st)]
                     (if (at? st "]") st (recur (parse-index-item st))))
                   st)))]
      (expect-symbol st "]" "expected ']'"))))

(defn- parse-call-args [st]
  (let [st (advance st)]
    (let [st (if (at? st ")")
               st
               (loop [st (parse-expression st)]
                 (if (at? st ",")
                   (let [st (advance st)]
                     (if (at? st ")") st (recur (parse-expression st))))
                   st)))]
      (expect-symbol st ")" "expected ')'"))))

;; --- operands ---

(defn- parse-indexed-identifier [st]
  (let [st (advance st)]
    (loop [st st]
      (if (at? st "[") (recur (parse-index st)) st))))

(defn- gate-operand? [st]
  (or (at-type? st :hardware) (at-type? st :ident)))

(defn- parse-gate-operand [st]
  (cond
    (at-type? st :hardware) (advance st)
    (at-type? st :ident) (parse-indexed-identifier st)
    :else (add-error st "expected a gate operand")))

(defn- parse-gate-operand-list [st]
  (if (gate-operand? st)
    (loop [st (parse-gate-operand st)]
      (if (at? st ",")
        (let [st (advance st)]
          (if (gate-operand? st) (recur (parse-gate-operand st)) st))
        st))
    st))

;; --- statements ---

(defn- parse-version [st]
  (let [st (advance st)
        st (if (at-type? st :number)
             (advance st)
             (add-error st "expected a version number after OPENQASM"))]
    (expect-symbol st ";" "expected ';' after the version")))

(defn- parse-include [st]
  (let [st (advance st)
        st (if (at-type? st :string)
             (advance st)
             (add-error st "expected a string after include"))]
    (expect-symbol st ";" "expected ';' after the include path")))

(defn- parse-declaration-expression [st]
  (if (at? st "measure")
    (parse-measure-expression st)
    (parse-expression st)))

(defn- parse-register-decl [st message]
  (let [st (advance st)
        st (if (at? st "[") (parse-index st) st)
        st (if (at-type? st :ident)
             (advance st)
             (add-error st "expected a register name"))
        st (if (at? st "=")
             (parse-declaration-expression (advance st))
             st)]
    (expect-symbol st ";" message)))

(defn- parse-paren-params [st]
  (let [st (advance st)
        st (if (at-type? st :ident)
             (loop [st (advance st)]
               (if (at? st ",")
                 (let [st (advance st)]
                   (if (at-type? st :ident) (recur (advance st)) st))
                 st))
             st)]
    (expect-symbol st ")" "expected ')' after the parameter list")))

(defn- parse-bare-identifier-list [st message]
  (if (at-type? st :ident)
    (loop [st (advance st)]
      (if (at? st ",")
        (let [st (advance st)]
          (if (at-type? st :ident) (recur (advance st)) st))
        st))
    (add-error st message)))

(defn- parse-gate-def [st]
  (let [st (advance st)
        st (if (at-type? st :ident)
             (advance st)
             (add-error st "expected a gate name"))
        st (if (at? st "(") (parse-paren-params st) st)
        st (parse-bare-identifier-list st "expected the gate qubit parameters")]
    (if (at? st "{")
      (parse-scope st)
      (add-error st "expected '{' to begin the gate body"))))

(defn- parse-measure [st]
  (let [st (advance st)
        st (parse-gate-operand st)
        st (if (at? st "->")
             (let [st (advance st)]
               (if (at-type? st :ident)
                 (parse-indexed-identifier st)
                 (add-error st "expected a classical bit target after '->'")))
             st)]
    (expect-symbol st ";" "expected ';' after measure")))

(defn- parse-barrier [st]
  (let [st (advance st)
        st (parse-gate-operand-list st)]
    (expect-symbol st ";" "expected ';' after barrier")))

(defn- parse-reset [st]
  (let [st (advance st)
        st (parse-gate-operand st)]
    (expect-symbol st ";" "expected ';' after reset")))

(defn- parse-gate-modifiers [st]
  (loop [st st]
    (let [v (current-value st)]
      (if (and (= :keyword (current-type st)) (contains? gate-modifiers v))
        (let [st (advance st)
              st (if (and (not= v "inv") (at? st "(")) (parse-call-args st) st)]
          (if (at? st "@")
            (recur (advance st))
            (add-error st "expected '@' after a gate modifier")))
        st))))

(defn- parse-measure-expression [st]
  (parse-gate-operand (advance st)))

(defn- parse-invocation [st]
  (let [st (parse-gate-modifiers st)]
    (if (or (at-type? st :ident) (at? st "gphase"))
      (let [name (current-value st)
            st (advance st)
            call? (at? st "(")
            st (if call? (parse-call-args st) st)
            st (if (at? st "[") (parse-index st) st)]
        (cond
          (contains? assignment-ops (current-value st))
          (let [st (advance st)
                st (if (at? st "measure") (parse-measure-expression st) (parse-expression st))]
            (expect-symbol st ";" "expected ';' after the assignment"))

          :else
          (let [start (:pos st)
                st (parse-gate-operand-list st)
                has-operands? (> (:pos st) start)]
            (if (and (not call?) (not= name "gphase") (not has-operands?))
              (add-error st "expected at least one gate operand")
              (expect-symbol st ";" "expected ';' after the gate call")))))
      (add-error st "expected a gate or function name"))))

(defn- parse-expression-statement [st]
  (let [st (parse-expression st)]
    (expect-symbol st ";" "expected ';' after the expression")))

(defn- parse-statement [st]
  (let [v (current-value st)
        tp (current-type st)]
    (cond
      (= :keyword tp)
      (cond
        (= v "OPENQASM") (parse-version st)
        (= v "include") (parse-include st)
        (= v "gate") (parse-gate-def st)
        (= v "measure") (parse-measure st)
        (= v "barrier") (parse-barrier st)
        (= v "reset") (parse-reset st)
        (= v "qubit") (parse-register-decl st "expected ';' after the qubit declaration")
        (contains? scalar-decl-keywords v) (parse-register-decl st "expected ';' after the declaration")
        (= v "gphase") (parse-invocation st)
        (contains? gate-modifiers v) (parse-invocation st)
        (contains? literal-keywords v) (parse-expression-statement st)
        :else (add-error st (str "statement '" v "' is not supported by this validator")))

      (= :ident tp) (parse-invocation st)

      (contains? #{:number :string :hardware} tp) (parse-expression-statement st)

      (= :symbol tp)
      (cond
        (= v "{") (parse-scope st)
        (= v "(") (parse-expression-statement st)
        :else (add-error st (str "unexpected token '" v "'")))

      :else (add-error st "unexpected end of input"))))

(defn- parse-statement-or-scope [st]
  (if (at? st "{") (parse-scope st) (parse-statement st)))

(defn- recover-statement [st]
  (loop [st st]
    (let [t (current st)]
      (cond
        (= :eof (:type t)) st
        (= "}" (:value t)) st
        (= ";" (:value t)) (advance st)
        :else (recur (advance st))))))

(defn- parse-statement-safe [st]
  (let [before (:pos st)
        st' (parse-statement-or-scope st)]
    (if (= before (:pos st'))
      (let [st'' (recover-statement st')]
        (if (= before (:pos st'')) (advance st'') st''))
      st')))

(defn- parse-scope [st]
  (let [st (advance st)]
    (loop [st st]
      (let [t (current st)]
        (cond
          (= "}" (:value t)) (advance st)
          (= :eof (:type t)) (add-error st "unexpected end of input, expected '}'")
          :else (recur (parse-statement-safe st)))))))

(defn- parse-program [st]
  (let [st (if (at? st "OPENQASM") (parse-version st) st)]
    (loop [st st]
      (if (= :eof (current-type st))
        st
        (recur (parse-statement-safe st))))))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn validate
  "Validates `text` against the OpenQASM 3 grammar subset supported by the
   editor. Returns {:ok true :errors []} when the source is syntactically
   valid, or {:ok false :errors [...]} with one entry per problem found."
  [text]
  (let [{:keys [tokens errors]} (tokenize (or text ""))
        errs (:errors (parse-program {:tokens tokens :pos 0 :errors errors}))]
    (if (empty? errs)
      {:ok true :errors []}
      {:ok false :errors errs})))

(defn valid? [text]
  (boolean (:ok (validate text))))

(defn first-error [text]
  (first (:errors (validate text))))
