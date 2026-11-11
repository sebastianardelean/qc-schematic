# QC Schematic — Rust/egui Migration Plan

## 1. Current application inventory

**Stack:** Electron shell + ClojureScript renderer (shadow-cljs), two OS windows, IPC bridge.

| Layer | Files | Lines | Nature |
|---|---|---|---|
| Domain logic | `model.cljs` | 233 | Pure: gate types, geometry, state, undo/redo, placement |
| OpenQASM I/O | `qasm.cljs` | 220 | Pure: regex parse + generate |
| Validation | `grammar.cljs` | 504 | Pure: hand-written lexer + recursive-descent parser |
| Export | `export.cljs` | 129 | Pure: SVG string generation |
| UI | `ui.cljs` | 537 | DOM rendering, drag/drop, palette, settings |
| Editor UI | `editor.cljs` | 111 | Textarea + line-number gutter + validation |
| Glue | `dom.cljs`, `io.cljs`, `core.cljs` | 86 | DOM helpers, Electron IPC wiring |
| Shell | `main.cjs` | 487 | Windows, menus, dialogs, snapshots, IPC handlers |
| Shell | `preload.cjs`, `logger.cjs` | 173 | IPC bridge, rotating file logger |
| Tests | 8 CLJS + 2 CJS | ~1600 | Unit, jsdom integration, Electron e2e |

**Data flow today:** the renderer owns the model (`model/state` atom) → generates QASM → publishes over IPC → the main process relays it to the paired editor window. Menu actions in the main process are relayed back to renderers. The main process also owns the snapshot timer and all filesystem/dialog work.

**Key insight:** ~1,100 lines (model, qasm, grammar, export) are pure, side-effect-free functions and port almost mechanically. The hard parts are the *rendering model change* (retained DOM/CSS → immediate-mode painting) and *window/menu/filesystem* integration (currently split across two processes).

## 2. Target Rust architecture

Split into a pure core and a GUI app (mirrors the existing pure/effectful boundary and enables fast, headless testing):

```
qc-schematic/
  Cargo.toml                    # workspace
  crates/
    qc-core/                    # no GUI deps — pure domain
      src/
        model.rs                # <- model.cljs
        qasm/{mod,generate,parse}.rs   # <- qasm.cljs
        grammar/{mod,lexer,parser}.rs  # <- grammar.cljs
        export.rs               # <- export.cljs (SVG strings)
    qc-app/                     # eframe/egui
      src/
        main.rs                 # <- core.cljs / editor/core.cljs
        app.rs                  # App struct, update(), viewports
        canvas.rs               # <- ui.cljs canvas + gate painting
        palette.rs              # <- ui.cljs palette
        editor.rs               # <- editor.cljs (code editor + gutter)
        settings.rs             # <- ui.cljs settings panel
        menu.rs                 # <- main.cjs buildMenu
        files.rs                # <- main.cjs dialogs/handlers (rfd)
        snapshot.rs             # <- main.cjs snapshot timer
        prefs.rs                # <- localStorage persistence
        logging.rs              # <- logger.cjs
        ports.rs                # traits: FileDialogs, Clock, Logger
```

**Mapping:**

| CLJS | Rust | Notes |
|---|---|---|
| `model.cljs` | `qc-core::model` | State struct + history/future vecs (or `Vec<State>` undo stack) |
| `qasm.cljs` | `qc-core::qasm` | Keep regex/line-based parser to preserve exact test vectors |
| `grammar.cljs` | `qc-core::grammar` | Port lexer/parser verbatim; `logos`/`chumsky` optional but not required |
| `export.cljs` | `qc-core::export` | Keep string output; tests assert exact SVG attributes |
| `ui.cljs` | `qc-app::{canvas,palette,app}` | `Painter` shapes instead of DOM + `<img>` |
| `editor.cljs` | `qc-app::editor` | `TextEdit::multiline().code_editor()` + painted gutter |
| `io.cljs` + `preload.cjs` | *eliminated* | No process boundary; in-process state |
| `main.cjs` | `qc-app::{main,menu,files,snapshot}` | Native dialogs/menus |
| `logger.cjs` | `qc-app::logging` | Size-based rotation preserved |

**Proposed crates:** `eframe`/`egui` (GUI), `rfd` (native file dialogs), `muda` (optional native menus), `serde` + `eframe` storage (prefs), `tracing` + `tracing-subscriber` + `file-rotate` (logging), `thiserror`/`anyhow` (errors), `directories` (paths), `proptest` (round-trips), `egui_kittest` + `insta` (UI tests).

## 3. The three hard problems

1. **Retained DOM/CSS -> immediate-mode painting.** Wires (CSS `repeat-x` background images), gates (positioned `<img>` SVG), previews, and the classical wire all become `Painter` calls (`line`, `rect`, `circle`, `text`, `Shape::dashed_line`). This *removes the 22 static SVG files* and unifies canvas drawing with the existing vector `export.rs` geometry. Alternative: rasterize SVGs via `resvg` into textures for pixel parity — rejected as unnecessary since export already draws vectors.

