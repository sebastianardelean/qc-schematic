# QC Schematic — Requirements

This document specifies the requirements for QC Schematic, a desktop quantum
circuit schematic editor with a two-way OpenQASM 3 editor. It is derived from the
current application's behaviour (source, tests, README, CHANGELOG) and is written
to serve as the acceptance baseline for the Rust/egui migration described in
`refactor_plan.md`.

Requirements are labelled:

- **FR** — functional requirement (observable behaviour).
- **QA** — quality attribute (non-functional).
- **CON** — constraint (fixed, not a preference).
- **DATA** — data/format contract.
- **OUT** — explicitly out of scope.

Each requirement carries a priority: **MUST**, **SHOULD**, or **MAY**.

---

## 1. Scope

QC Schematic lets a user build a quantum circuit two ways that stay in sync:

1. Directly, by dragging gates from a palette onto qubit wires on a canvas.
2. Textually, by writing OpenQASM 3 in a separate editor window.

The canvas redraws when the text changes and the text regenerates when the canvas
changes. Circuits can be opened, saved, and exported as SVG.

---

## 2. Functional requirements

### 2.1 Circuit model

- **FR-1 (MUST)** The circuit model SHALL track: number of qubits, number of
  classical bits, an ordered list of gates, and custom gate definitions.
- **FR-2 (MUST)** Each gate SHALL carry a stable identity (id), a type, the wire
  it occupies, a column, and, where applicable, target/control wires, an angle
  argument, and the set of wires it spans.
- **FR-3 (MUST)** The number of qubits SHALL be constrained to 1–12 inclusive.
- **FR-4 (MUST)** The number of classical bits SHALL be constrained to 0–12
  inclusive.
- **FR-5 (MUST)** Supported gate types SHALL be: X, Y, Z, H, S, T, S†, T†, Rx,
  Ry, Rz, P (phase), ID, CX (control–NOT), CCX (Toffoli), measurement, and
  barrier. Rx, Ry, Rz and P SHALL default to a `pi/2` angle argument.
- **FR-6 (MUST)** Changing the qubit count SHALL remove any gate that no longer
  fits within the new wire range.
- **FR-7 (MUST)** The application SHALL support undo and redo of circuit
  mutations; a new mutation SHALL clear the redo history.

### 2.2 Canvas and geometry

- **FR-8 (MUST)** The canvas SHALL draw one horizontal wire per qubit, a zero-state
  marker at the left of each wire, and, when classical bits exist, a single
  classical wire below the qubit wires.
- **FR-9 (MUST)** Wire spacing, column spacing, gate size, and padding SHALL use a
  fixed geometry (wire gap 90 px, column gap 70 px, gate size 46 px, top padding
  50 px, origin x 100 px) so that canvas layout and SVG export agree.
- **FR-10 (MUST)** Gate placement SHALL snap to the nearest wire and nearest free
  column; when the target column is occupied on any affected wire, the nearest
  free column SHALL be used instead.
- **FR-11 (MUST)** The canvas SHALL scroll both horizontally and vertically.
- **FR-12 (MUST)** The canvas content SHALL grow to fit the widest gate column and
  the number of rows; layers SHALL expand to fill the viewport when the content is
  narrower than the visible area.
- **FR-13 (MUST)** On window resize, the wires and layers SHALL re-layout.

### 2.3 Palette and direct manipulation

- **FR-14 (MUST)** A palette SHALL present all supported gate types as draggable
  items.
- **FR-15 (MUST)** Dragging a palette gate over the canvas SHALL show a translucent
  preview at the snapped destination; the preview SHALL hide on drop, drag-leave,
  or drag-end.
- **FR-16 (MUST)** Dropping a gate SHALL add it at the snapped cell, run
  measurement-related side effects (see FR-20), and regenerate the OpenQASM.
- **FR-17 (MUST)** Double-clicking a gate on the canvas SHALL remove it.
- **FR-18 (MUST)** A CX gate's target SHALL be draggable between wires; a CCX
  gate's target SHALL be draggable while its two controls are fixed. Dragging
  SHALL avoid wires already used by the gate and avoid occupied columns.
- **FR-19 (MUST)** A CX gate placed on a wire SHALL receive an adjacent target
  wire; a CCX gate SHALL receive two additional distinct wires; CCX placement
  SHALL be refused when fewer than three qubits are available.

