# QC Schematic

A desktop editor for quantum circuits. Build a circuit by dragging gates onto
qubit wires, or by writing OpenQASM 3 directly — the canvas and the code stay in
sync, so you can work whichever way you prefer.

> **OpenQASM** is a text format for describing quantum circuits. QC Schematic
> reads and writes **OpenQASM 3**.

## Features

- **Two-way editing** — drag gates on the canvas and the OpenQASM editor updates
  instantly; type in the editor and the canvas redraws.
- **Gate palette** — H, X, Y, Z, S, T, S†, T†, Rx, Ry, Rz, P (phase), ID, CX
  (control–NOT), measurement, and barrier, with 1–12 qubits.
- **Direct manipulation** — drag a gate onto a wire to place it, double-click to
  remove it, and drag a CX gate's target between wires.
- **Custom gates** — `gate` definitions in OpenQASM are parsed and drawn as
  labelled rectangles spanning the wires they act on.
- **Measurement and classical bits** — adding a measurement creates the classical
  register in OpenQASM, draws the classical wire, and links the two with a dotted
  line.
- **Live validation** — line numbers and an OpenQASM 3 grammar check flag syntax
  errors as you type.
- **File operations** — New, Open, Save (`Ctrl/Cmd+S`), Save As, and SVG export.
- **Autosave snapshots** — periodic snapshots are written next to a file you have
  already saved, and cleaned up automatically when you save again.
- **Dark mode** — configurable under Preferences → Settings.

## Prerequisites

QC Schematic is an [Electron](https://www.electronjs.org/) app compiled from
[ClojureScript](https://clojurescript.org/) with
[shadow-cljs](https://github.com/thheller/shadow-cljs). Install:

- **Node.js** 20 or newer (with npm)
- A **Java Development Kit (JDK)** 8 or newer — shadow-cljs runs on the JVM

Verify both are on your `PATH`:

```console
$ node --version
v20.11.0
$ java -version
openjdk version "17.0.10" 2024-01-16
```

## Getting started

Install dependencies, compile the renderer, and launch the app:

```console
$ npm install
$ npm run build:cljs
$ npm start
```

The QC Schematic window opens with a 4-qubit circuit. Drag a gate from the
**Operations** palette on the left onto a wire, then watch the **Editor** panel on
the right update.

> `npm start` runs Electron against the compiled bundle in `public/js`. Run
> `npm run build:cljs` at least once before the first `npm start`, or use
> `npm run watch:cljs` (below) so the bundle is always current.

### Develop with automatic rebuilds

Run a ClojureScript watcher in one terminal and Electron in another:

```console
$ npm run watch:cljs
```

```console
$ npm start
```

Changes to files under `src/` recompile automatically; reload the window to see
them.

## Using the app

### Build a circuit on the canvas

1. Set the **Number of qubits** (1–12) under the palette.
2. Drag a gate from the palette onto a wire. A preview shows where it will land.
3. Double-click a gate to remove it.
4. For a CX gate, drag the circle on the target wire to move the target.

### Edit the OpenQASM

The **Editor** panel contains the OpenQASM 3 source. Edit it and the canvas
redraws after a short pause (or immediately when the editor loses focus). A
status indicator shows `valid` when the grammar check passes, or the first error's
line and column when it does not.

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
Preferences → Settings, and removes the snapshot on the next save.

| Action          | Shortcut              |
| --------------- | --------------------- |
| New window      | `Ctrl/Cmd+N`          |
| Open…           | `Ctrl/Cmd+O`          |
| Save            | `Ctrl/Cmd+S`          |
| Save As…        | `Ctrl/Cmd+Shift+S`    |
| Export SVG…     | `Ctrl/Cmd+Shift+E`    |
| Settings…       | `Ctrl/Cmd+,`          |

The **Edit** menu (Undo, Redo, Cut, Copy, Paste, Paste and Match Style, Delete,
Select All) operates on the focused editor.

## Testing

Run the unit tests:

```console
$ npm test
```

Run the integration tests (jsdom-based, exercising the renderer through its IPC
boundaries):

```console
$ npm run test:integration
```

Run the end-to-end (system) tests. These boot the real Electron application and
drive it like a user; they require a headless-capable environment:

```console
$ npm run test:system
```

Every command prints a pass/fail summary and exits non-zero if anything fails. Run
them all together with:

```console
$ npm run test:all
```

### Coverage

Generate an LCOV report and a per-file summary for `src/app/**`:

```console
$ npm run coverage
```

The command fails if line coverage drops below the threshold (default 50%). Override
it with the `COVERAGE_THRESHOLD` environment variable:

```console
$ $env:COVERAGE_THRESHOLD=80; npm run coverage   # PowerShell
$ COVERAGE_THRESHOLD=80 npm run coverage         # bash
```

## Packaging

Build an installer for your current platform:

```console
$ npm run dist
```

Or target a specific platform:

```console
$ npm run dist:win     # Windows NSIS installer
$ npm run dist:linux   # Linux AppImage and .deb
$ npm run dist:mac     # macOS DMG
```

Artifacts are written to `dist/`. To produce an unpacked build for quick
inspection, use `npm run pack`.

## Project structure

```
src/app/
  core.cljs      Entry point; boots the UI
  ui.cljs        DOM rendering and interaction (palette, canvas, editor, settings)
  model.cljs     Gate data, geometry, and application state
  qasm.cljs      OpenQASM 3 parse and generate
  grammar.cljs   OpenQASM 3 grammar validation
  export.cljs    SVG rendering
  io.cljs        Electron IPC wiring
main.cjs         Electron main process: window, menus, file dialogs, snapshots
preload.cjs      Context-isolated bridge between the renderer and main process
test/app/        Unit and integration specs
test/system/     End-to-end Electron test
```

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
