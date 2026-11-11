//! The eframe application: state, menus, panels, file operations, settings and
//! the editor viewport. Port of `ui.cljs`, `editor.cljs` and `main.cjs`.

use std::path::PathBuf;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use eframe::egui;
use egui::{Color32, Context, Event, Key, Modifiers, Vec2, ViewportCommand, ViewportId};
use qc_core::export;
use qc_core::grammar;
use qc_core::model::{self, Gate, GateKind, State, UndoStack};
use qc_core::qasm;

use crate::canvas;
use crate::editor;
use crate::files::{self, Document};
use crate::logging::{self, Logger};
use crate::paint;

pub const DARK_PREF_KEY: &str = "qc-schematic-dark";
pub const SNAPSHOT_PREF_KEY: &str = "qc-schematic-snapshot-minutes";
pub const DEFAULT_SNAPSHOT_MINUTES: u32 = 1;
pub const MIN_SNAPSHOT_MINUTES: u32 = 1;
pub const MAX_SNAPSHOT_MINUTES: u32 = 1440;

/// A CX/CCX target being dragged between wires (FR-18).
#[derive(Clone, Debug)]
pub struct TargetDrag {
    pub id: u64,
    pub kind: GateKind,
    pub controls: Vec<i32>,
    pub wire: i32,
}

/// State shared between the main window and the editor viewport.
#[derive(Default)]
pub struct EditorShared {
    pub text: String,
    pub pending_apply: Option<String>,
    pub status: String,
    pub invalid: bool,
    pub last_edit: Option<Instant>,
    pub version: u64,
    /// Set by the editor viewport when the user requests to close its window.
    pub close_requested: bool,
}

#[derive(Clone, Copy)]
pub struct Theme {
    pub dark: bool,
}

impl Theme {
    pub fn canvas_bg(&self) -> Color32 {
        if self.dark {
            Color32::from_rgb(0x1b, 0x1f, 0x24)
        } else {
            Color32::WHITE
        }
    }
    pub fn gate_fill(&self) -> Color32 {
        if self.dark {
            Color32::from_rgb(0x26, 0x2c, 0x33)
        } else {
            Color32::WHITE
        }
    }
    pub fn gate_label(&self) -> Color32 {
        if self.dark {
            Color32::from_rgb(0xe6, 0xe6, 0xe6)
        } else {
            paint::GATE_COLOR
        }
    }
    pub fn wire(&self) -> Color32 {
        if self.dark {
            Color32::from_rgb(0x4a, 0x55, 0x60)
        } else {
            paint::WIRE_COLOR
        }
    }
}

pub struct QcApp {
    pub state: State,
    pub undo: UndoStack,
    pub code_text: String,
    pub drag_type: Option<GateKind>,
    pub target_drag: Option<TargetDrag>,
    pub shared: Arc<Mutex<EditorShared>>,
    pub editor_open: bool,
    pub editor_id: ViewportId,
    /// Set when the canvas regenerates code, so the editor viewport is woken.
    pub editor_dirty: bool,
    pub settings_open: bool,
    pub dark: bool,
    pub snapshot_minutes: u32,
    pub last_snapshot: Instant,
    pub document: Document,
    pub logger: Logger,
    pub status: Option<(String, Instant)>,
}

impl QcApp {
    pub fn new(cc: &eframe::CreationContext<'_>) -> Self {
        let dark = cc
            .storage
            .and_then(|s| eframe::get_value::<bool>(s, DARK_PREF_KEY))
            .unwrap_or(false);
        let snapshot_minutes = cc
            .storage
            .and_then(|s| eframe::get_value::<u32>(s, SNAPSHOT_PREF_KEY))
            .unwrap_or(DEFAULT_SNAPSHOT_MINUTES)
            .clamp(MIN_SNAPSHOT_MINUTES, MAX_SNAPSHOT_MINUTES);

        let logger = Logger::new(Some(log_dir().join(logging::LOG_FILE_NAME)));
        logger.info_meta(
            "application ready",
            &format!(
                "{{\"version\":\"{}\",\"platform\":\"{}\"}}",
                env!("CARGO_PKG_VERSION"),
                std::env::consts::OS
            ),
        );

        let state = State::default();
        let mut undo = UndoStack::new();
        undo.save(&state);
        let code_text = qasm::generate_qasm(&state);

        let mut shared = EditorShared {
            text: code_text.clone(),
            ..Default::default()
        };
        set_status(&mut shared);

        QcApp {
            state,
            undo,
            code_text,
            drag_type: None,
            target_drag: None,
            shared: Arc::new(Mutex::new(shared)),
            editor_open: false,
            editor_id: ViewportId::from_hash_of("qc-editor"),
            editor_dirty: false,
            settings_open: false,
            dark,
            snapshot_minutes,
            last_snapshot: Instant::now(),
            document: Document::default(),
            logger,
            status: None,
        }
    }

