//! Circuit model: gate types, geometry, placement rules and undo history.
//!
//! This is a direct port of the original `model.cljs`. Coordinates are whole
//! pixels so that canvas layout and SVG export agree exactly.

use std::collections::BTreeMap;

/// The kind of a gate. `Custom` represents a user-defined `gate` call.
#[derive(Clone, Copy, PartialEq, Eq, Debug, Hash)]
pub enum GateKind {
    X,
    Y,
    Z,
    H,
    S,
    T,
    Sdg,
    Tdg,
    Rx,
    Ry,
    Rz,
    P,
    Id,
    Cx,
    Ccx,
    Measure,
    Barrier,
    Custom,
}

impl GateKind {
    /// The OpenQASM name emitted for the gate (`""` for custom gates, which
    /// carry their own name).
    pub fn qasm_name(self) -> &'static str {
        match self {
            GateKind::X => "x",
            GateKind::Y => "y",
            GateKind::Z => "z",
            GateKind::H => "h",
            GateKind::S => "s",
            GateKind::T => "t",
            GateKind::Sdg => "sdg",
            GateKind::Tdg => "tdg",
            GateKind::Rx => "rx",
            GateKind::Ry => "ry",
            GateKind::Rz => "rz",
            GateKind::P => "p",
            GateKind::Id => "id",
            GateKind::Cx => "cx",
            GateKind::Ccx => "ccx",
            GateKind::Measure => "measure",
            GateKind::Barrier => "barrier",
            GateKind::Custom => "",
        }
    }

    /// Default angle argument for parameterised gates.
    pub fn default_angle(self) -> Option<&'static str> {
        match self {
            GateKind::Rx | GateKind::Ry | GateKind::Rz | GateKind::P => Some("pi/2"),
            _ => None,
        }
    }

    /// The palette icon asset used by the original DOM UI. Retained for parity
    /// with `gate-src`; the egui canvas draws vector shapes instead.
    pub fn asset(self) -> &'static str {
        match self {
            GateKind::X => "static/x.svg",
            GateKind::Y => "static/y.svg",
            GateKind::Z => "static/z.svg",
            GateKind::H => "static/hadamard.svg",
            GateKind::S => "static/s.svg",
            GateKind::T => "static/t.svg",
            GateKind::Sdg => "static/sdg.svg",
            GateKind::Tdg => "static/tdg.svg",
            GateKind::Rx => "static/rx.svg",
            GateKind::Ry => "static/ry.svg",
            GateKind::Rz => "static/rz.svg",
            GateKind::P => "static/phase.svg",
            GateKind::Id => "static/identity.svg",
            GateKind::Cx => "static/cx.svg",
            GateKind::Ccx => "static/ccx.svg",
            GateKind::Measure => "static/measure.svg",
            GateKind::Barrier => "static/barrier.svg",
            GateKind::Custom => "static/hadamard.svg",
        }
    }

    /// Parse a gate kind from its OpenQASM name.
    pub fn from_qasm_name(name: &str) -> Option<GateKind> {
        Some(match name {
            "x" => GateKind::X,
            "y" => GateKind::Y,
            "z" => GateKind::Z,
            "h" => GateKind::H,
            "s" => GateKind::S,
            "t" => GateKind::T,
            "sdg" => GateKind::Sdg,
            "tdg" => GateKind::Tdg,
            "rx" => GateKind::Rx,
            "ry" => GateKind::Ry,
            "rz" => GateKind::Rz,
            "p" => GateKind::P,
            "id" => GateKind::Id,
            "cx" => GateKind::Cx,
            "ccx" => GateKind::Ccx,
            "measure" => GateKind::Measure,
            "barrier" => GateKind::Barrier,
            _ => return None,
        })
    }

    pub fn is_cx(self) -> bool {
        self == GateKind::Cx
    }

    pub fn is_ccx(self) -> bool {
        self == GateKind::Ccx
    }
}

/// Palette order, matching `palette-types` in the original.
pub const PALETTE_TYPES: [GateKind; 17] = [
    GateKind::H,
    GateKind::X,
    GateKind::Y,
    GateKind::Z,
    GateKind::S,
    GateKind::T,
    GateKind::Sdg,
    GateKind::Tdg,
    GateKind::Rx,
    GateKind::Ry,
    GateKind::Rz,
    GateKind::P,
    GateKind::Id,
    GateKind::Cx,
    GateKind::Ccx,
    GateKind::Measure,
    GateKind::Barrier,
];

