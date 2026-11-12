//! The OpenQASM editor viewport. Port of `editor.cljs`: a monospace editor with
//! a line-number gutter, live validation status and debounced apply-back.

use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use eframe::egui;
use egui::{Color32, Context, RichText, TextWrapMode};

use crate::app::{set_status, EditorShared, Theme};

const DEBOUNCE: Duration = Duration::from_millis(250);
const INVALID_COLOR: Color32 = Color32::from_rgb(0xd1, 0x5b, 0x5b);

pub fn ui(ctx: &Context, shared: &Arc<Mutex<EditorShared>>, theme: Theme) {
    ctx.set_visuals(if theme.dark {
        egui::Visuals::dark()
    } else {
        egui::Visuals::light()
    });

    if ctx.input(|i| i.viewport().close_requested()) {
        if let Ok(mut s) = shared.lock() {
            s.close_requested = true;
        }
        ctx.request_repaint_of(egui::ViewportId::ROOT);
    }

    egui::CentralPanel::default().show(ctx, |ui| {
        let (mut text, invalid, status) = {
            let s = shared.lock().unwrap();
            (s.text.clone(), s.invalid, s.status.clone())
        };

        ui.horizontal(|ui| {
            ui.heading("Editor");
            let color = if invalid {
                INVALID_COLOR
            } else {
                ui.visuals().weak_text_color()
            };
            ui.colored_label(color, status);
        });
        ui.separator();

        let text_color = if invalid {
            INVALID_COLOR
        } else {
            ui.visuals().text_color()
        };

        let mut changed = false;
        egui::ScrollArea::both()
            .auto_shrink([false, false])
            .show(ui, |ui| {
                ui.horizontal_top(|ui| {
                    let lines = text.matches('\n').count() + 1;
                    let nums: String = (1..=lines)
                        .map(|n| n.to_string())
                        .collect::<Vec<_>>()
                        .join("\n");
                    ui.add(
                        egui::Label::new(RichText::new(nums).monospace())
                            .wrap_mode(TextWrapMode::Extend),
                    );
                    let resp = ui.add(
                        egui::TextEdit::multiline(&mut text)
                            .code_editor()
                            .desired_width(f32::INFINITY)
                            .desired_rows(lines)
                            .text_color(text_color),
                    );
                    if resp.changed() {
                        changed = true;
                    }
                });
            });

        {
            let mut s = shared.lock().unwrap();
            if changed {
                s.text = text.clone();
                s.last_edit = Some(Instant::now());
            }
            set_status(&mut s);
            let focused = ctx.input(|i| i.focused);
            if let Some(t) = s.last_edit {
                if !focused || t.elapsed() >= DEBOUNCE {
                    s.pending_apply = Some(s.text.clone());
                    s.last_edit = None;
                    ctx.request_repaint_of(egui::ViewportId::ROOT);
                }
            }
            if s.last_edit.is_some() {
                ctx.request_repaint_after(Duration::from_millis(50));
            }
        }
    });
}