### 2.4 Measurement and classical bits

- **FR-20 (MUST)** Adding the first measurement to a circuit with no classical
  bits SHALL create a classical register whose size equals the qubit count (capped
  at 12), draw exactly one classical wire, and connect the measurement to that
  wire with a dotted link.
- **FR-21 (MUST)** Additional measurements SHALL NOT add extra classical wires;
  they SHALL only affect the generated OpenQASM.

### 2.5 OpenQASM 3 generation

- **FR-22 (MUST)** The generated program SHALL begin with `OPENQASM 3;` and
  `include "stdgates.inc";`.
- **FR-23 (MUST)** Custom gate definitions SHALL be emitted before the qubit
  declaration.
- **FR-24 (MUST)** A `qubit[n] q;` declaration SHALL always be emitted; a
  `bit[n] c;` declaration SHALL be emitted only when the classical-bit count is
  greater than zero.
- **FR-25 (MUST)** Gates SHALL be emitted sorted by column, then wire, then id.
- **FR-26 (MUST)** Gate statement formats SHALL be:
  - single-qubit: `<name>[ (<angle>)] q[<wire>];`
  - CX: `cx q[<control>], q[<target>];`
  - CCX: `ccx q[<control1>], q[<control2>], q[<target>];`
  - measurement: `measure q[<wire>];`
  - barrier: `barrier[ q[a], q[b], ...];`
  - custom: `<name> q[a], q[b], ...;`
- **FR-27 (MUST)** The generated program SHALL end with a trailing newline.

### 2.6 OpenQASM 3 parsing

- **FR-28 (MUST)** The parser SHALL accept version/include statements,
  `qubit`/`bit` declarations, `gate` definitions (with or without parameters,
  including a brace on the following line), gate calls, `measure` (with or without
  a classical target), `barrier` (with explicit wires or bare), and `reset`.
- **FR-29 (MUST)** Comments (`//` line comments) SHALL be stripped before
  interpretation; blank lines SHALL be ignored.
- **FR-30 (MUST)** Columns SHALL be assigned so that gates affecting disjoint
  wires may share a column, and overlapping gates are scheduled in successive
  columns.
- **FR-31 (MUST)** The parser SHALL reject, with a failure result: missing qubit
  declaration, out-of-range wire indices, unknown gate names, CX with identical
  operands, extra operands on single-qubit gates, qubit counts outside 1–12,
  classical counts outside 0–12, and custom-gate calls with wrong wire counts or
  out-of-range indices.
- **FR-32 (MUST)** CCX SHALL require exactly three distinct, in-range operands.
- **FR-33 (MUST)** A parse that succeeds SHALL return the qubit count, classical
  count, gate list, and custom gate definitions.
- **FR-34 (MUST)** Parsing SHALL apply FR-20 semantics when a measurement appears
  without a classical-bit declaration.

### 2.7 OpenQASM editor window

- **FR-35 (MUST)** The editor SHALL open in its own window from
  **View → Windows → Editor**; reopening SHALL focus the existing editor rather
  than create a second one.
- **FR-36 (MUST)** The editor window SHALL show no application menu bar.
- **FR-37 (MUST)** The editor SHALL display the OpenQASM source in an editable
  text area with a synchronized line-number gutter.
- **FR-38 (MUST)** Editing SHALL regenerate the canvas after a short debounce
  (250 ms) or immediately when the editor loses focus.
- **FR-39 (MUST)** An invalid edit SHALL NOT corrupt the circuit: the last valid
  circuit SHALL be retained, and the editor text SHALL remain as typed.
- **FR-40 (MUST)** The canvas owns the circuit model; the editor and canvas SHALL
  exchange text, and the main/canvas side SHALL be the text of record for saving.
- **FR-41 (MUST)** When the editor opens, the current circuit text SHALL be
  published to it.
- **FR-42 (MUST)** Each editor window SHALL be paired with the main window that
  spawned it; menu actions on either SHALL resolve to that pair's main window.

### 2.8 Grammar validation

- **FR-43 (MUST)** The editor SHALL validate the OpenQASM 3 source against the
  subset grammar and display a status: `valid`, or `invalid: line <n>: <message>`
  with a `(+k more)` suffix when additional errors exist.