/// A placed gate. `wire` is the primary wire (the control for CX, the first
/// control for CCX). `wires` is used by custom gates and barriers.
#[derive(Clone, PartialEq, Debug)]
pub struct Gate {
    pub id: u64,
    pub kind: GateKind,
    pub col: i32,
    pub wire: i32,
    pub target: Option<i32>,
    pub control: Option<i32>,
    pub wires: Vec<i32>,
    pub angle: Option<String>,
    pub name: Option<String>,
}

impl Gate {
    pub fn new(kind: GateKind, wire: i32, col: i32) -> Self {
        Gate {
            id: 0,
            kind,
            col,
            wire,
            target: None,
            control: None,
            wires: Vec::new(),
            angle: None,
            name: None,
        }
    }
}

/// A user-defined `gate` definition captured from source.
#[derive(Clone, PartialEq, Debug)]
pub struct GateDef {
    pub arity: usize,
    pub lines: Vec<String>,
}

/// The complete circuit state.
#[derive(Clone, PartialEq, Debug)]
pub struct State {
    pub qubits: i32,
    pub cbits: i32,
    pub next_id: u64,
    pub gates: Vec<Gate>,
    pub defs: BTreeMap<String, GateDef>,
}

impl Default for State {
    fn default() -> Self {
        State {
            qubits: 4,
            cbits: 0,
            next_id: 0,
            gates: Vec::new(),
            defs: BTreeMap::new(),
        }
    }
}

pub const MIN_QUBITS: i32 = 1;
pub const MAX_QUBITS: i32 = 12;
pub const MIN_CBITS: i32 = 0;
pub const MAX_CBITS: i32 = 12;
pub const WIRE_GAP: i32 = 90;
pub const COL_GAP: i32 = 70;
pub const TOP_PAD: i32 = 50;
pub const LINE_START: i32 = 70;
pub const ZERO_CENTER: i32 = 35;
pub const ORIGIN_X: i32 = 100;
pub const GATE_SIZE: i32 = 46;
pub const BOTTOM_PAD: i32 = 60;
pub const RIGHT_PAD: i32 = 60;
pub const MIN_COLS: i32 = 4;
pub const MIN_CANVAS_WIDTH: i32 = 480;

/// Undo/redo history over circuit states.
#[derive(Default)]
pub struct UndoStack {
    past: Vec<State>,
    future: Vec<State>,
}

impl UndoStack {
    pub fn new() -> Self {
        UndoStack::default()
    }

    /// Record `current` as a restore point before a mutation.
    pub fn save(&mut self, current: &State) {
        self.past.push(current.clone());
    }

    pub fn clear_future(&mut self) {
        self.future.clear();
    }

    /// Drop all history.
    pub fn clear(&mut self) {
        self.past.clear();
        self.future.clear();
    }

    pub fn can_undo(&self) -> bool {
        !self.past.is_empty()
    }

    pub fn can_redo(&self) -> bool {
        !self.future.is_empty()
    }

    /// Move `current` to the future stack and restore the previous state.
    /// Returns true when an undo happened.
    pub fn undo(&mut self, current: &mut State) -> bool {
        match self.past.pop() {
            Some(prev) => {
                self.future.push(current.clone());
                *current = prev;
                true
            }
            None => false,
        }
    }

    /// Re-apply a future state. Returns true when a redo happened.
    pub fn redo(&mut self, current: &mut State) -> bool {
        match self.future.pop() {
            Some(next) => {
                self.past.push(current.clone());
                *current = next;
                true
            }
            None => false,
        }
    }
}

pub fn wire_y(wire: i32) -> i32 {
    TOP_PAD + wire * WIRE_GAP
}

pub fn cwire_y(idx: i32, qubits: i32) -> i32 {
    TOP_PAD + (qubits + idx) * WIRE_GAP
}

pub fn col_x(col: i32) -> i32 {
    ORIGIN_X + col * COL_GAP
}

