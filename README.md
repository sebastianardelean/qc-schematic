# QC Schematic

A native desktop editor for quantum circuits, written in Rust with
[egui](https://github.com/emilk/egui)/[eframe](https://github.com/emilk/egui/tree/master/crates/eframe).
Build a circuit by dragging gates onto qubit wires, or by writing OpenQASM 3
directly — the canvas and the code stay in sync, so you can work whichever way
you prefer.

QC Schematic is a Rust port of an earlier ClojureScript/Electron application; it
reproduces that app's behaviour with a single self-contained binary and no
JavaScript runtime.

> **OpenQASM** is a text format for describing quantum circuits. QC Schematic
> reads and writes **OpenQASM 3**.

## Features

- **Two-way editing** — drag gates on the canvas and the OpenQASM editor updates
  instantly; type in the editor and the canvas redraws.
- **Two windows** — the main window holds the operations palette and canvas; the
  OpenQASM editor opens in its own OS window from **View → Windows → Editor**.
- **Gate palette** — H, X, Y, Z, S, T, S†, T†, Rx, Ry, Rz, P (phase), ID, CX
  (control–NOT), CCX (Toffoli), measurement, and barrier, with 1–12 qubits.
- **Direct manipulation** — drag a gate onto a wire to place it, double-click to
  remove it, and drag a CX or CCX gate's target between wires.
- **Custom gates** — `gate` definitions in OpenQASM are parsed and drawn as
  labelled rectangles spanning the wires they act on.
- **Measurement and classical bits** — adding a measurement creates the classical
  register in OpenQASM, draws the classical wire, and links the two with a dotted
  line.
- **Live validation** — line numbers and an OpenQASM 3 grammar check flag syntax
  errors as you type.
- **Undo / redo** — every canvas edit is reversible.
- **File operations** — New, Open, Save (`Ctrl/Cmd+S`), Save As, and SVG export.
- **Autosave snapshots** — periodic snapshots are written next to a file you have
  already saved, and cleaned up automatically when you save again.
- **Dark mode** — configurable under **Preferences → Settings**.
- **Activity log** — a rotating log file (kept to 5 MB plus one backup) for
  troubleshooting.

## Prerequisites

QC Schematic is a Rust workspace and requires only a Rust toolchain:

- **Rust** 1.91 or newer (the `stable-x86_64-pc-windows-msvc` toolchain, or the
  stable toolchain for your platform) with `cargo`.

Verify it is on your `PATH`:

```console
$ rustc --version
rustc 1.91.1 (ed61e7d7e 2025-11-07)
$ cargo --version
cargo 1.91.1
```

On Linux you also need the usual windowing/OpenGL development packages (for
example `libx11`, `libxkbcommon`, and a GPU driver exposing OpenGL/EGL).

## Getting started

Build and launch the application:

```console
$ cargo run -p qc-app
```

The QC Schematic window opens with a 4-qubit circuit. Drag a gate from the
**Operations** palette on the left onto a wire, then open the editor window from
**View → Windows → Editor** to see the OpenQASM update.

To produce an optimized binary:

```console
$ cargo build --release
$ ./target/release/qc-schematic
```

## Using the app

### Build a circuit on the canvas

1. Set the **Number of qubits** (1–12) under the palette.
2. Drag a gate from the palette onto a wire. A preview shows where it will land.
3. Double-click a gate to remove it.
4. For a CX or CCX gate, drag the circle on the target wire to move the target.

The canvas scrolls when a circuit is larger than the window; the horizontal and
vertical scrollbars are always visible.

### Edit the OpenQASM

Open the editor window from **View → Windows → Editor**. It contains the OpenQASM
3 source. Edit it and the canvas redraws after a short pause (or immediately when
the editor loses focus). A status indicator shows `valid` when the grammar check
passes, or the first error's line and column when it does not.

```qasm
OPENQASM 3;
include "stdgates.inc";

qubit[2] q;

h q[0];
cx q[0], q[1];
```

### Define a custom gate

Gate definitions are recognised and rendered on the canvas as a rectangle labelled
with the gate name:

```qasm
gate cphase(θ) a, b
{
  U(0, 0, θ / 2) a;
  cx a, b;
  U(0, 0, -θ / 2) b;
  cx a, b;
  U(0, 0, θ / 2) b;
}

cphase(pi / 2) q[0], q[1];
```

### Measure

Drag the measurement gate onto a wire. If the circuit has no classical bits yet,
QC Schematic creates a classical register in the OpenQASM for you and draws the
classical wire, connected to the measurement by a dotted line.

### File operations

Use the **File** menu (or the shortcuts below). Saving a file the first time opens
a native dialog; later saves overwrite it. While a saved file is open, QC
Schematic writes an autosave snapshot beside it on the interval set under
**Preferences → Settings**, and removes the snapshot on the next save.

| Action          | Shortcut              |
| --------------- | --------------------- |
| New             | `Ctrl/Cmd+N`          |
| Open…           | `Ctrl/Cmd+O`          |
| Save            | `Ctrl/Cmd+S`          |
| Save As…        | `Ctrl/Cmd+Shift+S`    |
| Export SVG…     | `Ctrl/Cmd+Shift+E`    |
| Settings…       | `Ctrl/Cmd+,`          |
| Undo            | `Ctrl/Cmd+Z`          |
| Redo            | `Ctrl/Cmd+Shift+Z`    |

The **Edit** menu (Undo, Redo, Cut, Copy, Paste, Paste and Match Style, Delete,
Select All) operates on the focused editor.

### Logs

The application writes a plain-text activity log to the platform log directory:

- **Windows:** `%LOCALAPPDATA%\qc-schematic\logs`
- **macOS:** `~/Library/Logs/qc-schematic`
- **Linux:** `~/.local/state/qc-schematic`

The file is named `qc-schematic.log`. It records window lifecycle, file
operations, and unexpected failures. When the file reaches 5 MB it is rotated to
`qc-schematic.log.1`, and the previous backup is replaced, so only 5 MB plus one
generation is kept.

To redirect the log elsewhere (for example when running tests), set the
`QC_SCHEMATIC_LOG_DIR` environment variable to a directory.

## Testing

Run the workspace test suite:

```console
$ cargo test --workspace
```

The suite is split across the two crates: `qc-core` tests cover parsing,
generation, geometry, and SVG export (including property-based tests via
[`proptest`](https://docs.rs/proptest)), and `qc-app` tests cover status
validation and the rotating logger. Before submitting changes, also run:

```console
$ cargo fmt --all -- --check
$ cargo clippy --workspace --all-targets
```

## Packaging

Produce an optimized binary for your current platform:

```console
$ cargo build --release
```

The executable is written to `target/release/qc-schematic` (`.exe` on Windows).
The release profile enables thin LTO.

## Project structure

```
crates/
  qc-core/          Pure domain crate: no UI, no I/O
    src/model.rs      Gate types, geometry, circuit state, palette
    src/qasm.rs       OpenQASM 3 parse and generate
    src/grammar.rs    OpenQASM 3 grammar validation
    src/export.rs     SVG rendering
    src/lib.rs        Public API surface
  qc-app/           eframe/egui application crate
    src/main.rs       Entry point (run_native)
    src/app.rs        Application state, menus, shortcuts, settings, editor viewport
    src/canvas.rs     Canvas rendering, hit-testing, drag-and-drop
    src/editor.rs     OpenQASM editor window (textarea, line numbers, validation)
    src/paint.rs      Gate/icon drawing helpers and colours
    src/files.rs      Native file dialogs and snapshot paths
    src/logging.rs    Rotating file logger
static/             Reference SVG art from the original app (not loaded at runtime)
```

## Architecture

QC Schematic is a two-crate workspace with a strict, one-way dependency
direction: `qc-app` depends on `qc-core`, and `qc-core` depends on nothing in the
workspace.

- **`qc-core` is the domain.** It owns the circuit model, the OpenQASM parser and
  generator, the grammar checker, the geometry, and SVG export. It performs no
  I/O and has no UI dependency, so it is deterministic and fully unit-testable.
  Its public API is the only contract the application relies on — for example
  `parse_qasm`, `circuit_to_svg`, and the geometry helpers such as `wire_y` and
  `col_x`.
- **`qc-app` is the shell.** It owns presentation and all side effects: the
  egui immediate-mode UI, native dialogs (`rfd`), clipboard access (`arboard`),
  the file logger, and the second OS window for the editor.

Data flows in two directions. A canvas edit mutates the `State`, regenerates the
OpenQASM text, and publishes it to the editor; an editor edit is parsed back into
a `State` and the canvas repaints. The two OS windows share an
`Arc<Mutex<EditorShared>>` for the text, status, and lifecycle flags, while the
authoritative circuit `State` lives in the main window.

Key decisions, and why:

- **Immediate-mode GUI (`egui`/`eframe`).** A single-window desktop app with no
  retained widget tree keeps the state model in one place and avoids a separate
  UI framework.
- **The editor is a separate OS window**, not a panel, matching the original
  app's workflow and keeping the canvas uncluttered.
- **Vectors, not rasterized SVG.** The 22 static SVGs are not embedded or parsed;
  gates and the `|0⟩` marker are drawn directly, and exported SVGs are generated
  in memory. This removes a rendering dependency and keeps output crisp.
- **Failures degrade gracefully.** Invalid OpenQASM is rejected and the last good
  circuit is retained; log writes never panic; file errors surface in the status
  bar rather than aborting.

## OpenQASM support

The editor reads and writes the subset of OpenQASM 3 that it can draw: version and
`include` statements, `qubit`/`bit` declarations, `gate` definitions, gate calls
(including `ctrl`/`inv`/`pow` modifiers), `measure`, `barrier`, and `reset`.
Validation is purely syntactic — unknown gates or out-of-range indices are
reported by the parser that builds the canvas, not the grammar checker. Constructs
outside this subset (control flow, pulse calibration, and so on) are reported as
unsupported rather than silently accepted.

## License

QC Schematic is licensed under the GNU General Public License v3.0. See
[LICENSE](LICENSE).
