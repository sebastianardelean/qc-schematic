//! Canvas rendering and direct manipulation. Port of the canvas parts of
//! `ui.cljs` (drag-to-place, target drag, double-click removal).

use egui::{CornerRadius, Pos2, Sense, Shape, Stroke, Vec2};
use qc_core::model::{self, target_cell, Gate, GateKind};

use crate::app::{QcApp, TargetDrag};
use crate::paint;

pub fn show(app: &mut QcApp, ui: &mut egui::Ui) {
    let (cw, ch) = model::canvas_size(&app.state);
    let avail = ui.available_size();
    let width = (cw as f32).max(avail.x);
    let height = (ch as f32).max(avail.y);
    let theme = app.theme();

    egui::ScrollArea::both()
        .auto_shrink([false, false])
        .scroll_source(egui::scroll_area::ScrollSource {
            scroll_bar: true,
            drag: false,
            mouse_wheel: true,
        })
        .show(ui, |ui| {
            let (rect, response) =
                ui.allocate_exact_size(Vec2::new(width, height), Sense::click_and_drag());
            let origin = rect.min;
            let painter = ui.painter_at(rect);
            painter.rect_filled(rect, CornerRadius::ZERO, theme.canvas_bg());

            let qubits = app.state.qubits;
            for w in 0..qubits {
                let y = origin.y + model::wire_y(w) as f32;
                painter.line_segment(
                    [
                        Pos2::new(origin.x + model::LINE_START as f32, y),
                        Pos2::new(rect.right(), y),
                    ],
                    Stroke::new(1.0, theme.wire()),
                );
                zero_marker(&painter, origin, w, theme.wire(), theme.gate_label());
            }
            if app.state.cbits > 0 {
                let y = origin.y + model::cwire_y(0, qubits) as f32;
                painter.add(Shape::dashed_line(
                    &[
                        Pos2::new(origin.x + model::LINE_START as f32, y),
                        Pos2::new(rect.right(), y),
                    ],
                    Stroke::new(1.0, theme.wire()),
                    4.0,
                    3.0,
                ));
            }

            for g in &app.state.gates {
                if g.kind == GateKind::Measure && app.state.cbits > 0 {
                    let x =
                        origin.x + model::gate_left(g.col) as f32 + model::GATE_SIZE as f32 / 2.0;
                    let y0 =
                        origin.y + model::wire_y(g.wire) as f32 + model::GATE_SIZE as f32 / 2.0;
                    let y1 = origin.y + model::cwire_y(0, qubits) as f32;
                    painter.add(Shape::dashed_line(
                        &[Pos2::new(x, y0), Pos2::new(x, y1)],
                        Stroke::new(1.5, paint::GATE_STROKE),
                        4.0,
                        3.0,
                    ));
                }
                paint::paint_gate(
                    &painter,
                    origin,
                    g,
                    theme.gate_label(),
                    theme.gate_fill(),
                    1.0,
                );
            }

            interactions(app, ui, &response, origin, rect);
        });
}

/// Draw the `|0>` ket marker at the left of a wire, reproducing the vector
/// geometry of `static/zero-state.svg` (46x46, centred on the wire).
fn zero_marker(
    painter: &egui::Painter,
    origin: Pos2,
    wire: i32,
    wire_color: egui::Color32,
    label: egui::Color32,
) {
    let cx = origin.x + model::ZERO_CENTER as f32;
    let cy = origin.y + model::wire_y(wire) as f32;
    let s = model::GATE_SIZE as f32 / 46.0;
    let p = |x: f32, y: f32| Pos2::new(cx + x * s, cy + y * s);
    let stroke = Stroke::new(2.0 * s, wire_color);
    // Vertical bar "|".
    painter.line_segment([p(-12.0, 17.0), p(-12.0, -17.0)], stroke);
    // Right angle bracket ">".
    painter.line_segment([p(2.0, 17.0), p(14.0, -0.5)], stroke);
    painter.line_segment([p(2.0, -17.0), p(14.0, 0.5)], stroke);
    // Digit "0" (SVG: bold 28px, baseline at y=10, left at x=-9.5).
    painter.text(
        p(-2.0, 0.0),
        egui::Align2::CENTER_CENTER,
        "0",
        egui::FontId::proportional(26.0 * s),
        label,
    );
}

