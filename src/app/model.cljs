(ns app.model)

(def gate-types
  {:x {:qasm "x" :src "static/x.svg"}
   :y {:qasm "y" :src "static/y.svg"}
   :z {:qasm "z" :src "static/z.svg"}
   :h {:qasm "h" :src "static/hadamard.svg"}
   :s {:qasm "s" :src "static/s.svg"}
   :t {:qasm "t" :src "static/t.svg"}
   :sdg {:qasm "sdg" :src "static/sdg.svg"}
   :tdg {:qasm "tdg" :src "static/tdg.svg"}
   :rx {:qasm "rx" :src "static/rx.svg" :angle "pi/2"}
   :ry {:qasm "ry" :src "static/ry.svg" :angle "pi/2"}
   :rz {:qasm "rz" :src "static/rz.svg" :angle "pi/2"}
   :p {:qasm "p" :src "static/phase.svg" :angle "pi/2"}
   :id {:qasm "id" :src "static/identity.svg"}
   :cx {:qasm "cx" :src "static/cx.svg"}
   :measure {:qasm "measure" :src "static/measure.svg"}
   :barrier {:qasm "barrier" :src "static/barrier.svg"}})

(def palette-types
  [:h :x :y :z :s :t :sdg :tdg :rx :ry :rz :p :id :cx :measure :barrier])

(defn gate-src [type]
  (get-in gate-types [type :src] "static/hadamard.svg"))

(def min-qubits 1)
(def max-qubits 12)
(def min-cbits 0)
(def max-cbits 12)
(def wire-gap 90)
(def col-gap 70)
(def top-pad 50)
(def line-start 70)
(def zero-center 35)
(def origin-x 100)
(def gate-size 46)
(def bottom-pad 60)
(def right-pad 60)
(def min-cols 4)
(def min-canvas-width 480)

(def initial-state {:qubits 4 :cbits 0 :next-id 0 :gates [] :defs {}})

(def state (atom initial-state))

(defn wire-y [wire]
  (+ top-pad (* wire wire-gap)))

(defn cwire-y [idx qubits]
  (+ top-pad (* (+ qubits idx) wire-gap)))

(defn col-x [col]
  (+ origin-x (* col col-gap)))

(defn gate-left [col]
  (- (col-x col) (/ gate-size 2)))

(defn gate-top [wire]
  (- (wire-y wire) (/ gate-size 2)))

(defn canvas-size [{:keys [qubits cbits gates]}]
  (let [max-col (apply max 0 (map :col gates))
        cols (max min-cols (inc max-col))
        rows (+ qubits (if (pos? (or cbits 0)) 1 0))]
    {:width (max (+ origin-x (* cols col-gap) right-pad) min-canvas-width)
     :height (+ top-pad (* rows wire-gap) bottom-pad)}))

(defn snap-wire [y qubits]
  (int (-> (/ (- y top-pad) wire-gap) Math/round (max 0) (min (dec qubits)))))

(defn snap-col [x]
  (int (max 0 (Math/round (/ (- x origin-x) col-gap)))))

(defn gate-wires [{:keys [type wire target wires]}]
  (cond
    (= type :cx) (range (min wire target) (inc (max wire target)))
    wires wires
    :else [wire]))

(defn blocks-cell? [gate wire col]
  (and (= (:col gate) col)
       (or (= (:wire gate) wire)
           (= (:target gate) wire)
           (and (= (:type gate) :cx)
                (< (min (:wire gate) (:target gate))
                   wire
                   (max (:wire gate) (:target gate))))
           (and (contains? #{:custom :barrier} (:type gate))
                (some #(= % wire) (gate-wires gate))))))

(defn blocked? [gates wire col]
  (boolean (some #(blocks-cell? % wire col) gates)))

(defn affected-wires [gate]
  (vec (gate-wires gate)))

(defn free-col? [gates col wires]
  (every? #(not (blocked? gates % col)) wires))

(defn free-wire-span? [gates col control wire]
  (every? #(not (blocked? gates % col)) (range (min control wire) (inc (max control wire)))))

(defn nearest-free-col [gates col wires]
  (if (free-col? gates col wires)
    col
    (let [candidates (mapcat (fn [d] [(+ col d) (- col d)]) (range 1 64))]
      (or (first (filter #(and (>= % 0) (free-col? gates % wires)) candidates))
          col))))

(defn cx-target [control qubits]
  (if (< control (dec qubits))
    (inc control)
    (dec control)))

(defn target-cell
  [{:keys [qubits gates] :as _state} type x y]
  (let [wire (snap-wire y qubits)
        target (when (= type :cx) (cx-target wire qubits))
        candidate (cond-> {:type type :wire wire :target target
                           :angle (get-in gate-types [type :angle])}
                    (= type :barrier) (assoc :wires (vec (range qubits))))
        col (nearest-free-col gates (snap-col x) (affected-wires candidate))]
    (assoc candidate :col col)))

(defn cx-geometry [control target]
  (let [above (min control target)
        below (max control target)]
    {:above above
     :below below
     :span (- (wire-y below) (wire-y above))
     :control-at-top? (< control target)}))

(defn nearest-free-wire [gates col desired control qubits]
  (if (and (not= desired control) (free-wire-span? gates col control desired))
    desired
    (let [candidates (mapcat (fn [d] [(+ desired d) (- desired d)]) (range 1 qubits))]
      (or (first (filter #(and (>= % 0)
                               (< % qubits)
                               (not= % control)
                               (free-wire-span? gates col control %))
                         candidates))
          desired))))

(defn add-gate-to-state [current gate]
  (-> current
      (update :next-id inc)
      (update :gates conj (assoc gate :id (:next-id current)))))

(defn remove-gate-from-state [current id]
  (update current :gates (fn [gates] (filterv #(not= (:id %) id) gates))))

(defn set-qubits-in-state [current raw]
  (let [qubits (max min-qubits (min max-qubits (int raw)))]
    (-> current
        (assoc :qubits qubits)
        (update :gates (fn [gates]
                         (filterv #(every? (fn [w] (< w qubits)) (gate-wires %)) gates))))))

(defn measure-gate? [gate]
  (= (:type gate) :measure))

(defn ensure-measure-cbits [current]
  (if (and (zero? (or (:cbits current) 0))
           (some measure-gate? (:gates current)))
    (let [qubits (or (:qubits current) min-cbits)]
      (assoc current :cbits (max min-cbits (min max-cbits qubits))))
    current))
