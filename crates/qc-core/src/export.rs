//! Standalone SVG export of a circuit. Port of `export.cljs`.
//!
//! The output is monochrome and self-contained (no external assets), matching
//! the canvas symbols.

use crate::model::{self, cx_geometry, gate_span, Gate, GateKind, State};

pub const GATE_COLOR: &str = "#1f2933";

fn esc(s: &str) -> String {
    s.replace('&', "&amp;")
        .replace('<', "&lt;")
        .replace('>', "&gt;")
        .replace('"', "&quot;")
}

fn gate_label(gate: &Gate) -> String {
    match gate.kind {
        GateKind::Custom => gate.name.clone().unwrap_or_default(),
        GateKind::Id => "I".to_string(),
        GateKind::Rx => "Rx".to_string(),
        GateKind::Ry => "Ry".to_string(),
        GateKind::Rz => "Rz".to_string(),
        GateKind::P => "P".to_string(),
        GateKind::Measure => "M".to_string(),
        _ => gate.kind.qasm_name().to_uppercase(),
    }
}

fn wire_svg(wire: i32, width: i32) -> String {
    let y = model::wire_y(wire);
    format!(
        "<line x1=\"{}\" y1=\"{}\" x2=\"{}\" y2=\"{}\" stroke=\"#9aa5b1\" stroke-width=\"1\"/>",
        model::LINE_START,
        y,
        width,
        y
    )
}

fn standard_gate_svg(gate: &Gate) -> String {
    let x = model::gate_left(gate.col);
    let y = model::gate_top(gate.wire);
    let size = model::GATE_SIZE;
    let cx = x + size / 2;
    let cy = y + size / 2;
    format!(
        "<rect x=\"{}\" y=\"{}\" width=\"{}\" height=\"{}\" rx=\"6\" fill=\"#ffffff\" stroke=\"#3a4a5b\" stroke-width=\"2\"/><text x=\"{}\" y=\"{}\" dy=\"0.35em\" text-anchor=\"middle\" font-family=\"sans-serif\" font-size=\"18\" fill=\"#1f2933\">{}</text>",
        x, y, size, size, cx, cy, esc(&gate_label(gate))
    )
}

fn custom_gate_svg(gate: &Gate) -> String {
    let wires = &gate.wires;
    let above = *wires.iter().min().unwrap();
    let below = *wires.iter().max().unwrap();
    let x = model::gate_left(gate.col);
    let y = model::wire_y(above) - model::GATE_SIZE / 2;
    let height = (model::wire_y(below) - model::wire_y(above)) + model::GATE_SIZE;
    let cx = x + model::GATE_SIZE / 2;
    let cy = y + height / 2;
    format!(
        "<rect x=\"{}\" y=\"{}\" width=\"{}\" height=\"{}\" rx=\"6\" fill=\"#ffffff\" stroke=\"#3a4a5b\" stroke-width=\"2\"/><text x=\"{}\" y=\"{}\" dy=\"0.35em\" text-anchor=\"middle\" font-family=\"sans-serif\" font-size=\"14\" fill=\"#1f2933\">{}</text>",
        x, y, model::GATE_SIZE, height, cx, cy, esc(&gate_label(gate))
    )
}

fn cn_line_svg(x: i32, y1: i32, y2: i32) -> String {
    format!(
        "<line x1=\"{}\" y1=\"{}\" x2=\"{}\" y2=\"{}\" stroke=\"{}\" stroke-width=\"2.5\"/>",
        x, y1, x, y2, GATE_COLOR
    )
}

fn control_dot_svg(cx: i32, cy: i32) -> String {
    format!(
        "<circle cx=\"{}\" cy=\"{}\" r=\"10\" fill=\"{}\" stroke=\"{}\" stroke-width=\"2\"/>",
        cx, cy, GATE_COLOR, GATE_COLOR
    )
}

fn target_cross_svg(cx: i32, cy: i32) -> String {
    format!(
        "<circle cx=\"{}\" cy=\"{}\" r=\"11\" fill=\"none\" stroke=\"{}\" stroke-width=\"2.5\"/><line x1=\"{}\" y1=\"{}\" x2=\"{}\" y2=\"{}\" stroke=\"{}\" stroke-width=\"2.5\"/><line x1=\"{}\" y1=\"{}\" x2=\"{}\" y2=\"{}\" stroke=\"{}\" stroke-width=\"2.5\"/>",
        cx, cy, GATE_COLOR, cx, cy - 15, cx, cy + 15, GATE_COLOR, cx - 15, cy, cx + 15, cy, GATE_COLOR
    )
}