- **FR-44 (MUST)** Validation SHALL be purely syntactic (no semantic analysis of
  gate existence or index ranges) and SHALL cover: version/include, register
  declarations, gate definitions, gate calls and modifiers (`ctrl`, `inv`, `pow`,
  `negctrl`), measure/barrier/reset, and the expression grammar (arithmetic,
  comparison, bitwise, logical, casts, calls).
- **FR-45 (MUST)** Constructs outside the supported subset (control flow, pulse
  calibration, etc.) SHALL be reported as unsupported rather than accepted.
- **FR-46 (MUST)** Lexical errors (unterminated comments/strings, stray
  characters) SHALL be reported with line and column.
- **FR-47 (MUST)** When the source is invalid, the editor text SHALL be visibly
  marked (error styling).

### 2.9 File operations

- **FR-48 (MUST)** The application SHALL provide New, Open, Save, Save As, and
  Export SVG.
- **FR-49 (MUST)** Save on a never-saved circuit SHALL prompt with a native save
  dialog; subsequent saves SHALL overwrite the remembered path.
- **FR-50 (MUST)** Open SHALL use a native open dialog and load the chosen file's
  contents into the circuit (canvas and editor both update).
- **FR-51 (MUST)** Export SVG SHALL render the current circuit to a standalone SVG
  document and write it via a native save dialog.
- **FR-52 (MUST)** New SHALL create a new circuit/instance (see ADR-009 for the
  windowing semantics to adopt).
- **FR-53 (MUST)** File dialogs SHALL default to `circuit.qasm` (OpenQASM) and
  `circuit.svg` (SVG), with appropriate file filters.

### 2.10 Autosave snapshots

- **FR-54 (MUST)** When a circuit has been saved at least once, the application
  SHALL write an autosave snapshot on a configurable interval.
- **FR-55 (MUST)** The snapshot SHALL be written beside the saved file as
  `.<basename>.autosave.qasm`.
- **FR-56 (MUST)** Snapshots SHALL NOT be created for circuits that have never
  been saved.
- **FR-57 (MUST)** Saving (Save or Save As) SHALL remove the snapshot for the
  current path, and Save As SHALL also remove the snapshot for the previous path.

### 2.11 Menus and shortcuts

- **FR-58 (MUST)** The main window SHALL expose menus: File, Edit, Preferences,
  View, and Window.
- **FR-59 (MUST)** File SHALL contain New, Open…, Save, Save As…, Export SVG…,
  and Quit (Close on macOS).
- **FR-60 (MUST)** Edit SHALL contain Undo, Redo, Cut, Copy, Paste, Paste and
  Match Style, Delete, and Select All; Cut/Copy/Paste/Delete/Select All SHALL act
  on the focused editor.
- **FR-61 (MUST)** Preferences SHALL contain Settings….
- **FR-62 (MUST)** View SHALL contain reload/reload-devtools/reset-zoom/zoom
  controls/fullscreen (as applicable) and Windows → Editor.
- **FR-63 (MUST)** Keyboard shortcuts SHALL be:
  | Action | Shortcut |
  | --- | --- |
  | New | Ctrl/Cmd+N |
  | Open… | Ctrl/Cmd+O |
  | Save | Ctrl/Cmd+S |
  | Save As… | Ctrl/Cmd+Shift+S |
  | Export SVG… | Ctrl/Cmd+Shift+E |
  | Settings… | Ctrl/Cmd+, |
  | Undo / Redo | Ctrl/Cmd+Z / Ctrl/Cmd+Shift+Z |
  | Cut/Copy/Paste/Select All | Ctrl/Cmd+X/C/V/A |

### 2.12 Settings and preferences

- **FR-64 (MUST)** Settings SHALL provide a dark-mode toggle and a snapshot-period
  input (1–1440 minutes, default 1).
- **FR-65 (MUST)** Settings SHALL open as an overlay and close via a Close
  control.
- **FR-66 (MUST)** Dark mode and snapshot period SHALL persist between sessions.
- **FR-67 (MUST)** The snapshot period SHALL be clamped to 1–1440; non-numeric
  input SHALL fall back to the default (1).

### 2.13 Activity logging

- **FR-68 (MUST)** The application SHALL write a plain-text activity log
  (`qc-schematic.log`) to the platform log directory.
- **FR-69 (MUST)** The log SHALL record application version, window lifecycle,
  file operations, and unexpected failures (uncaught exceptions, unhandled
  rejections, crashed renderer/child processes).