    pub fn theme(&self) -> Theme {
        Theme { dark: self.dark }
    }

    fn flash(&mut self, message: impl Into<String>) {
        self.status = Some((message.into(), Instant::now()));
    }

    /// Regenerate the OpenQASM text from the model and publish it to the editor.
    pub fn render_code(&mut self) {
        self.code_text = qasm::generate_qasm(&self.state);
        self.publish(&self.code_text.clone());
        self.editor_dirty = true;
    }

    fn publish(&self, text: &str) {
        if let Ok(mut s) = self.shared.lock() {
            if s.text != text {
                s.text = text.to_string();
                s.version += 1;
                s.last_edit = None;
            }
            set_status(&mut s);
        }
    }

    pub fn current_code(&self) -> String {
        self.code_text.clone()
    }

    /// Apply editor text to the model. On success the last valid circuit is
    /// replaced; on failure the circuit is retained (FR-39).
    pub fn apply_qasm(&mut self, text: &str) {
        match qasm::parse_qasm(text) {
            Ok(parsed) => {
                self.undo.save(&self.state);
                self.undo.clear_future();
                self.state.qubits = parsed.qubits;
                self.state.cbits = parsed.cbits;
                self.state.next_id = parsed.gates.len() as u64;
                self.state.defs = parsed.defs;
                self.state.gates = parsed.gates;
                self.code_text = text.to_string();
                self.publish(&self.code_text.clone());
                self.editor_dirty = true;
            }
            Err(_) => {
                self.logger
                    .warn("rejected invalid OpenQASM; circuit unchanged");
                self.flash("invalid OpenQASM \u{2014} circuit unchanged");
            }
        }
    }

    pub fn add_gate(&mut self, gate: Gate) {
        self.undo.save(&self.state);
        self.undo.clear_future();
        self.state = model::add_gate_to_state(&self.state, gate);
        self.state = model::ensure_measure_cbits(&self.state);
        self.render_code();
    }

    pub fn remove_gate(&mut self, id: u64) {
        self.undo.save(&self.state);
        self.undo.clear_future();
        self.state = model::remove_gate_from_state(&self.state, id);
        self.render_code();
    }

    pub fn set_qubits(&mut self, raw: i32) {
        self.undo.save(&self.state);
        self.undo.clear_future();
        self.state = model::set_qubits_in_state(&self.state, raw);
        self.render_code();
    }

    pub fn undo(&mut self) {
        self.undo.undo(&mut self.state);
        self.render_code();
    }

    pub fn redo(&mut self) {
        self.undo.redo(&mut self.state);
        self.render_code();
    }

    fn new_document(&mut self) {
        self.undo.clear();
        self.state = State::default();
        self.undo.save(&self.state);
        self.document = Document::default();
        self.render_code();
        self.flash("new circuit");
    }

    fn open(&mut self) {
        if let Some(path) = files::pick_open_qasm() {
            match std::fs::read_to_string(&path) {
                Ok(content) => {
                    self.apply_qasm(&content);
                    self.document.file_path = Some(path.clone());
                    self.logger
                        .info_meta("opened OpenQASM", &format!("\"{}\"", path.display()));
                    self.flash(format!("opened {}", path.display()));
                }
                Err(e) => {
                    self.logger
                        .error_meta("failed to open OpenQASM", &format!("\"{}\"", e));
                    self.flash(format!("open failed: {}", e));
                }
            }
        }
    }

    fn save(&mut self) {
        let path = match self.document.file_path.clone() {
            Some(p) => p,
            None => match files::pick_save_qasm(None) {
                Some(p) => p,
                None => return,
            },
        };
        self.write_to(path, false);
    }