fn cx_gate_svg(gate: &Gate) -> String {
    let target = gate.target.unwrap_or(gate.wire);
    let geo = cx_geometry(gate.wire, target);
    let cxx = model::gate_left(gate.col) + model::GATE_SIZE / 2;
    format!(
        "{}{}{}",
        cn_line_svg(cxx, model::wire_y(geo.above), model::wire_y(geo.below)),
        control_dot_svg(cxx, model::wire_y(gate.wire)),
        target_cross_svg(cxx, model::wire_y(target))
    )
}

fn ccx_gate_svg(gate: &Gate) -> String {
    let control = gate.control.unwrap_or(gate.wire);
    let target = gate.target.unwrap_or(gate.wire);
    let span = gate_span(gate);
    let cxx = model::gate_left(gate.col) + model::GATE_SIZE / 2;
    format!(
        "{}{}{}{}",
        cn_line_svg(cxx, model::wire_y(span.above), model::wire_y(span.below)),
        control_dot_svg(cxx, model::wire_y(gate.wire)),
        control_dot_svg(cxx, model::wire_y(control)),
        target_cross_svg(cxx, model::wire_y(target))
    )
}

fn measurement_gate_svg(gate: &Gate) -> String {
    let cx = model::gate_left(gate.col) + model::GATE_SIZE / 2;
    let cy = model::wire_y(gate.wire);
    format!(
        "<rect x=\"{}\" y=\"{}\" width=\"44\" height=\"44\" rx=\"6\" fill=\"none\" stroke=\"{}\" stroke-width=\"2\"/><path d=\"M {} {} A 13 13 0 0 1 {} {}\" fill=\"none\" stroke=\"{}\" stroke-width=\"2.5\" stroke-linecap=\"round\"/><line x1=\"{}\" y1=\"{}\" x2=\"{}\" y2=\"{}\" stroke=\"{}\" stroke-width=\"2.5\" stroke-linecap=\"round\"/><circle cx=\"{}\" cy=\"{}\" r=\"2\" fill=\"{}\"/>",
        cx - 22, cy - 22, GATE_COLOR,
        cx - 13, cy + 9, cx + 13, cy + 9, GATE_COLOR,
        cx, cy + 9, cx - 8, cy + 1, GATE_COLOR,
        cx, cy + 9, GATE_COLOR
    )
}

fn barrier_gate_svg(gate: &Gate) -> String {
    let wires = &gate.wires;
    let above = *wires.iter().min().unwrap();
    let below = *wires.iter().max().unwrap();
    let x = model::gate_left(gate.col) + model::GATE_SIZE / 2;
    let y1 = model::wire_y(above) - model::GATE_SIZE / 2;
    let y2 = model::wire_y(below) + model::GATE_SIZE / 2;
    format!(
        "<line x1=\"{}\" y1=\"{}\" x2=\"{}\" y2=\"{}\" stroke=\"#3a4a5b\" stroke-width=\"2\" stroke-dasharray=\"6 4\"/>",
        x, y1, x, y2
    )
}

fn gate_svg(gate: &Gate) -> String {
    match gate.kind {
        GateKind::Cx => cx_gate_svg(gate),
        GateKind::Ccx => ccx_gate_svg(gate),
        GateKind::Measure => measurement_gate_svg(gate),
        GateKind::Custom => custom_gate_svg(gate),
        GateKind::Barrier => barrier_gate_svg(gate),
        _ => standard_gate_svg(gate),
    }
}

