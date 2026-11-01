(ns app.export
  (:require [clojure.string :as string]
            [app.model :as model]))

(defn- esc [s]
  (-> (str s)
      (string/replace "&" "&amp;")
      (string/replace "<" "&lt;")
      (string/replace ">" "&gt;")
      (string/replace "\"" "&quot;")))

(defn- gate-label [{:keys [type name angle]}]
  (case type
    :custom name
    :id "I"
    :rx "Rx"
    :ry "Ry"
    :rz "Rz"
    :p "P"
    :measure "M"
    (string/upper-case (get-in model/gate-types [type :qasm] "?"))))

(def gate-color "#1f2933")

(defn- wire-svg [wire width]
  (let [y (model/wire-y wire)]
    (str "<line x1=\"" model/line-start "\" y1=\"" y "\" x2=\"" width "\" y2=\"" y "\" "
         "stroke=\"#9aa5b1\" stroke-width=\"1\"/>")))

(defn- standard-gate-svg [{:keys [type col wire] :as gate}]
  (let [x (model/gate-left col)
        y (model/gate-top wire)
        size model/gate-size
        cx (+ x (/ size 2))
        cy (+ y (/ size 2))]
    (str "<rect x=\"" x "\" y=\"" y "\" width=\"" size "\" height=\"" size "\" rx=\"6\" "
         "fill=\"#ffffff\" stroke=\"#3a4a5b\" stroke-width=\"2\"/>"
         "<text x=\"" cx "\" y=\"" cy "\" dy=\"0.35em\" text-anchor=\"middle\" "
         "font-family=\"sans-serif\" font-size=\"18\" fill=\"#1f2933\">"
         (esc (gate-label gate)) "</text>")))

(defn- custom-gate-svg [{:keys [col wires] :as gate}]
  (let [above (apply min wires)
        below (apply max wires)
        x (model/gate-left col)
        y (- (model/wire-y above) (/ model/gate-size 2))
        height (+ (- (model/wire-y below) (model/wire-y above)) model/gate-size)
        cx (+ x (/ model/gate-size 2))
        cy (+ y (/ height 2))]
    (str "<rect x=\"" x "\" y=\"" y "\" width=\"" model/gate-size "\" height=\"" height "\" rx=\"6\" "
         "fill=\"#ffffff\" stroke=\"#3a4a5b\" stroke-width=\"2\"/>"
         "<text x=\"" cx "\" y=\"" cy "\" dy=\"0.35em\" text-anchor=\"middle\" "
         "font-family=\"sans-serif\" font-size=\"14\" fill=\"#1f2933\">"
         (esc (gate-label gate)) "</text>")))

(defn- cn-line-svg [x y1 y2]
  (str "<line x1=\"" x "\" y1=\"" y1 "\" x2=\"" x "\" y2=\"" y2 "\" "
       "stroke=\"" gate-color "\" stroke-width=\"2.5\"/>"))

(defn- control-dot-svg [cx cy]
  (str "<circle cx=\"" cx "\" cy=\"" cy "\" r=\"10\" fill=\"" gate-color "\" "
       "stroke=\"" gate-color "\" stroke-width=\"2\"/>"))

(defn- target-cross-svg [cx cy]
  (str "<circle cx=\"" cx "\" cy=\"" cy "\" r=\"11\" fill=\"none\" "
       "stroke=\"" gate-color "\" stroke-width=\"2.5\"/>"
       "<line x1=\"" cx "\" y1=\"" (- cy 15) "\" x2=\"" cx "\" y2=\"" (+ cy 15) "\" "
       "stroke=\"" gate-color "\" stroke-width=\"2.5\"/>"
       "<line x1=\"" (- cx 15) "\" y1=\"" cy "\" x2=\"" (+ cx 15) "\" y2=\"" cy "\" "
       "stroke=\"" gate-color "\" stroke-width=\"2.5\"/>"))

(defn- cx-gate-svg [{:keys [col wire target]}]
  (let [{:keys [above below]} (model/cx-geometry wire target)
        cxx (+ (model/gate-left col) (/ model/gate-size 2))]
    (str (cn-line-svg cxx (model/wire-y above) (model/wire-y below))
         (control-dot-svg cxx (model/wire-y wire))
         (target-cross-svg cxx (model/wire-y target)))))

(defn- ccx-gate-svg [gate]
  (let [{:keys [col wire control target]} gate
        {:keys [above below]} (model/gate-span gate)
        cxx (+ (model/gate-left col) (/ model/gate-size 2))]
    (str (cn-line-svg cxx (model/wire-y above) (model/wire-y below))
         (control-dot-svg cxx (model/wire-y wire))
         (control-dot-svg cxx (model/wire-y control))
         (target-cross-svg cxx (model/wire-y target)))))

(defn- measurement-gate-svg [{:keys [col wire]}]
  (let [cx (+ (model/gate-left col) (/ model/gate-size 2))
        cy (model/wire-y wire)]
    (str "<rect x=\"" (- cx 22) "\" y=\"" (- cy 22) "\" width=\"44\" height=\"44\" rx=\"6\" "
         "fill=\"none\" stroke=\"" gate-color "\" stroke-width=\"2\"/>"
         "<path d=\"M " (- cx 13) " " (+ cy 9) " A 13 13 0 0 1 " (+ cx 13) " " (+ cy 9) "\" "
         "fill=\"none\" stroke=\"" gate-color "\" stroke-width=\"2.5\" stroke-linecap=\"round\"/>"
         "<line x1=\"" cx "\" y1=\"" (+ cy 9) "\" x2=\"" (- cx 8) "\" y2=\"" (+ cy 1) "\" "
         "stroke=\"" gate-color "\" stroke-width=\"2.5\" stroke-linecap=\"round\"/>"
         "<circle cx=\"" cx "\" cy=\"" (+ cy 9) "\" r=\"2\" fill=\"" gate-color "\"/>")))

(defn- barrier-gate-svg [{:keys [col wires]}]
  (let [above (apply min wires)
        below (apply max wires)
        x (+ (model/gate-left col) (/ model/gate-size 2))
        y1 (- (model/wire-y above) (/ model/gate-size 2))
        y2 (+ (model/wire-y below) (/ model/gate-size 2))]
    (str "<line x1=\"" x "\" y1=\"" y1 "\" x2=\"" x "\" y2=\"" y2 "\" "
         "stroke=\"#3a4a5b\" stroke-width=\"2\" stroke-dasharray=\"6 4\"/>")))

(defn- gate-svg [gate]
  (case (:type gate)
    :cx (cx-gate-svg gate)
    :ccx (ccx-gate-svg gate)
    :measure (measurement-gate-svg gate)
    :custom (custom-gate-svg gate)
    :barrier (barrier-gate-svg gate)
    (standard-gate-svg gate)))

(defn circuit->svg [{:keys [qubits gates]}]
  (let [max-col (apply max 0 (map :col gates))
        width (+ model/origin-x (* (inc max-col) model/col-gap) model/col-gap)
        height (+ model/top-pad (* qubits model/wire-gap) model/top-pad)
        wires (map #(wire-svg % width) (range qubits))
        body (map gate-svg (sort-by (juxt :col :wire :id) gates))]
    (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
         "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"" width "\" height=\"" height "\" "
         "viewBox=\"0 0 " width " " height "\">\n"
         "<rect width=\"" width "\" height=\"" height "\" fill=\"#ffffff\"/>\n"
         (string/join "\n" wires) "\n"
         (string/join "\n" body) "\n"
         "</svg>\n")))
