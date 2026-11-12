# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Changed

- Rewrote QC Schematic in Rust using `egui`/`eframe`. The application is now a
  two-crate Cargo workspace — `qc-core` (pure domain: model, OpenQASM
  parse/generate, grammar, geometry, SVG export) and `qc-app` (eframe/egui shell)
  — and builds to a single native binary with no Node.js or JavaScript runtime.
- Replaced the Electron/DOM interface with native egui rendering and native
  open/save dialogs; the OpenQASM editor now opens in its own OS window
  (**View → Windows → Editor**).
- The canvas draws gates as vectors instead of loading SVG assets, and exported
  SVGs are generated in memory.
- The activity log now writes to the platform log directory (Windows
  `%LOCALAPPDATA%\qc-schematic\logs`, macOS `~/Library/Logs/qc-schematic`, Linux
  `~/.local/state/qc-schematic`), still overridable via `QC_SCHEMATIC_LOG_DIR`.

### Added

- Property-based tests (`proptest`) for OpenQASM parse/generate round-tripping.

### Removed

- Removed the ClojureScript/Electron implementation and its tooling: the
  `src/**/*.cljs` sources, `main.cjs`, `preload.cjs`, `logger.cjs`, `index.html`,
  `styles.css`, shadow-cljs and npm build/packaging scripts, and the jsdom and
  Electron system-test harnesses.

## [0.1.1-alpha] - 2026-11-01

### Added

- Rotating activity log for the main process: application version, window
  lifecycle, and file operations are recorded to the platform log directory,
  with the file rotated once it reaches 5 MB (one backup kept).
- CCX (Toffoli) gate: palette entry, canvas symbol, target dragging, OpenQASM
  import/export (`ccx q[a], q[b], q[c];`) and SVG export.

### Changed

- The OpenQASM editor window no longer shows the application menu bar, leaving a
  focused editing surface.
- The exported CNOT and CCX symbols now match the canvas in black and white:
  filled control dots and crossed target rings.
- SVG export now draws the canvas measurement symbol with rounded corners
  consistent with the other gate symbols, instead of a labelled gate box.

## [0.1.0-alpha] - 2026-10-15

Initial release of QC Schematic, a desktop quantum circuit schematic editor with
a two-way OpenQASM 3 editor.

### Added

- Schematic canvas for building quantum circuits: drag gates from the palette
  onto qubit wires, double-click a gate to remove it, and drag a CX gate's target
  to move it between wires.
- Gate palette with H, X, Y, Z, S, T, S†, T†, Rx, Ry, Rz, P (phase), ID, CX,
  measurement, and barrier operations, plus a configurable qubit count (1–12).
- Editable OpenQASM 3 editor that mirrors canvas changes and re-draws the canvas
  as you type, with line numbers and live grammar validation.
- Support for user-defined gates (`gate` definitions): calls are parsed and drawn
  on the canvas as labeled rectangles spanning the wires they use.
- Measurement support with a classical register: adding a measurement creates the
  classical bits in OpenQASM, draws the classical wire, and links the measurement
  to it with a dotted line.
- File menu with New (opens a new application window), Open, Save (Ctrl/Cmd+S),
  and Save As, backed by native open/save dialogs.
- Export of the current circuit as a standalone SVG file (File > Export SVG).
- Automatic snapshots of unsaved work at a configurable interval, created only
  for circuits that have been saved at least once and removed automatically on
  the next save.
- Preferences > Settings for dark mode and the snapshot interval, persisted
  between sessions.
- Horizontal and vertical scrolling on the canvas, with wires that resize on
  window resize.
- Native application menus for File, Edit actions on the editor content,
  Preferences, View, and Window.
- Unit, integration, and system test suites with code-coverage reporting.
- Packaging for Windows (NSIS installer), macOS (DMG), and Linux (AppImage/deb).

### Fixed

- CX control/target placement is now correct when the control sits on the
  second-to-last wire.

[Unreleased]: https://github.com/sebastianardelean/qc-schematic/compare/0.1.1-alpha...HEAD
[0.1.1-alpha]: https://github.com/sebastianardelean/qc-schematic/compare/0.1.0-alpha...0.1.1-alpha
[0.1.0-alpha]: https://github.com/sebastianardelean/qc-schematic/releases/tag/0.1.0-alpha