    fn save_as(&mut self) {
        let previous = self.document.file_path.clone();
        if let Some(path) = files::pick_save_qasm(previous.as_deref()) {
            self.write_to(path, true);
        }
    }

    fn write_to(&mut self, path: PathBuf, save_as: bool) {
        match std::fs::write(&path, self.current_code()) {
            Ok(()) => {
                if save_as {
                    let (remove_new, remove_prev) = self.document.set_saved(path.clone());
                    if let Some(p) = remove_new {
                        files::remove_file(&p);
                    }
                    if let Some(p) = remove_prev {
                        files::remove_file(&p);
                    }
                } else {
                    self.document.file_path = Some(path.clone());
                    if let Some(p) = self.document.snapshot_path() {
                        files::remove_file(&p);
                    }
                }
                self.logger
                    .info_meta("saved OpenQASM", &format!("\"{}\"", path.display()));
                self.flash(format!("saved {}", path.display()));
            }
            Err(e) => {
                self.logger
                    .error_meta("failed to save OpenQASM", &format!("\"{}\"", e));
                self.flash(format!("save failed: {}", e));
            }
        }
    }

    fn export_svg(&mut self) {
        if let Some(path) = files::pick_save_svg() {
            let svg = export::circuit_to_svg(&self.state);
            match std::fs::write(&path, svg) {
                Ok(()) => {
                    self.logger
                        .info_meta("exported SVG", &format!("\"{}\"", path.display()));
                    self.flash(format!("exported {}", path.display()));
                }
                Err(e) => {
                    self.logger
                        .error_meta("failed to export SVG", &format!("\"{}\"", e));
                    self.flash(format!("export failed: {}", e));
                }
            }
        }
    }

    fn snapshot_tick(&mut self) {
        let interval = Duration::from_secs(self.snapshot_minutes as u64 * 60);
        if self.last_snapshot.elapsed() < interval {
            return;
        }
        self.last_snapshot = Instant::now();
        let Some(path) = self.document.snapshot_path() else {
            return;
        };
        match std::fs::write(&path, self.current_code()) {
            Ok(()) => self
                .logger
                .debug(&format!("wrote snapshot {}", path.display())),
            Err(e) => self
                .logger
                .error_meta("failed to write snapshot", &format!("\"{}\"", e)),
        }
    }

    fn apply_pending_editor(&mut self) {
        let pending = self
            .shared
            .lock()
            .ok()
            .and_then(|mut s| s.pending_apply.take());
        if let Some(text) = pending {
            self.apply_qasm(&text);
        }
    }

    fn inject_event(&self, ctx: &Context, event: Event) {
        ctx.input_mut(|i| i.events.push(event));
    }

    /// Keyboard shortcuts (FR-63).
    fn shortcuts(&mut self, ctx: &Context) {
        use egui::{Key, KeyboardShortcut, Modifiers};
        let cmd = Modifiers::COMMAND;
        let cmd_shift = Modifiers::COMMAND | Modifiers::SHIFT;
        let hit = |ctx: &Context, m: Modifiers, k: Key| {
            ctx.input_mut(|i| i.consume_shortcut(&KeyboardShortcut::new(m, k)))
        };

        if hit(ctx, cmd, Key::N) {
            self.new_document();
        }
        if hit(ctx, cmd, Key::O) {
            self.open();
        }
        if hit(ctx, cmd, Key::S) {
            self.save();
        }
        if hit(ctx, cmd_shift, Key::S) {
            self.save_as();
        }
        if hit(ctx, cmd_shift, Key::E) {
            self.export_svg();
        }
        if hit(ctx, cmd, Key::Comma) {
            self.settings_open = true;
        }
        if hit(ctx, cmd, Key::Z) {
            self.undo();
        }
        if hit(ctx, cmd_shift, Key::Z) {
            self.redo();
        }
        if hit(ctx, cmd, Key::X) {
            self.inject_event(ctx, Event::Cut);
        }
        if hit(ctx, cmd, Key::C) {
            self.inject_event(ctx, Event::Copy);
        }
        if hit(ctx, cmd, Key::V) {
            self.paste(ctx);
        }
        if hit(ctx, cmd, Key::A) {
            self.inject_event(
                ctx,
                Event::Key {
                    key: Key::A,
                    physical_key: None,
                    pressed: true,
                    repeat: false,
                    modifiers: Modifiers::COMMAND,
                },
            );
        }
    }