2. **Two windows + File -> New.** eframe's multi-viewport feature can host the editor as a real second OS window; if it proves immature, fall back to the in-window `#code-panel` layout the CSS already anticipates. "New" currently spawns a window -> either re-exec the process or move to multi-document semantics. Must be an explicit decision.

3. **Menus & the Edit menu.** Electron provides native menus + native cut/copy/paste. egui gives in-window menu bars and text-edit shortcuts for free; true native menus need `muda`. Decide early (macOS HIG favors native).

## 4. Phased plan (thin end-to-end slice first)

- **Phase 0 — Scaffold & decisions.** Workspace, CI (`fmt`/`clippy`/`test`), crate versions. Write ADRs (below).
- **Phase 1 — Domain core.** Port `model`, `qasm`, `grammar`, `export` + all unit tests; add `proptest` generate->parse round-trips. Validate against the existing CLJS test vectors as oracle. Deliverable: `qc-core` green + a tiny CLI to parse/generate/export.
- **Phase 2 — Thin vertical slice.** eframe window: palette + canvas painter + qubit count; click/drag to place a gate; show generated QASM. Single window, no menus/files. Proves the rendering approach.
- **Phase 3 — Interactions.** Full palette, CX/CCX target drag, barrier, measure + classical wire, drag preview, undo/redo. Port UI tests with `egui_kittest`.
- **Phase 4 — Editor.** Second viewport with code editor, line numbers, live validation, debounced two-way sync preserving "invalid edit keeps last valid".
- **Phase 5 — Desktop integration.** Menus/shortcuts, `rfd` dialogs, Save/Save As/Export SVG, settings (dark mode, snapshot minutes), persistence, autosave snapshots, rotating log.
- **Phase 6 — Packaging & parity.** `cargo-packager`/`cargo-bundle` for NSIS/DMG/AppImage/deb; verify feature checklist; delete Electron/shadow-cljs/Node artifacts.

## 5. Contracts to preserve (tested behavior)

- QASM strings exactly: `cx q[0], q[1];`, angle defaults (`rx(pi/2)`), defs emitted before `qubit`, `bit[n] c;` only when cbits > 0.
- Parser validation: index ranges, distinct CCX operands, custom-gate arity.
- Grammar validator returns `{ok, errors:[{line,col,message}]}` for the supported subset.
- Geometry constants + collision/placement rules (`nearest-free-col`, `nearest-free-wire`, `cx-target`, `ccx-wires`).
- SVG export attributes (monochrome, `rx="6"`, radii 10/11) — port carefully or deliberately relax tests.
- Snapshot file name `.{basename}.autosave.qasm`; prefs keys `qc-schematic-dark`, `qc-schematic-snapshot-minutes`; snapshot clamp 1–1440, default 1.

## 6. Testing strategy

- **Core:** Rust unit + integration tests ported from `*_test.cljs`; `proptest` for round-trips. This carries the bulk of the current coverage.
- **UI:** `egui_kittest` (headless harness) + `insta` snapshots replace jsdom tests; query by accessibility tree rather than DOM ids.
- **Integration seams:** define `ports.rs` traits (`FileDialogs`, `Clock`, `Logger`) so logic is tested with fakes — the direct analogue of today's `electronAPI` mocks.
- **E2E:** `egui_kittest` covers most; keep one OS smoke test for window/menu/dialog wiring.

## 7. ADRs to record

- ADR-001: Workspace split (core vs app)
- ADR-002: egui/eframe as the GUI toolkit
- ADR-003: Canvas = immediate-mode vector painting
- ADR-004: Multi-window via viewports (fallback in-window panel)
- ADR-005: Native (`muda`) vs egui menus
- ADR-006: Preferences persistence
- ADR-007: Testing/ports approach
- ADR-008: Packaging toolchain
- ADR-009: File -> New semantics

## 8. Risks

| Risk | Severity | Mitigation |
|---|---|---|
| eframe multi-viewport immaturity | High | In-window editor fallback behind a `Windows` abstraction; decide in Phase 0 |
| Immediate-mode rendering fidelity (wires, dark theme) | Medium | Reuse `export.rs` geometry; snapshot tests |
| Text editor gaps (gutter, undo grouping, tabs) | Medium | Small custom widget; keep validation pure |
| Native menu/accelerator parity & macOS HIG | Medium | Decide `muda` vs egui early |
| Parser edge-case drift | Low | Existing test vectors as oracle + proptest |
| Packaging toolchain change | Low | Isolated Phase 6 / separate CI job |

**Recommended first commit:** Phase 0 + Phase 1 (`qc-core` with ported tests passing), since it is low-risk, high-value, and de-risks the parser/export parity before any GUI work.