pub fn gate_left(col: i32) -> i32 {
    col_x(col) - GATE_SIZE / 2
}

pub fn gate_top(wire: i32) -> i32 {
    wire_y(wire) - GATE_SIZE / 2
}

pub fn canvas_size(state: &State) -> (i32, i32) {
    let max_col = state.gates.iter().map(|g| g.col).max().unwrap_or(0);
    let cols = MIN_COLS.max(max_col + 1);
    let rows = state.qubits + if state.cbits > 0 { 1 } else { 0 };
    let width = (ORIGIN_X + cols * COL_GAP + RIGHT_PAD).max(MIN_CANVAS_WIDTH);
    let height = TOP_PAD + rows * WIRE_GAP + BOTTOM_PAD;
    (width, height)
}

fn round_i32(v: f32) -> i32 {
    v.round() as i32
}

pub fn snap_wire(y: f32, qubits: i32) -> i32 {
    let w = round_i32((y - TOP_PAD as f32) / WIRE_GAP as f32);
    w.max(0).min(qubits - 1)
}

pub fn snap_col(x: f32) -> i32 {
    round_i32((x - ORIGIN_X as f32) / COL_GAP as f32).max(0)
}

pub fn wire_span(wires: &[i32]) -> Vec<i32> {
    let lo = *wires.iter().min().unwrap();
    let hi = *wires.iter().max().unwrap();
    (lo..=hi).collect()
}

/// Wires occupied by a CX/CCX gate, or `None` for other gates.
pub fn cn_wires(gate: &Gate) -> Option<Vec<i32>> {
    match gate.kind {
        GateKind::Cx => Some(vec![gate.wire, gate.target.unwrap_or(gate.wire)]),
        GateKind::Ccx => Some(vec![
            gate.wire,
            gate.control.unwrap_or(gate.wire),
            gate.target.unwrap_or(gate.wire),
        ]),
        _ => None,
    }
}

pub fn gate_wires(gate: &Gate) -> Vec<i32> {
    if gate.kind.is_cx() || gate.kind.is_ccx() {
        let ws = cn_wires(gate).unwrap_or_default();
        if !ws.is_empty() {
            wire_span(&ws)
        } else {
            vec![gate.wire]
        }
    } else if !gate.wires.is_empty() {
        gate.wires.clone()
    } else {
        vec![gate.wire]
    }
}

#[derive(Clone, Copy, PartialEq, Debug)]
pub struct Span {
    pub above: i32,
    pub below: i32,
    pub span: i32,
}

pub fn gate_span(gate: &Gate) -> Span {
    let wires = gate_wires(gate);
    let above = *wires.iter().min().unwrap();
    let below = *wires.iter().max().unwrap();
    Span {
        above,
        below,
        span: wire_y(below) - wire_y(above),
    }
}

pub fn blocks_cell(gate: &Gate, wire: i32, col: i32) -> bool {
    gate.col == col && gate_wires(gate).contains(&wire)
}

pub fn blocked(gates: &[Gate], wire: i32, col: i32) -> bool {
    gates.iter().any(|g| blocks_cell(g, wire, col))
}

pub fn affected_wires(gate: &Gate) -> Vec<i32> {
    gate_wires(gate)
}

pub fn free_col(gates: &[Gate], col: i32, wires: &[i32]) -> bool {
    wires.iter().all(|w| !blocked(gates, *w, col))
}

pub fn free_wire_span(gates: &[Gate], col: i32, control: i32, wire: i32) -> bool {
    free_col(gates, col, &wire_span(&[control, wire]))
}

pub fn nearest_free_col(gates: &[Gate], col: i32, wires: &[i32]) -> i32 {
    if free_col(gates, col, wires) {
        return col;
    }
    for d in 1..64 {
        for candidate in [col + d, col - d] {
            if candidate >= 0 && free_col(gates, candidate, wires) {
                return candidate;
            }
        }
    }
    col
}

pub fn cx_target(control: i32, qubits: i32) -> i32 {
    if control < qubits - 1 {
        control + 1
    } else {
        control - 1
    }
}