/// Render the circuit to a standalone SVG document.
pub fn circuit_to_svg(state: &State) -> String {
    let max_col = state.gates.iter().map(|g| g.col).max().unwrap_or(0);
    let width = model::ORIGIN_X + (max_col + 1) * model::COL_GAP + model::COL_GAP;
    let height = model::TOP_PAD + state.qubits * model::WIRE_GAP + model::TOP_PAD;

    let wires: Vec<String> = (0..state.qubits).map(|w| wire_svg(w, width)).collect();
    let mut sorted: Vec<&Gate> = state.gates.iter().collect();
    sorted.sort_by_key(|g| (g.col, g.wire, g.id));
    let body: Vec<String> = sorted.iter().map(|g| gate_svg(g)).collect();

    format!(
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"{}\" height=\"{}\" viewBox=\"0 0 {} {}\">\n<rect width=\"{}\" height=\"{}\" fill=\"#ffffff\"/>\n{}\n{}\n</svg>\n",
        width,
        height,
        width,
        height,
        width,
        height,
        wires.join("\n"),
        body.join("\n")
    )
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::model::Gate;

    #[test]
    fn circuit_svg_test() {
        let svg = circuit_to_svg(&State {
            qubits: 2,
            gates: vec![Gate {
                id: 0,
                ..Gate::new(GateKind::H, 0, 0)
            }],
            ..Default::default()
        });
        assert!(svg.contains("<svg"));
        assert!(svg.contains("</svg>"));
        assert!(svg.contains("<line"));
        assert!(svg.contains(">H<"));
        assert!(svg.contains("fill=\"#ffffff\""));
    }

    #[test]
    fn circuit_svg_empty_test() {
        let svg = circuit_to_svg(&State {
            qubits: 2,
            ..Default::default()
        });
        assert!(svg.contains("<svg"));
    }

    #[test]
    fn circuit_svg_labels_test() {
        let svg = circuit_to_svg(&State {
            qubits: 4,
            gates: vec![
                Gate {
                    id: 0,
                    angle: Some("pi/2".into()),
                    ..Gate::new(GateKind::Rx, 0, 0)
                },
                Gate {
                    id: 1,
                    ..Gate::new(GateKind::Id, 1, 0)
                },
                Gate {
                    id: 2,
                    ..Gate::new(GateKind::P, 2, 0)
                },
                Gate {
                    id: 3,
                    name: Some("foo".into()),
                    wires: vec![0, 2],
                    ..Gate::new(GateKind::Custom, 0, 1)
                },
            ],
            ..Default::default()
        });
        assert!(svg.contains(">Rx<"));
        assert!(svg.contains(">I<"));
        assert!(svg.contains(">P<"));
        assert!(svg.contains(">foo<"));
    }

    #[test]
    fn circuit_svg_cx_test() {
        let svg = circuit_to_svg(&State {
            qubits: 3,
            gates: vec![Gate {
                id: 0,
                target: Some(2),
                ..Gate::new(GateKind::Cx, 0, 0)
            }],
            ..Default::default()
        });
        assert!(svg.contains("<circle"));
        assert!(svg.contains(&format!("y1=\"{}\"", model::wire_y(0))));
        assert!(svg.contains(&format!("y2=\"{}\"", model::wire_y(2))));
        assert_eq!(2, svg.matches("<circle").count());
        assert!(svg.contains(&format!("fill=\"{}\"", GATE_COLOR)));
        assert!(svg.contains("r=\"11\" fill=\"none\""));
        assert!(!svg.contains("r=\"6\""));
        assert!(!svg.contains("#678efa"));
    }

    #[test]
    fn circuit_svg_ccx_test() {
        let svg = circuit_to_svg(&State {
            qubits: 3,
            gates: vec![Gate {
                id: 0,
                control: Some(1),
                target: Some(2),
                ..Gate::new(GateKind::Ccx, 0, 0)
            }],
            ..Default::default()
        });
        assert_eq!(3, svg.matches("<circle").count());
        assert_eq!(2, svg.matches("r=\"10\"").count());
        assert!(svg.contains("r=\"11\" fill=\"none\""));
        assert!(!svg.contains("#678efa"));
    }

    #[test]
    fn circuit_svg_measure_test() {
        let svg = circuit_to_svg(&State {
            qubits: 1,
            gates: vec![Gate {
                id: 0,
                ..Gate::new(GateKind::Measure, 0, 0)
            }],
            ..Default::default()
        });
        assert!(svg.contains("<rect"));
        assert!(svg.contains("rx=\"6\""));
        assert!(svg.contains("<path d=\"M "));
        assert!(svg.contains("r=\"2\""));
        assert!(!svg.contains(">M<"));
        assert!(!svg.contains("#678efa"));
    }

    #[test]
    fn circuit_svg_barrier_test() {
        let svg = circuit_to_svg(&State {
            qubits: 3,
            gates: vec![Gate {
                id: 0,
                wires: vec![0, 2],
                ..Gate::new(GateKind::Barrier, 0, 0)
            }],
            ..Default::default()
        });
        assert!(svg.contains("stroke-dasharray=\"6 4\""));
        assert!(svg.contains(&format!(
            "y1=\"{}\"",
            model::wire_y(0) - model::GATE_SIZE / 2
        )));
        assert!(svg.contains(&format!(
            "y2=\"{}\"",
            model::wire_y(2) + model::GATE_SIZE / 2
        )));
    }

    #[test]
    fn circuit_svg_escapes_test() {
        let svg = circuit_to_svg(&State {
            qubits: 1,
            gates: vec![Gate {
                id: 0,
                name: Some("a<b&c".into()),
                wires: vec![0],
                ..Gate::new(GateKind::Custom, 0, 0)
            }],
            ..Default::default()
        });
        assert!(svg.contains("a&lt;b&amp;c"));
    }
}