- **FR-70 (MUST)** The log SHALL rotate at 5 MB, keeping one backup
  (`qc-schematic.log.1`).
- **FR-71 (MUST)** Logging SHALL be best-effort: a write failure SHALL NOT crash
  the application.
- **FR-72 (SHOULD)** The log directory SHALL be overridable via the
  `QC_SCHEMATIC_LOG_DIR` environment variable.

---

## 3. Quality attributes

- **QA-1 (MUST)** *Portability:* the application SHALL build and run on Windows,
  macOS, and Linux.
- **QA-2 (MUST)** *Responsiveness:* editing and dragging SHALL remain interactive
  at the maximum circuit size (12 qubits, many columns).
- **QA-3 (MUST)** *Correctness of round-trips:* `generate → parse → generate`
  SHALL be stable for qubits, classical bits, gate types, columns, and wire spans.
- **QA-4 (MUST)** *Testability:* domain logic SHALL be separable from GUI and OS
  effects so it can be tested headlessly.
- **QA-5 (SHOULD)** *Coverage:* the domain core SHOULD retain the existing test
  coverage (line coverage threshold default 50%, configurable via
  `COVERAGE_THRESHOLD`).
- **QA-6 (SHOULD)** *Startup:* the application SHOULD present its window promptly
  after launch.
- **QA-7 (SHOULD)** *Observability:* unexpected failures SHOULD be recorded to the
  log rather than failing silently.
- **QA-8 (SHOULD)** *Accessibility:* interactive controls SHOULD be reachable by
  keyboard where the toolkit permits.

---

## 4. Constraints

- **CON-1** The target implementation language is **Rust**.
- **CON-2** The target GUI toolkit is **egui** (via `eframe`).
- **CON-3** The application SHALL read and write **OpenQASM 3** (the supported
  subset above).
- **CON-4** The project is licensed under **GPL-3.0**.
- **CON-5** SVG export SHALL be a self-contained document (no external assets).

---

## 5. Data and format contracts

- **DATA-1** Snapshot file name: `.<basename>.autosave.qasm` beside the saved
  file.
- **DATA-2** Preference keys: `qc-schematic-dark` (boolean), and a snapshot-period
  key with default 1 and range 1–1440.
- **DATA-3** Grammar validator result shape: success, or a list of
  `{line, col, message}` (line/column 1-based).
- **DATA-4** SVG export SHALL render gates as monochrome vector shapes: rounded
  rectangles (`rx=6`) with centred labels for standard gates; a filled control dot
  (r=10) and a crossed target ring (r=11) for CX/CCX; a rounded box with an arc
  and needle for measurement; a dashed vertical line for barrier; wires as thin
  horizontal lines. Labels: custom→name, ID→`I`, Rx→`Rx`, Ry→`Ry`, Rz→`Rz`,
  P→`P`, measurement symbol (no `M` text), others→uppercased gate name. Text SHALL
  be XML-escaped.
- **DATA-5** Canonical gate statement spellings SHALL match Section 2.5 exactly
  (e.g. `cx q[0], q[1];`, `rx(pi/2) q[0];`).

---

## 6. Out of scope

- **OUT-1** Quantum simulation or execution; the tool is a schematic editor only.
- **OUT-2** OpenQASM 3 constructs outside the supported subset: control flow
  (`if`/`for`/`while`), pulse calibration (`defcal`, `cal`), and other advanced
  statements (reported as unsupported, not modelled).
- **OUT-3** Semantic validation of gate definitions (unknown gates / out-of-range
  indices are handled by the canvas parser, not the grammar checker).
- **OUT-4** Multi-document editing within a single window (unless adopted as the
  ADR-009 decision).
- **OUT-5** Cloud sync, collaboration, or versioning.

---

## 7. Riskiest assumptions

1. **Multi-window support in eframe/egui is sufficient** for the main + editor
   pairing; otherwise an in-window editor panel is the fallback.
2. **Immediate-mode painting can reproduce** the canvas and the exact SVG export
   geometry from a single shared geometry source.
3. **The regex/line-based OpenQASM parser ports faithfully**, preserving the
   documented accept/reject behaviour encoded in the existing tests.
4. **Native menus and the Edit menu** can be delivered with acceptable fidelity
   (native `muda` vs in-window egui menu bar).
5. **Packaging** for all three platforms can be achieved without Electron's
   `electron-builder` feature set.