fn nearest_unoccupied_wire(wire: i32, qubits: i32, taken: &[i32]) -> i32 {
    for d in 1..qubits {
        for candidate in [wire + d, wire - d] {
            if candidate >= 0 && candidate < qubits && !taken.contains(&candidate) {
                return candidate;
            }
        }
    }
    wire
}

pub fn ccx_wires(wire: i32, qubits: i32) -> (i32, i32) {
    let control = nearest_unoccupied_wire(wire, qubits, &[wire]);
    let target = nearest_unoccupied_wire(wire, qubits, &[wire, control]);
    (control, target)
}

/// Build the gate that would be placed at pixel `(x, y)`, or `None` when the
/// placement is not permitted (CCX with fewer than three qubits).
pub fn target_cell(state: &State, kind: GateKind, x: f32, y: f32) -> Option<Gate> {
    if kind == GateKind::Ccx && state.qubits < 3 {
        return None;
    }
    let wire = snap_wire(y, state.qubits);
    let ccx = if kind == GateKind::Ccx {
        Some(ccx_wires(wire, state.qubits))
    } else {
        None
    };
    let target = match kind {
        GateKind::Cx => Some(cx_target(wire, state.qubits)),
        GateKind::Ccx => Some(ccx.unwrap().1),
        _ => None,
    };
    let mut candidate = Gate {
        id: 0,
        kind,
        col: 0,
        wire,
        target,
        control: None,
        wires: Vec::new(),
        angle: kind.default_angle().map(|s| s.to_string()),
        name: None,
    };
    if kind == GateKind::Ccx {
        candidate.control = Some(ccx.unwrap().0);
    }
    if kind == GateKind::Barrier {
        candidate.wires = (0..state.qubits).collect();
    }
    let col = nearest_free_col(&state.gates, snap_col(x), &affected_wires(&candidate));
    candidate.col = col;
    Some(candidate)
}

#[derive(Clone, Copy, PartialEq, Debug)]
pub struct CxGeometry {
    pub above: i32,
    pub below: i32,
    pub span: i32,
    pub control_at_top: bool,
}

pub fn cx_geometry(control: i32, target: i32) -> CxGeometry {
    let above = control.min(target);
    let below = control.max(target);
    CxGeometry {
        above,
        below,
        span: wire_y(below) - wire_y(above),
        control_at_top: control < target,
    }
}

/// Find a free target wire near `desired`, avoiding `controls` and occupied
/// columns.
pub fn nearest_free_wire(
    gates: &[Gate],
    col: i32,
    desired: i32,
    controls: Option<&[i32]>,
    qubits: i32,
) -> i32 {
    let control_set: Vec<i32> = controls.map(|c| c.to_vec()).unwrap_or_default();
    let span = |target: i32| {
        let mut ws = control_set.clone();
        ws.push(target);
        wire_span(&ws)
    };
    if !control_set.contains(&desired) && free_col(gates, col, &span(desired)) {
        return desired;
    }
    for d in 1..qubits {
        for candidate in [desired + d, desired - d] {
            if candidate >= 0
                && candidate < qubits
                && !control_set.contains(&candidate)
                && free_col(gates, col, &span(candidate))
            {
                return candidate;
            }
        }
    }
    desired
}

pub fn add_gate_to_state(current: &State, mut gate: Gate) -> State {
    let mut next = current.clone();
    gate.id = current.next_id;
    next.next_id = current.next_id + 1;
    next.gates.push(gate);
    next
}

pub fn remove_gate_from_state(current: &State, id: u64) -> State {
    let mut next = current.clone();
    next.gates.retain(|g| g.id != id);
    next
}

pub fn set_qubits_in_state(current: &State, raw: i32) -> State {
    let qubits = raw.clamp(MIN_QUBITS, MAX_QUBITS);
    let mut next = current.clone();
    next.qubits = qubits;
    next.gates
        .retain(|g| gate_wires(g).iter().all(|w| *w < qubits));
    next
}

pub fn is_measure_gate(gate: &Gate) -> bool {
    gate.kind == GateKind::Measure
}