    fn menu_bar(&mut self, ui: &mut egui::Ui) {
        egui::MenuBar::new().ui(ui, |ui| {
            ui.menu_button("File", |ui| {
                if ui.button("New").clicked() {
                    self.new_document();
                    ui.close();
                }
                if ui.button("Open\u{2026}").clicked() {
                    self.open();
                    ui.close();
                }
                ui.separator();
                if ui.button("Save").clicked() {
                    self.save();
                    ui.close();
                }
                if ui.button("Save As\u{2026}").clicked() {
                    self.save_as();
                    ui.close();
                }
                ui.separator();
                if ui.button("Export SVG\u{2026}").clicked() {
                    self.export_svg();
                    ui.close();
                }
                ui.separator();
                if ui.button("Quit").clicked() {
                    ui.ctx().send_viewport_cmd(ViewportCommand::Close);
                }
            });
            ui.menu_button("Edit", |ui| {
                if ui.button("Undo").clicked() {
                    self.undo();
                    ui.close();
                }
                if ui.button("Redo").clicked() {
                    self.redo();
                    ui.close();
                }
                ui.separator();
                if ui.button("Cut").clicked() {
                    self.inject_event(ui.ctx(), Event::Cut);
                    ui.close();
                }
                if ui.button("Copy").clicked() {
                    self.inject_event(ui.ctx(), Event::Copy);
                    ui.close();
                }
                if ui.button("Paste").clicked() {
                    self.paste(ui.ctx());
                    ui.close();
                }
                if ui.button("Paste and Match Style").clicked() {
                    self.paste(ui.ctx());
                    ui.close();
                }
                if ui.button("Delete").clicked() {
                    self.inject_event(
                        ui.ctx(),
                        Event::Key {
                            key: Key::Delete,
                            physical_key: None,
                            pressed: true,
                            repeat: false,
                            modifiers: Modifiers::NONE,
                        },
                    );
                    ui.close();
                }
                ui.separator();
                if ui.button("Select All").clicked() {
                    self.inject_event(
                        ui.ctx(),
                        Event::Key {
                            key: Key::A,
                            physical_key: None,
                            pressed: true,
                            repeat: false,
                            modifiers: Modifiers::COMMAND,
                        },
                    );
                    ui.close();
                }
            });
            ui.menu_button("Preferences", |ui| {
                if ui.button("Settings\u{2026}").clicked() {
                    self.settings_open = true;
                    ui.close();
                }
            });
            ui.menu_button("View", |ui| {
                if ui.button("Reset Zoom").clicked() {
                    ui.ctx().set_zoom_factor(1.0);
                    ui.close();
                }
                if ui.button("Zoom In").clicked() {
                    let z = ui.ctx().zoom_factor();
                    ui.ctx().set_zoom_factor(z * 1.1);
                    ui.close();
                }
                if ui.button("Zoom Out").clicked() {
                    let z = ui.ctx().zoom_factor();
                    ui.ctx().set_zoom_factor(z * 0.9);
                    ui.close();
                }
                if ui.button("Toggle Fullscreen").clicked() {
                    let fs = ui.ctx().input(|i| i.viewport().fullscreen.unwrap_or(false));
                    ui.ctx().send_viewport_cmd(ViewportCommand::Fullscreen(!fs));
                    ui.close();
                }
                ui.separator();
                ui.menu_button("Windows", |ui| {
                    if ui.button("Editor").clicked() {
                        self.editor_open = true;
                        self.editor_dirty = true;
                        ui.close();
                    }
                });
            });
            ui.menu_button("Window", |ui| {
                if ui.button("Minimize").clicked() {
                    ui.ctx().send_viewport_cmd(ViewportCommand::Minimized(true));
                    ui.close();
                }
            });
        });
    }

    fn paste(&self, ctx: &Context) {
        if let Ok(mut clipboard) = arboard::Clipboard::new() {
            if let Ok(text) = clipboard.get_text() {
                self.inject_event(ctx, Event::Paste(text));
            }
        }
    }

