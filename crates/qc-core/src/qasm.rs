//! OpenQASM 3 generation and parsing.
//!
//! A faithful port of `qasm.cljs`: generation emits canonical statements, and
//! parsing is line-oriented and column-schedules gates so that disjoint gates
//! may share a column.

use std::collections::BTreeMap;
use std::sync::LazyLock;

use regex::Regex;

use crate::model::{
    self, ensure_measure_cbits, Gate, GateDef, GateKind, State, MAX_CBITS, MAX_QUBITS, MIN_CBITS,
    MIN_QUBITS,
};

/// Error returned when a program cannot be represented as a circuit.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ParseError(pub String);

impl std::fmt::Display for ParseError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{}", self.0)
    }
}

impl std::error::Error for ParseError {}

/// A successfully parsed program.
#[derive(Debug, Clone, PartialEq)]
pub struct Parsed {
    pub qubits: i32,
    pub cbits: i32,
    pub gates: Vec<Gate>,
    pub defs: BTreeMap<String, GateDef>,
}

static QUBIT_RE: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r"^\s*qubit\s*\[\s*(\d+)\s*\]\s*[A-Za-z_][A-Za-z0-9_]*\s*;?\s*$").unwrap()
});
static BIT_RE: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r"^\s*bit\s*\[\s*(\d+)\s*\]\s*[A-Za-z_][A-Za-z0-9_]*\s*;?\s*$").unwrap()
});
static GATE_RE: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(
        r"^\s*([A-Za-z][A-Za-z0-9_]*)\s*(?:\(\s*([^)]*)\s*\))?\s*[A-Za-z_][A-Za-z0-9_]*\s*\[\s*(\d+)\s*\]\s*(?:,\s*[A-Za-z_][A-Za-z0-9_]*\s*\[\s*(\d+)\s*\])?\s*;?\s*$",
    )
    .unwrap()
});
static GATE_DEF_RE: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(
        r"^\s*gate\s+([A-Za-z_][A-Za-z0-9_]*)\s*(?:\([^)]*\))?\s*([A-Za-z_,\s]*?)\s*(\{.*)?$",
    )
    .unwrap()
});
static CALL_RE: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(
        r"^\s*([A-Za-z_][A-Za-z0-9_]*)\s*(?:\([^)]*\))?\s*((?:[A-Za-z_][A-Za-z0-9_]*\s*\[\s*\d+\s*\]\s*,?\s*)+)\s*;?\s*$",
    )
    .unwrap()
});
static MEASURE_RE: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(
        r"^\s*measure\s+[A-Za-z_][A-Za-z0-9_]*\s*\[\s*(\d+)\s*\]\s*(?:->\s*[A-Za-z_][A-Za-z0-9_]*\s*\[\s*\d+\s*\]\s*)?;?\s*$",
    )
    .unwrap()
});
static BARRIER_RE: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"^\s*barrier\s*([^;]*);?\s*$").unwrap());
static INDEX_RE: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"\[\s*(\d+)\s*\]").unwrap());

fn header_line(line: &str) -> bool {
    line.starts_with("OPENQASM") || line.starts_with("include")
}

fn strip_comment(raw: &str) -> String {
    match raw.find("//") {
        Some(idx) => raw[..idx].trim().to_string(),
        None => raw.trim().to_string(),
    }
}

fn wire_indices(text: &str) -> Vec<i32> {
    INDEX_RE
        .captures_iter(text)
        .map(|c| c[1].parse::<i32>().unwrap())
        .collect()
}

fn count_arity(args: Option<&str>) -> usize {
    let trimmed = args.unwrap_or("").trim();
    if trimmed.is_empty() {
        0
    } else {
        trimmed.split(',').filter(|s| !s.trim().is_empty()).count()
    }
}

fn brace_delta(line: &str) -> i32 {
    let open = line.chars().filter(|c| *c == '{').count() as i32;
    let close = line.chars().filter(|c| *c == '}').count() as i32;
    open - close
}