pub fn ensure_measure_cbits(current: &State) -> State {
    if current.cbits == 0 && current.gates.iter().any(is_measure_gate) {
        let mut next = current.clone();
        next.cbits = current.qubits.clamp(MIN_CBITS, MAX_CBITS);
        next
    } else {
        current.clone()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn g(kind: GateKind, wire: i32, col: i32) -> Gate {
        Gate::new(kind, wire, col)
    }

    #[test]
    fn geometry() {
        assert_eq!(TOP_PAD + 2 * WIRE_GAP, wire_y(2));
        assert_eq!(ORIGIN_X + 3 * COL_GAP, col_x(3));
        assert_eq!(col_x(3) - GATE_SIZE / 2, gate_left(3));
        assert_eq!(wire_y(1) - GATE_SIZE / 2, gate_top(1));
    }

    #[test]
    fn cwire_y_test() {
        assert_eq!(wire_y(2), cwire_y(0, 2));
        assert_eq!(wire_y(3), cwire_y(1, 2));
    }

    #[test]
    fn snap_wire_test() {
        assert_eq!(0, snap_wire(0.0, 4));
        assert_eq!(0, snap_wire(-500.0, 4));
        assert_eq!(1, snap_wire((TOP_PAD + WIRE_GAP) as f32, 4));
        assert_eq!(3, snap_wire(100000.0, 4));
    }

    #[test]
    fn snap_col_test() {
        assert_eq!(0, snap_col(0.0));
        assert_eq!(0, snap_col(-50.0));
        assert_eq!(2, snap_col((ORIGIN_X + 2 * COL_GAP) as f32));
    }

    #[test]
    fn gate_wires_test() {
        assert_eq!(vec![1], gate_wires(&g(GateKind::X, 1, 0)));
        assert_eq!(
            vec![1, 2, 3],
            gate_wires(&Gate {
                target: Some(3),
                ..g(GateKind::Cx, 1, 0)
            })
        );
        assert_eq!(
            vec![0, 1, 2],
            gate_wires(&Gate {
                control: Some(1),
                target: Some(2),
                ..g(GateKind::Ccx, 0, 0)
            })
        );
        assert_eq!(
            vec![1, 2, 3],
            gate_wires(&Gate {
                control: Some(1),
                target: Some(2),
                ..g(GateKind::Ccx, 3, 0)
            })
        );
        assert_eq!(
            vec![2, 4],
            gate_wires(&Gate {
                wires: vec![2, 4],
                ..g(GateKind::Custom, 0, 0)
            })
        );
        assert_eq!(
            vec![0, 1, 2],
            gate_wires(&Gate {
                wires: vec![0, 1, 2],
                ..g(GateKind::Barrier, 0, 0)
            })
        );
        assert_eq!(vec![2], gate_wires(&g(GateKind::Measure, 2, 0)));
    }

    #[test]
    fn gate_span_test() {
        assert_eq!(
            Span {
                above: 0,
                below: 2,
                span: 2 * WIRE_GAP
            },
            gate_span(&Gate {
                control: Some(1),
                target: Some(2),
                ..g(GateKind::Ccx, 0, 0)
            })
        );
        assert_eq!(
            Span {
                above: 1,
                below: 3,
                span: 2 * WIRE_GAP
            },
            gate_span(&Gate {
                control: Some(1),
                target: Some(2),
                ..g(GateKind::Ccx, 3, 0)
            })
        );
    }

    #[test]
    fn blocks_cell_test() {
        let x = g(GateKind::X, 1, 2);
        assert!(blocks_cell(&x, 1, 2));
        assert!(!blocks_cell(&x, 2, 2));
        assert!(!blocks_cell(&x, 1, 3));

        let cx = Gate {
            target: Some(3),
            ..g(GateKind::Cx, 0, 1)
        };
        assert!(blocks_cell(&cx, 0, 1));
        assert!(blocks_cell(&cx, 1, 1));
        assert!(blocks_cell(&cx, 3, 1));
        assert!(!blocks_cell(&cx, 4, 1));

        let ccx = Gate {
            control: Some(2),
            target: Some(3),
            ..g(GateKind::Ccx, 0, 1)
        };
        assert!(blocks_cell(&ccx, 0, 1));
        assert!(blocks_cell(&ccx, 3, 1));
        assert!(!blocks_cell(&ccx, 4, 1));

        let custom = Gate {
            wires: vec![1, 3],
            ..g(GateKind::Custom, 0, 0)
        };
        assert!(blocks_cell(&custom, 3, 0));
        assert!(!blocks_cell(&custom, 2, 0));

        let barrier = Gate {
            wires: vec![0, 1, 2],
            ..g(GateKind::Barrier, 0, 2)
        };
        assert!(blocks_cell(&barrier, 1, 2));
        assert!(!blocks_cell(&barrier, 3, 2));
    }

    #[test]
    fn free_col_and_nearest() {
        let gates = vec![g(GateKind::X, 0, 0)];
        assert!(free_col(&gates, 1, &[0]));
        assert!(!free_col(&gates, 0, &[0]));
        assert_eq!(5, nearest_free_col(&[], 5, &[0]));
        let gates = vec![g(GateKind::X, 0, 3)];
        assert_eq!(4, nearest_free_col(&gates, 3, &[0]));
        assert_eq!(3, nearest_free_col(&gates, 3, &[1]));
    }

    #[test]
    fn cx_target_test() {
        assert_eq!(1, cx_target(0, 2));
        assert_eq!(0, cx_target(1, 2));
        assert_eq!(1, cx_target(2, 3));
    }

    #[test]
    fn target_cell_test() {
        let st = State {
            qubits: 4,
            ..Default::default()
        };
        let cell = target_cell(&st, GateKind::H, ORIGIN_X as f32, TOP_PAD as f32).unwrap();
        assert_eq!(g(GateKind::H, 0, 0), cell);

        let cell = target_cell(&st, GateKind::Cx, ORIGIN_X as f32, TOP_PAD as f32).unwrap();
        assert_eq!(Some(1), cell.target);

        let cell = target_cell(&st, GateKind::Ccx, ORIGIN_X as f32, TOP_PAD as f32).unwrap();
        assert_eq!(0, cell.wire);
        assert_eq!(Some(1), cell.control);
        assert_eq!(Some(2), cell.target);

        assert!(target_cell(
            &State {
                qubits: 2,
                ..Default::default()
            },
            GateKind::Ccx,
            ORIGIN_X as f32,
            TOP_PAD as f32
        )
        .is_none());

        let st = State {
            qubits: 4,
            gates: vec![g(GateKind::X, 0, 0)],
            ..Default::default()
        };
        let cell = target_cell(&st, GateKind::H, ORIGIN_X as f32, TOP_PAD as f32).unwrap();
        assert_eq!(1, cell.col);

        let cell = target_cell(
            &State {
                qubits: 3,
                ..Default::default()
            },
            GateKind::Barrier,
            ORIGIN_X as f32,
            TOP_PAD as f32,
        )
        .unwrap();
        assert_eq!(vec![0, 1, 2], cell.wires);
    }

    #[test]
    fn cx_geometry_test() {
        assert_eq!(
            CxGeometry {
                above: 1,
                below: 3,
                span: wire_y(3) - wire_y(1),
                control_at_top: true
            },
            cx_geometry(1, 3)
        );
        assert!(cx_geometry(0, 2).control_at_top);
        assert!(!cx_geometry(2, 0).control_at_top);
    }

    #[test]
    fn nearest_free_wire_test() {
        assert_eq!(2, nearest_free_wire(&[], 0, 2, Some(&[0]), 4));
        let gates = vec![g(GateKind::X, 2, 0)];
        assert_eq!(1, nearest_free_wire(&gates, 0, 2, Some(&[0]), 4));
        assert_eq!(3, nearest_free_wire(&[], 0, 3, Some(&[0, 1]), 4));
        assert_eq!(2, nearest_free_wire(&[], 0, 1, Some(&[0, 1]), 4));
    }

    #[test]
    fn canvas_size_test() {
        let (w, h) = canvas_size(&State {
            qubits: 4,
            ..Default::default()
        });
        assert_eq!(MIN_CANVAS_WIDTH, w);
        assert_eq!(TOP_PAD + 4 * WIRE_GAP + BOTTOM_PAD, h);

        let (w1, _) = canvas_size(&State {
            qubits: 2,
            cbits: 0,
            ..Default::default()
        });
        let (w2, _) = canvas_size(&State {
            qubits: 2,
            cbits: 3,
            ..Default::default()
        });
        let (_, h1) = canvas_size(&State {
            qubits: 2,
            cbits: 0,
            ..Default::default()
        });
        let (_, h2) = canvas_size(&State {
            qubits: 2,
            cbits: 9,
            ..Default::default()
        });
        assert_eq!(w1, w2);
        assert_eq!(WIRE_GAP, h2 - h1);
    }

    #[test]
    fn state_helpers_test() {
        let st = add_gate_to_state(&State::default(), g(GateKind::X, 0, 0));
        assert_eq!(1, st.next_id);
        assert_eq!(0, st.gates[0].id);
        assert!(remove_gate_from_state(&st, 0).gates.is_empty());
    }

    #[test]
    fn set_qubits_in_state_test() {
        let st = State {
            qubits: 4,
            gates: vec![g(GateKind::X, 3, 0), g(GateKind::Cx, 3, 1)],
            ..Default::default()
        };
        assert_eq!(2, set_qubits_in_state(&st, 2).qubits);
        assert!(set_qubits_in_state(&st, 2).gates.is_empty());
        assert_eq!(MIN_QUBITS, set_qubits_in_state(&State::default(), 0).qubits);
        assert_eq!(
            MAX_QUBITS,
            set_qubits_in_state(&State::default(), 99).qubits
        );
    }

    #[test]
    fn ensure_measure_cbits_test() {
        let st = State {
            qubits: 3,
            cbits: 0,
            gates: vec![g(GateKind::Measure, 0, 0)],
            ..Default::default()
        };
        assert_eq!(3, ensure_measure_cbits(&st).cbits);

        let st = State {
            qubits: 3,
            cbits: 0,
            gates: vec![g(GateKind::H, 0, 0)],
            ..Default::default()
        };
        assert_eq!(0, ensure_measure_cbits(&st).cbits);

        let st = State {
            qubits: 3,
            cbits: 2,
            gates: vec![g(GateKind::Measure, 0, 0)],
            ..Default::default()
        };
        assert_eq!(2, ensure_measure_cbits(&st).cbits);

        let st = State {
            qubits: 99,
            cbits: 0,
            gates: vec![g(GateKind::Measure, 0, 0)],
            ..Default::default()
        };
        assert_eq!(MAX_CBITS, ensure_measure_cbits(&st).cbits);
    }

    #[test]
    fn undo_redo() {
        let mut stack = UndoStack::new();
        let mut state = State::default();
        stack.save(&state);
        state.qubits = 2;
        stack.save(&state);
        state.qubits = 3;
        assert!(stack.undo(&mut state));
        assert_eq!(2, state.qubits);
        assert!(stack.undo(&mut state));
        assert_eq!(4, state.qubits);
        assert!(stack.redo(&mut state));
        assert_eq!(2, state.qubits);
    }

    #[test]
    fn blocked_test() {
        let gates = vec![g(GateKind::X, 0, 0)];
        assert!(blocked(&gates, 0, 0));
        assert!(!blocked(&gates, 0, 1));
    }

    #[test]
    fn affected_wires_test() {
        assert_eq!(vec![1], affected_wires(&g(GateKind::H, 1, 0)));
        assert_eq!(
            vec![1, 2, 3],
            affected_wires(&Gate {
                target: Some(1),
                ..g(GateKind::Cx, 3, 0)
            })
        );
        assert_eq!(
            vec![0, 1, 2],
            affected_wires(&Gate {
                control: Some(0),
                target: Some(1),
                ..g(GateKind::Ccx, 2, 0)
            })
        );
    }

    #[test]
    fn free_wire_span_test() {
        let gates = vec![g(GateKind::X, 1, 0)];
        assert!(!free_wire_span(&gates, 0, 0, 2));
        assert!(free_wire_span(&gates, 1, 0, 2));
    }

    #[test]
    fn target_cell_measure_test() {
        let st = State {
            qubits: 4,
            ..Default::default()
        };
        let cell = target_cell(&st, GateKind::Measure, ORIGIN_X as f32, TOP_PAD as f32).unwrap();
        assert_eq!(GateKind::Measure, cell.kind);
        assert_eq!(0, cell.wire);
        assert_eq!(None, cell.target);
    }
}