    fn operations_panel(&mut self, ui: &mut egui::Ui) {
        let theme = self.theme();
        ui.add_space(4.0);
        ui.heading("Operations");
        ui.add_space(4.0);
        egui::Grid::new("palette")
            .num_columns(4)
            .spacing([6.0, 6.0])
            .show(ui, |ui| {
                for (i, kind) in model::PALETTE_TYPES.iter().enumerate() {
                    let (rect, resp) =
                        ui.allocate_exact_size(Vec2::splat(44.0), egui::Sense::click_and_drag());
                    paint::paint_icon(
                        ui.painter(),
                        rect,
                        *kind,
                        theme.gate_fill(),
                        theme.gate_label(),
                    );
                    if resp.drag_started() {
                        self.drag_type = Some(*kind);
                    }
                    resp.on_hover_text(display_name(*kind));
                    if i % 4 == 3 {
                        ui.end_row();
                    }
                }
            });
        ui.add_space(8.0);
        ui.separator();
        ui.add_space(4.0);
        ui.label("Number of qubits");
        let mut q = self.state.qubits;
        if ui
            .add(
                egui::DragValue::new(&mut q)
                    .range(model::MIN_QUBITS..=model::MAX_QUBITS)
                    .speed(0.1),
            )
            .changed()
        {
            self.set_qubits(q);
        }
        ui.add_space(4.0);
        ui.label(format!("Classical bits: {}", self.state.cbits));
    }

    fn settings_window(&mut self, ctx: &Context) {
        if !self.settings_open {
            return;
        }
        let mut open = true;
        egui::Window::new("Settings")
            .open(&mut open)
            .collapsible(false)
            .resizable(false)
            .show(ctx, |ui| {
                if ui.checkbox(&mut self.dark, "Dark mode").changed() {
                    // applied in update()
                }
                ui.horizontal(|ui| {
                    ui.label("Snapshot period (minutes)");
                    let mut m = self.snapshot_minutes;
                    if ui
                        .add(
                            egui::DragValue::new(&mut m)
                                .range(MIN_SNAPSHOT_MINUTES..=MAX_SNAPSHOT_MINUTES)
                                .speed(0.2),
                        )
                        .changed()
                    {
                        self.snapshot_minutes = m.clamp(MIN_SNAPSHOT_MINUTES, MAX_SNAPSHOT_MINUTES);
                        self.last_snapshot = Instant::now();
                    }
                });
                ui.add_space(4.0);
                if ui.button("Close").clicked() {
                    self.settings_open = false;
                }
            });
        if !open {
            self.settings_open = false;
        }
    }

    /// Close the editor when its OS window was dismissed by the user.
    fn sync_editor_state(&mut self) {
        let requested = self
            .shared
            .lock()
            .map(|s| s.close_requested)
            .unwrap_or(false);
        if requested {
            if let Ok(mut s) = self.shared.lock() {
                s.close_requested = false;
            }
            self.editor_open = false;
        }
    }

    fn editor_viewport(&mut self, ctx: &Context) {
        if !self.editor_open {
            return;
        }
        let shared = self.shared.clone();
        let theme = self.theme();
        ctx.show_viewport_deferred(
            self.editor_id,
            egui::ViewportBuilder::default()
                .with_title("Editor")
                .with_inner_size([560.0, 650.0])
                .with_min_inner_size([360.0, 300.0]),
            move |ctx, _class| {
                editor::ui(ctx, &shared, theme);
            },
        );
    }

    fn status_bar(&mut self, ctx: &Context) {
        egui::TopBottomPanel::bottom("status").show(ctx, |ui| {
            ui.horizontal(|ui| {
                match &self.document.file_path {
                    Some(p) => ui.label(format!("{}", p.display())),
                    None => ui.label("untitled"),
                };
                ui.separator();
                ui.label(format!("{} qubits", self.state.qubits));
                if let Some((msg, when)) = &self.status {
                    if when.elapsed() < Duration::from_secs(5) {
                        ui.separator();
                        ui.label(msg.clone());
                        ui.ctx().request_repaint_after(Duration::from_millis(200));
                    }
                }
            });
        });
    }
}

