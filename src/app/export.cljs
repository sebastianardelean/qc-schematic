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

(defn- cx-gate-svg [{:keys [col wire target]}]
  (let [{:keys [above below]} (model/cx-geometry wire target)
        x (model/gate-left col)
        cxx (+ x (/ model/gate-size 2))
        top (model/wire-y above)
        bottom (model/wire-y below)]
    (str "<line x1=\"" cxx "\" y1=\"" top "\" x2=\"" cxx "\" y2=\"" bottom "\" "
         "stroke=\"#3a4a5b\" stroke-width=\"2\"/>"
         "<circle cx=\"" cxx "\" cy=\"" (model/wire-y wire) "\" r=\"5\" fill=\"#3a4a5b\"/>"
         "<circle cx=\"" cxx "\" cy=\"" (model/wire-y target) "\" r=\"12\" "
         "fill=\"none\" stroke=\"#3a4a5b\" stroke-width=\"2\"/>"
         "<circle cx=\"" cxx "\" cy=\"" (model/wire-y target) "\" r=\"6\" fill=\"#3a4a5b\"/>")))

(defn- gate-svg [gate]
  (case (:type gate)
    :cx (cx-gate-svg gate)
    :custom (custom-gate-svg gate)
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