/// Render a single gate as an OpenQASM statement.
pub fn gate_qasm(gate: &Gate) -> String {
    match gate.kind {
        GateKind::Cx => format!(
            "cx q[{}], q[{}];",
            gate.wire,
            gate.target.unwrap_or(gate.wire)
        ),
        GateKind::Ccx => format!(
            "ccx q[{}], q[{}], q[{}];",
            gate.wire,
            gate.control.unwrap_or(gate.wire),
            gate.target.unwrap_or(gate.wire)
        ),
        GateKind::Measure => format!("measure q[{}];", gate.wire),
        GateKind::Custom => {
            let operands: Vec<String> = gate.wires.iter().map(|w| format!("q[{}]", w)).collect();
            format!(
                "{} {};",
                gate.name.as_deref().unwrap_or(""),
                operands.join(", ")
            )
        }
        GateKind::Barrier => {
            if gate.wires.is_empty() {
                "barrier;".to_string()
            } else {
                let operands: Vec<String> =
                    gate.wires.iter().map(|w| format!("q[{}]", w)).collect();
                format!("barrier {};", operands.join(", "))
            }
        }
        _ => {
            let qname = gate.kind.qasm_name();
            let angle = gate
                .angle
                .clone()
                .or_else(|| gate.kind.default_angle().map(|s| s.to_string()));
            match angle {
                Some(a) => format!("{}({}) q[{}];", qname, a, gate.wire),
                None => format!("{} q[{}];", qname, gate.wire),
            }
        }
    }
}

/// Render the whole circuit as an OpenQASM 3 program.
pub fn generate_qasm(state: &State) -> String {
    let cbits = state.cbits;
    let mut def_lines: Vec<String> = Vec::new();
    for def in state.defs.values() {
        def_lines.extend(def.lines.iter().cloned());
    }
    let mut sorted: Vec<&Gate> = state.gates.iter().collect();
    sorted.sort_by_key(|g| (g.col, g.wire, g.id));
    let body: Vec<String> = sorted.iter().map(|g| gate_qasm(g)).collect();

    let mut header: Vec<String> = vec![
        "OPENQASM 3;".to_string(),
        "include \"stdgates.inc\";".to_string(),
        String::new(),
    ];
    if !def_lines.is_empty() {
        header.extend(def_lines);
        header.push(String::new());
    }
    header.push(format!("qubit[{}] q;", state.qubits));
    if cbits > 0 {
        header.push(format!("bit[{}] c;", cbits));
    }
    header.extend(body);
    let mut out = header.join("\n");
    out.push('\n');
    out
}

fn assign_col(nextcol: &BTreeMap<i32, i32>, affected: &[i32]) -> (i32, BTreeMap<i32, i32>) {
    let col = affected
        .iter()
        .map(|w| nextcol.get(w).copied().unwrap_or(0))
        .max()
        .unwrap_or(0)
        .max(0);
    let mut nc = nextcol.clone();
    for w in affected {
        nc.insert(*w, col + 1);
    }
    (col, nc)
}