fn interactions(
    app: &mut QcApp,
    ui: &egui::Ui,
    response: &egui::Response,
    origin: Pos2,
    rect: egui::Rect,
) {
    let ctx = ui.ctx().clone();
    let pointer = ctx.input(|i| i.pointer.latest_pos());
    let released = ctx.input(|i| i.pointer.any_released());
    let pressed = ctx.input(|i| i.pointer.any_pressed());
    let primary_down = ctx.input(|i| i.pointer.primary_down());
    let theme = app.theme();
    let painter = ui.painter_at(rect);

    // 1. Dragging a palette gate: preview and drop (FR-15, FR-16).
    if let Some(kind) = app.drag_type {
        let over = pointer.filter(|p| rect.contains(*p));
        let mut pending = None;
        if released {
            if let Some(pos) = over {
                let local = pos - origin;
                pending = target_cell(&app.state, kind, local.x, local.y);
            }
            app.drag_type = None;
        } else if let Some(pos) = over {
            let local = pos - origin;
            if let Some(cell) = target_cell(&app.state, kind, local.x, local.y) {
                paint::paint_gate(
                    &painter,
                    origin,
                    &cell,
                    theme.gate_label(),
                    theme.gate_fill(),
                    0.5,
                );
            }
        }
        if let Some(cell) = pending {
            app.add_gate(cell);
        }
        return;
    }

    // 2. Dragging a CX/CCX target (FR-18).
    if let Some(td) = app.target_drag.clone() {
        if primary_down {
            if let Some(pos) = pointer {
                let local = pos - origin;
                let wire = model::snap_wire(local.y, app.state.qubits);
                if !td.controls.contains(&wire) && (0..app.state.qubits).contains(&wire) {
                    if let Some(active) = app.target_drag.as_mut() {
                        active.wire = wire;
                    }
                }
            }
        }
        // Highlight the dragged target.
        if let Some(active) = app.target_drag.as_ref() {
            let c = Pos2::new(
                origin.x
                    + model::gate_left(gate_col(app, active.id)) as f32
                    + model::GATE_SIZE as f32 / 2.0,
                origin.y + model::wire_y(active.wire) as f32,
            );
            paint::paint_gate(
                &painter,
                origin,
                &synthetic(app, active),
                theme.gate_label(),
                theme.gate_fill(),
                0.5,
            );
            let _ = c;
        }
        if released {
            commit_target(app);
        }
        return;
    }

    // 3. Starting a target drag.
    if pressed {
        if let Some(pos) = pointer {
            if rect.contains(pos) {
                if let Some((id, kind, controls, wire)) = hit_target(app, pos - origin) {
                    app.target_drag = Some(TargetDrag {
                        id,
                        kind,
                        controls,
                        wire,
                    });
                }
            }
        }
    }

    // 4. Double-click removal (FR-17).
    if response.double_clicked() {
        if let Some(pos) = response.interact_pointer_pos() {
            if let Some(id) = hit_gate(app, pos - origin) {
                app.remove_gate(id);
            }
        }
    }
}

fn gate_col(app: &QcApp, id: u64) -> i32 {
    app.state
        .gates
        .iter()
        .find(|g| g.id == id)
        .map(|g| g.col)
        .unwrap_or(0)
}

fn synthetic(app: &QcApp, td: &TargetDrag) -> Gate {
    let gate = app.state.gates.iter().find(|g| g.id == td.id);
    let col = gate.map(|g| g.col).unwrap_or(0);
    let mut g = Gate::new(td.kind, td.controls[0], col);
    g.target = Some(td.wire);
    if td.kind == GateKind::Ccx {
        g.control = td.controls.get(1).copied();
    }
    g
}

/// Find the CX/CCX target symbol under `local`, if any.
fn hit_target(app: &QcApp, local: Vec2) -> Option<(u64, GateKind, Vec<i32>, i32)> {
    for g in app.state.gates.iter().rev() {
        if !matches!(g.kind, GateKind::Cx | GateKind::Ccx) {
            continue;
        }
        let target = g.target.unwrap_or(g.wire);
        let cx = model::gate_left(g.col) as f32 + model::GATE_SIZE as f32 / 2.0;
        let cy = model::wire_y(target) as f32;
        if (local.x - cx).abs() <= model::GATE_SIZE as f32 / 2.0
            && (local.y - cy).abs() <= model::GATE_SIZE as f32 / 2.0
        {
            let controls = if g.kind == GateKind::Ccx {
                vec![g.wire, g.control.unwrap_or(g.wire)]
            } else {
                vec![g.wire]
            };
            return Some((g.id, g.kind, controls, target));
        }
    }
    None
}

/// Find the topmost gate whose bounding box contains `local`.
fn hit_gate(app: &QcApp, local: Vec2) -> Option<u64> {
    let origin = Pos2::ZERO;
    for g in app.state.gates.iter().rev() {
        let r = paint::gate_rect(origin, g);
        if r.contains(origin + local) {
            return Some(g.id);
        }
    }
    None
}

fn commit_target(app: &mut QcApp) {
    let Some(td) = app.target_drag.take() else {
        return;
    };
    let Some(gate) = app.state.gates.iter().find(|g| g.id == td.id).cloned() else {
        return;
    };
    let others: Vec<Gate> = app
        .state
        .gates
        .iter()
        .filter(|g| g.id != td.id)
        .cloned()
        .collect();
    let mut target = model::nearest_free_wire(
        &others,
        gate.col,
        td.wire,
        Some(&td.controls),
        app.state.qubits,
    );
    if td.controls.contains(&target) {
        target = gate.target.unwrap_or(target);
    }
    app.undo.save(&app.state);
    app.undo.clear_future();
    for g in app.state.gates.iter_mut() {
        if g.id == td.id {
            g.target = Some(target);
        }
    }
    app.render_code();
}