impl eframe::App for QcApp {
    fn update(&mut self, ctx: &Context, _frame: &mut eframe::Frame) {
        ctx.set_visuals(if self.dark {
            egui::Visuals::dark()
        } else {
            egui::Visuals::light()
        });

        self.apply_pending_editor();
        self.snapshot_tick();
        self.shortcuts(ctx);

        egui::TopBottomPanel::top("menu").show(ctx, |ui| {
            self.menu_bar(ui);
        });
        egui::SidePanel::left("operations")
            .resizable(true)
            .default_width(210.0)
            .show(ctx, |ui| {
                egui::ScrollArea::vertical().show(ui, |ui| {
                    self.operations_panel(ui);
                });
            });
        egui::CentralPanel::default().show(ctx, |ui| {
            canvas::show(self, ui);
        });
        self.status_bar(ctx);
        self.settings_window(ctx);
        self.editor_viewport(ctx);
        if self.editor_open && self.editor_dirty {
            ctx.request_repaint_of(self.editor_id);
            self.editor_dirty = false;
        }
        self.sync_editor_state();
    }

    fn save(&mut self, storage: &mut dyn eframe::Storage) {
        eframe::set_value(storage, DARK_PREF_KEY, &self.dark);
        eframe::set_value(storage, SNAPSHOT_PREF_KEY, &self.snapshot_minutes);
    }

    fn on_exit(&mut self, _gl: Option<&eframe::glow::Context>) {
        self.logger.info("application exit");
    }
}

/// Validate `text` and store the status string in shared state (FR-43).
pub fn set_status(shared: &mut EditorShared) {
    let result = grammar::validate(&shared.text);
    if result.ok {
        shared.status = "valid".to_string();
        shared.invalid = false;
    } else {
        let first = &result.errors[0];
        let more = result.errors.len() - 1;
        let suffix = if more > 0 {
            format!(" (+{} more)", more)
        } else {
            String::new()
        };
        shared.status = format!("invalid: line {}: {}{}", first.line, first.message, suffix);
        shared.invalid = true;
    }
}

fn display_name(kind: GateKind) -> &'static str {
    match kind {
        GateKind::H => "H",
        GateKind::X => "X",
        GateKind::Y => "Y",
        GateKind::Z => "Z",
        GateKind::S => "S",
        GateKind::T => "T",
        GateKind::Sdg => "S\u{2020}",
        GateKind::Tdg => "T\u{2020}",
        GateKind::Rx => "Rx",
        GateKind::Ry => "Ry",
        GateKind::Rz => "Rz",
        GateKind::P => "P",
        GateKind::Id => "ID",
        GateKind::Cx => "CX",
        GateKind::Ccx => "CCX",
        GateKind::Measure => "Measure",
        GateKind::Barrier => "Barrier",
        GateKind::Custom => "Custom",
    }
}

fn log_dir() -> PathBuf {
    if let Ok(dir) = std::env::var("QC_SCHEMATIC_LOG_DIR") {
        return PathBuf::from(dir);
    }
    #[cfg(target_os = "windows")]
    if let Ok(dir) = std::env::var("LOCALAPPDATA") {
        return PathBuf::from(dir).join("qc-schematic").join("logs");
    }
    #[cfg(target_os = "macos")]
    if let Ok(home) = std::env::var("HOME") {
        return PathBuf::from(home).join("Library/Logs/qc-schematic");
    }
    #[cfg(target_os = "linux")]
    if let Ok(home) = std::env::var("HOME") {
        return PathBuf::from(home).join(".local/state/qc-schematic");
    }
    std::env::temp_dir().join("qc-schematic")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn status_valid() {
        let mut s = EditorShared {
            text: "OPENQASM 3;\nqubit[1] q;\nh q[0];\n".into(),
            ..Default::default()
        };
        set_status(&mut s);
        assert_eq!("valid", s.status);
        assert!(!s.invalid);
    }

    #[test]
    fn status_invalid_with_more() {
        let mut s = EditorShared {
            text: "if (x) { y; }\nfor (i in [1:2]) { z; }".into(),
            ..Default::default()
        };
        set_status(&mut s);
        assert!(s.invalid);
        assert!(s.status.starts_with("invalid: line "));
    }
}