/// Parse an OpenQASM 3 program into a circuit, or return a [`ParseError`].
pub fn parse_qasm(text: &str) -> Result<Parsed, ParseError> {
    let lines: Vec<&str> = text.split('\n').collect();
    let mut qubits: Option<i32> = None;
    let mut cbits: i32 = 0;
    let mut nextcol: BTreeMap<i32, i32> = BTreeMap::new();
    let mut id: u64 = 0;
    let mut gates: Vec<Gate> = Vec::new();
    let mut defs: BTreeMap<String, GateDef> = BTreeMap::new();
    let mut def_name: Option<String> = None;
    let mut def_arity: usize = 0;
    let mut def_lines: Vec<String> = Vec::new();
    let mut def_depth: Option<i32> = None;

    let fail = || ParseError("program is not a drawable circuit".to_string());

    let mut i = 0;
    while i < lines.len() {
        let raw = lines[i];
        i += 1;

        if let Some(name) = def_name.clone() {
            let raw_line = strip_comment(raw);
            let has_brace = raw_line.contains('{');
            let depth = match def_depth {
                None => {
                    if has_brace {
                        Some(brace_delta(&raw_line))
                    } else {
                        None
                    }
                }
                Some(d) => Some(d + brace_delta(&raw_line)),
            };
            def_lines.push(raw.to_string());
            if let Some(d) = depth {
                if d <= 0 {
                    defs.insert(
                        name,
                        GateDef {
                            arity: def_arity,
                            lines: def_lines.clone(),
                        },
                    );
                    def_name = None;
                    def_arity = 0;
                    def_lines = Vec::new();
                    def_depth = None;
                    continue;
                }
            }
            def_depth = depth;
            continue;
        }

        let line = strip_comment(raw);
        if line.is_empty() || header_line(&line) {
            continue;
        }

        if let Some(caps) = QUBIT_RE.captures(&line) {
            qubits = Some(caps[1].parse::<i32>().unwrap());
            continue;
        }

        if let Some(caps) = BIT_RE.captures(&line) {
            let n = caps[1].parse::<i32>().unwrap();
            if (MIN_CBITS..=MAX_CBITS).contains(&n) {
                cbits = n;
                continue;
            }
            return Err(fail());
        }

        if let Some(caps) = GATE_DEF_RE.captures(&line) {
            let dname = caps[1].to_string();
            let dargs = caps.get(2).map(|m| m.as_str());
            let has_brace = line.contains('{');
            let start_depth = if has_brace {
                Some(brace_delta(&line))
            } else {
                None
            };
            if let Some(d) = start_depth {
                if d <= 0 {
                    defs.insert(
                        dname,
                        GateDef {
                            arity: count_arity(dargs),
                            lines: vec![raw.to_string()],
                        },
                    );
                    continue;
                }
            }
            def_name = Some(dname);
            def_arity = count_arity(dargs);
            def_lines = vec![raw.to_string()];
            def_depth = start_depth;
            continue;
        }

        if let Some(caps) = MEASURE_RE.captures(&line) {
            let wire = caps[1].parse::<i32>().unwrap();
            if let Some(q) = qubits {
                if (0..q).contains(&wire) {
                    let candidate = Gate {
                        kind: GateKind::Measure,
                        wire,
                        ..Gate::new(GateKind::Measure, wire, 0)
                    };
                    let (col, nc) = assign_col(&nextcol, &model::affected_wires(&candidate));
                    nextcol = nc;
                    gates.push(Gate {
                        id,
                        col,
                        ..candidate
                    });
                    id += 1;
                    continue;
                }
            }
            return Err(fail());
        }

        if let Some(caps) = BARRIER_RE.captures(&line) {
            let args = caps.get(1).map(|m| m.as_str()).unwrap_or("");
            let mut ws = wire_indices(args);
            ws.sort_unstable();
            ws.dedup();
            let ws: Vec<i32> = if !ws.is_empty() {
                ws
            } else {
                match qubits {
                    Some(q) => (0..q).collect(),
                    None => Vec::new(),
                }
            };
            if let Some(q) = qubits {
                if !ws.is_empty() && ws.iter().all(|w| (0..q).contains(w)) {
                    let candidate = Gate {
                        kind: GateKind::Barrier,
                        wire: ws[0],
                        wires: ws.clone(),
                        ..Gate::new(GateKind::Barrier, ws[0], 0)
                    };
                    let (col, nc) = assign_col(&nextcol, &model::affected_wires(&candidate));
                    nextcol = nc;
                    gates.push(Gate {
                        id,
                        col,
                        ..candidate
                    });
                    id += 1;
                    continue;
                }
            }
            return Err(fail());
        }

        let gate_caps = GATE_RE.captures(&line);
        let call_caps = CALL_RE.captures(&line);
        let cname = gate_caps.as_ref().map(|c| c[1].to_string());
        let arg = gate_caps
            .as_ref()
            .and_then(|c| c.get(2).map(|m| m.as_str().to_string()));
        let i1 = gate_caps
            .as_ref()
            .and_then(|c| c.get(3).map(|m| m.as_str().to_string()));
        let i2 = gate_caps
            .as_ref()
            .and_then(|c| c.get(4).map(|m| m.as_str().to_string()));
        let call_name = call_caps.as_ref().map(|c| c[1].to_string());
        let call_args = call_caps
            .as_ref()
            .and_then(|c| c.get(2).map(|m| m.as_str().to_string()));

        // Custom gate call (name matches a known definition).
        if let Some(cn) = &call_name {
            if defs.contains_key(cn) {
                let wires = wire_indices(call_args.as_deref().unwrap_or(""));
                let valid = qubits.is_some()
                    && !wires.is_empty()
                    && wires.iter().all(|w| (0..qubits.unwrap()).contains(w));
                if !valid {
                    return Err(fail());
                }
                let candidate = Gate {
                    kind: GateKind::Custom,
                    name: Some(cn.clone()),
                    wire: wires[0],
                    wires,
                    ..Gate::new(GateKind::Custom, 0, 0)
                };
                let (col, nc) = assign_col(&nextcol, &model::affected_wires(&candidate));
                nextcol = nc;
                gates.push(Gate {
                    id,
                    col,
                    ..candidate
                });
                id += 1;
                continue;
            }
        }

        // CCX (call with three operands).
        if let Some(cn) = &call_name {
            if GateKind::from_qasm_name(cn) == Some(GateKind::Ccx) {
                let wires = wire_indices(call_args.as_deref().unwrap_or(""));
                let mut distinct = wires.clone();
                distinct.sort_unstable();
                distinct.dedup();
                let valid = qubits.is_some()
                    && wires.len() == 3
                    && distinct.len() == 3
                    && wires.iter().all(|w| (0..qubits.unwrap()).contains(w));
                if !valid {
                    return Err(fail());
                }
                let candidate = Gate {
                    kind: GateKind::Ccx,
                    wire: wires[0],
                    control: Some(wires[1]),
                    target: Some(wires[2]),
                    ..Gate::new(GateKind::Ccx, wires[0], 0)
                };
                let (col, nc) = assign_col(&nextcol, &model::affected_wires(&candidate));
                nextcol = nc;
                gates.push(Gate {
                    id,
                    col,
                    ..candidate
                });
                id += 1;
                continue;
            }
        }

        // CX (two operands on the gate form).
        if let Some(cn) = &cname {
            if GateKind::from_qasm_name(cn) == Some(GateKind::Cx) {
                let wire = i1.as_ref().map(|s| s.parse::<i32>().unwrap());
                let target = i2.as_ref().map(|s| s.parse::<i32>().unwrap());
                let valid = target.is_some()
                    && target != wire
                    && qubits.is_some()
                    && (0..qubits.unwrap()).contains(&wire.unwrap())
                    && (0..qubits.unwrap()).contains(&target.unwrap());
                if !valid {
                    return Err(fail());
                }
                let candidate = Gate {
                    kind: GateKind::Cx,
                    wire: wire.unwrap(),
                    target,
                    ..Gate::new(GateKind::Cx, wire.unwrap(), 0)
                };
                let (col, nc) = assign_col(&nextcol, &model::affected_wires(&candidate));
                nextcol = nc;
                gates.push(Gate {
                    id,
                    col,
                    ..candidate
                });
                id += 1;
                continue;
            }
        }

        // Single-qubit gate (and error cases for extra operands).
        if let Some(cn) = &cname {
            if let Some(kind) = GateKind::from_qasm_name(cn) {
                let wire = i1.as_ref().map(|s| s.parse::<i32>().unwrap());
                if i2.is_some() {
                    return Err(fail());
                }
                let valid = qubits.is_some()
                    && wire.is_some()
                    && (0..qubits.unwrap()).contains(&wire.unwrap());
                if !valid {
                    return Err(fail());
                }
                let angle = arg
                    .as_ref()
                    .filter(|a| !a.trim().is_empty())
                    .map(|a| a.trim().to_string());
                let candidate = Gate {
                    kind,
                    wire: wire.unwrap(),
                    angle,
                    ..Gate::new(kind, wire.unwrap(), 0)
                };
                let (col, nc) = assign_col(&nextcol, &model::affected_wires(&candidate));
                nextcol = nc;
                gates.push(Gate {
                    id,
                    col,
                    ..candidate
                });
                id += 1;
                continue;
            }
        }

        return Err(fail());
    }

    let result = ensure_measure_cbits(&State {
        qubits: qubits.unwrap_or(0),
        cbits,
        next_id: id,
        gates,
        defs,
    });

    match qubits {
        Some(q)
            if (MIN_QUBITS..=MAX_QUBITS).contains(&q)
                && (MIN_CBITS..=MAX_CBITS).contains(&result.cbits) =>
        {
            Ok(Parsed {
                qubits: q,
                cbits: result.cbits,
                gates: result.gates,
                defs: result.defs,
            })
        }
        _ => Err(fail()),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use regex::Regex;

    fn has_bit_decl(text: &str) -> bool {
        Regex::new(r"\bbit\[").unwrap().is_match(text)
    }

    #[test]
    fn gate_qasm_test() {
        assert_eq!("x q[0];", gate_qasm(&Gate::new(GateKind::X, 0, 0)));
        assert_eq!(
            "rx(pi/2) q[2];",
            gate_qasm(&Gate {
                angle: Some("pi/2".into()),
                ..Gate::new(GateKind::Rx, 2, 0)
            })
        );
        assert_eq!(
            "rz(pi/4) q[1];",
            gate_qasm(&Gate {
                angle: Some("pi/4".into()),
                ..Gate::new(GateKind::Rz, 1, 0)
            })
        );
        assert_eq!("rz(pi/2) q[1];", gate_qasm(&Gate::new(GateKind::Rz, 1, 0)));
        assert_eq!(
            "cx q[0], q[2];",
            gate_qasm(&Gate {
                target: Some(2),
                ..Gate::new(GateKind::Cx, 0, 0)
            })
        );
        assert_eq!(
            "ccx q[0], q[1], q[2];",
            gate_qasm(&Gate {
                control: Some(1),
                target: Some(2),
                ..Gate::new(GateKind::Ccx, 0, 0)
            })
        );
        assert_eq!(
            "mygate q[0], q[2];",
            gate_qasm(&Gate {
                name: Some("mygate".into()),
                wires: vec![0, 2],
                ..Gate::new(GateKind::Custom, 0, 0)
            })
        );
        assert_eq!(
            "barrier q[0], q[1];",
            gate_qasm(&Gate {
                wires: vec![0, 1],
                ..Gate::new(GateKind::Barrier, 0, 0)
            })
        );
        assert_eq!(
            "barrier;",
            gate_qasm(&Gate {
                wires: vec![],
                ..Gate::new(GateKind::Barrier, 0, 0)
            })
        );
        assert_eq!("id q[3];", gate_qasm(&Gate::new(GateKind::Id, 3, 0)));
    }

    #[test]
    fn generate_qasm_test() {
        let text = generate_qasm(&State {
            qubits: 2,
            gates: vec![
                Gate {
                    id: 0,
                    ..Gate::new(GateKind::H, 0, 0)
                },
                Gate {
                    id: 1,
                    target: Some(1),
                    ..Gate::new(GateKind::Cx, 0, 1)
                },
            ],
            ..Default::default()
        });
        assert!(text.contains("OPENQASM 3;"));
        assert!(text.contains("include \"stdgates.inc\";"));
        assert!(text.contains("qubit[2] q;"));
        assert!(text.contains("h q[0];"));
        assert!(text.contains("cx q[0], q[1];"));
        assert!(!has_bit_decl(&text));
    }

    #[test]
    fn generate_qasm_cbits_test() {
        let text = generate_qasm(&State {
            qubits: 2,
            cbits: 3,
            ..Default::default()
        });
        assert!(text.contains("bit[3] c;"));
        let text = generate_qasm(&State {
            qubits: 2,
            cbits: 0,
            ..Default::default()
        });
        assert!(!has_bit_decl(&text));
    }

    #[test]
    fn assign_col_test() {
        let empty: BTreeMap<i32, i32> = BTreeMap::new();
        assert_eq!((0, BTreeMap::from([(0, 1)])), assign_col(&empty, &[0]));
        let nc = BTreeMap::from([(0, 1), (1, 2)]);
        assert_eq!(
            (2, BTreeMap::from([(0, 3), (1, 3)])),
            assign_col(&nc, &[0, 1])
        );
        assert_eq!((0, BTreeMap::new()), assign_col(&empty, &[]));
    }

    #[test]
    fn parse_basic_test() {
        let res = parse_qasm(
            "OPENQASM 3;\ninclude \"stdgates.inc\";\nqubit[3] q;\nh q[0];\ncx q[0], q[2];\n",
        )
        .unwrap();
        assert_eq!(3, res.qubits);
        assert_eq!(0, res.cbits);
        assert_eq!(2, res.gates.len());
        assert_eq!(GateKind::H, res.gates[0].kind);
        assert_eq!(0, res.gates[0].wire);
        assert_eq!(GateKind::Cx, res.gates[1].kind);
        assert_eq!(Some(2), res.gates[1].target);
    }

    #[test]
    fn parse_ccx_test() {
        let res = parse_qasm("qubit[3] q;\nccx q[0], q[1], q[2];\n").unwrap();
        assert_eq!(GateKind::Ccx, res.gates[0].kind);
        assert_eq!(0, res.gates[0].wire);
        assert_eq!(Some(1), res.gates[0].control);
        assert_eq!(Some(2), res.gates[0].target);

        assert!(parse_qasm("qubit[3] q;\nccx q[0], q[1], q[1];").is_err());
        assert!(parse_qasm("qubit[2] q;\nccx q[0], q[1], q[2];").is_err());
        assert!(parse_qasm("qubit[3] q;\nccx q[0], q[1];").is_err());
    }

    #[test]
    fn parse_bit_declaration_test() {
        let res = parse_qasm("qubit[2] q;\nbit[2] c;\nmeasure q[0];").unwrap();
        assert_eq!(2, res.cbits);
        assert!(parse_qasm("qubit[2] q;\nbit[99] c;").is_err());
    }

    #[test]
    fn parse_measure_defaults_cbits_test() {
        let res = parse_qasm("qubit[2] q;\nmeasure q[0];").unwrap();
        assert_eq!(2, res.cbits);
        let res = parse_qasm("qubit[2] q;\nbit[1] c;\nmeasure q[0];").unwrap();
        assert_eq!(1, res.cbits);
    }

    #[test]
    fn parse_comments_and_angles_test() {
        let res = parse_qasm("qubit[2] q;\nrx(pi/4) q[1]; // rotate\n// full line\n").unwrap();
        assert_eq!(2, res.qubits);
        assert_eq!(Some("pi/4".to_string()), res.gates[0].angle);
    }

    #[test]
    fn parse_scheduling_test() {
        let res = parse_qasm("qubit[2] q;\nh q[0];\nh q[0];\nh q[1];").unwrap();
        let cols: Vec<i32> = res.gates.iter().map(|g| g.col).collect();
        assert_eq!(vec![0, 1, 0], cols);
    }

    #[test]
    fn parse_custom_gate_test() {
        let text = "qubit[2] q;\ngate mygate a, b { cx a, b; }\nmygate q[0], q[1];\n";
        let res = parse_qasm(text).unwrap();
        assert_eq!(2, res.defs["mygate"].arity);
        assert_eq!(GateKind::Custom, res.gates[0].kind);
        assert_eq!(Some("mygate".to_string()), res.gates[0].name);
        assert_eq!(vec![0, 1], res.gates[0].wires);
    }

    #[test]
    fn parse_def_brace_next_line_test() {
        let text = "qubit[2] q;\ngate cphase(theta) a, b\n{\n  U(0, 0, theta / 2) a;\n  CX a, b;\n}\ncphase(pi / 2) q[0], q[1];\n";
        let res = parse_qasm(text).unwrap();
        assert_eq!(2, res.defs["cphase"].arity);
        assert_eq!(GateKind::Custom, res.gates[0].kind);
        assert_eq!(vec![0, 1], res.gates[0].wires);
    }

    #[test]
    fn strip_comment_test() {
        assert_eq!("h q[0];", strip_comment("h q[0]; // comment"));
        assert_eq!("h q[0];", strip_comment("h q[0];"));
        assert_eq!("", strip_comment("// full line comment"));
    }

    #[test]
    fn parse_measure_test() {
        let res = parse_qasm("qubit[2] q;\nmeasure q[1];").unwrap();
        assert_eq!(GateKind::Measure, res.gates[0].kind);
        assert_eq!(1, res.gates[0].wire);
        let res = parse_qasm("qubit[2] q;\nmeasure q[0] -> c[0];").unwrap();
        assert_eq!(GateKind::Measure, res.gates[0].kind);
        assert!(parse_qasm("qubit[1] q;\nmeasure q[5];").is_err());
    }

    #[test]
    fn parse_barrier_test() {
        let res = parse_qasm("qubit[2] q;\nbarrier q[0], q[1];\nh q[0];").unwrap();
        assert_eq!(vec![0, 1], res.gates[0].wires);
        assert_eq!(1, res.gates[1].col);
        let res = parse_qasm("qubit[2] q;\nbarrier;").unwrap();
        assert_eq!(vec![0, 1], res.gates[0].wires);
        assert!(parse_qasm("qubit[1] q;\nbarrier q[3];").is_err());
    }

    #[test]
    fn parse_invalid_test() {
        assert!(parse_qasm("h q[0];").is_err());
        assert!(parse_qasm("qubit[1] q; h q[5];").is_err());
        assert!(parse_qasm("qubit[1] q; bogus q[0];").is_err());
        assert!(parse_qasm("qubit[2] q; cx q[0], q[0];").is_err());
        assert!(parse_qasm("qubit[2] q; h q[0], q[1];").is_err());
        assert!(parse_qasm("qubit[99] q;").is_err());
        assert!(parse_qasm("qubit[1] q;\ngate foo a { x a; }\nfoo q[5];").is_err());
        assert!(parse_qasm("qubit[1] q;\ngate foo a { x a; }\nfoo;").is_err());
    }

    #[test]
    fn round_trip_test() {
        let st = State {
            qubits: 3,
            gates: vec![
                Gate {
                    id: 0,
                    ..Gate::new(GateKind::H, 0, 0)
                },
                Gate {
                    id: 1,
                    angle: Some("pi/2".into()),
                    ..Gate::new(GateKind::Rx, 1, 0)
                },
                Gate {
                    id: 2,
                    target: Some(2),
                    ..Gate::new(GateKind::Cx, 0, 1)
                },
            ],
            ..Default::default()
        };
        let round = parse_qasm(&generate_qasm(&st)).unwrap();
        assert_eq!(3, round.qubits);
        assert_eq!(st.gates.len(), round.gates.len());
        let kinds: Vec<GateKind> = round.gates.iter().map(|g| g.kind).collect();
        assert_eq!(vec![GateKind::H, GateKind::Rx, GateKind::Cx], kinds);

        let st = State {
            qubits: 3,
            gates: vec![Gate {
                id: 0,
                control: Some(1),
                target: Some(2),
                ..Gate::new(GateKind::Ccx, 0, 0)
            }],
            ..Default::default()
        };
        let round = parse_qasm(&generate_qasm(&st)).unwrap();
        assert_eq!(GateKind::Ccx, round.gates[0].kind);
        assert_eq!(Some(1), round.gates[0].control);
        assert_eq!(Some(2), round.gates[0].target);
    }
}
