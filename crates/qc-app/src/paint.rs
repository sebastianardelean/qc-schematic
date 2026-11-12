//! Vector painting of gates, wires and symbols. The geometry mirrors
//! `export.rs` so the canvas and the SVG export agree (FR-9, DATA-4).

use egui::{Align2, Color32, CornerRadius, FontId, Painter, Pos2, Rect, Shape, Stroke, StrokeKind};
use qc_core::model::{self, cx_geometry, gate_span, Gate, GateKind};

pub const GATE_COLOR: Color32 = Color32::from_rgb(0x1f, 0x29, 0x33);
pub const GATE_STROKE: Color32 = Color32::from_rgb(0x3a, 0x4a, 0x5b);
pub const WIRE_COLOR: Color32 = Color32::from_rgb(0x9a, 0xa5, 0xb1);

pub fn label(kind: GateKind, name: Option<&str>) -> String {
    match kind {
        GateKind::Custom => name.unwrap_or_default().to_string(),
        GateKind::Id => "I".to_string(),
        GateKind::Rx => "Rx".to_string(),
        GateKind::Ry => "Ry".to_string(),
        GateKind::Rz => "Rz".to_string(),
        GateKind::P => "P".to_string(),
        GateKind::Measure => "M".to_string(),
        _ => kind.qasm_name().to_uppercase(),
    }
}

fn gate_size() -> f32 {
    model::GATE_SIZE as f32
}

/// Bounding rectangle of a gate on the canvas.
pub fn gate_rect(origin: Pos2, gate: &Gate) -> Rect {
    let size = gate_size();
    let x = origin.x + model::gate_left(gate.col) as f32;
    match gate.kind {
        GateKind::Cx => {
            let g = cx_geometry(gate.wire, gate.target.unwrap_or(gate.wire));
            let top = origin.y + model::wire_y(g.above) as f32 - size / 2.0;
            Rect::from_min_size(Pos2::new(x, top), egui::vec2(size, g.span as f32 + size))
        }
        GateKind::Ccx => {
            let span = gate_span(gate);
            let top = origin.y + model::wire_y(span.above) as f32 - size / 2.0;
            Rect::from_min_size(Pos2::new(x, top), egui::vec2(size, span.span as f32 + size))
        }
        GateKind::Custom | GateKind::Barrier => {
            let (above, below) = span_bounds(&gate.wires);
            let top = origin.y + model::wire_y(above) as f32 - size / 2.0;
            let height = (model::wire_y(below) - model::wire_y(above)) as f32 + size;
            Rect::from_min_size(Pos2::new(x, top), egui::vec2(size, height))
        }
        _ => {
            let top = origin.y + model::gate_top(gate.wire) as f32;
            Rect::from_min_size(Pos2::new(x, top), egui::vec2(size, size))
        }
    }
}

fn span_bounds(wires: &[i32]) -> (i32, i32) {
    let above = wires.iter().copied().min().unwrap_or(0);
    let below = wires.iter().copied().max().unwrap_or(0);
    (above, below)
}

fn stroke(color: Color32, width: f32) -> Stroke {
    Stroke::new(width, color)
}

/// Draw a placed gate.
pub fn paint_gate(
    painter: &Painter,
    origin: Pos2,
    gate: &Gate,
    label_color: Color32,
    fill: Color32,
    opacity: f32,
) {
    let a = |c: Color32| c.gamma_multiply(opacity);
    let size = gate_size();
    let x = origin.x + model::gate_left(gate.col) as f32;
    let cx = x + size / 2.0;
    let lc = a(label_color);
    match gate.kind {
        GateKind::Cx => {
            let g = cx_geometry(gate.wire, gate.target.unwrap_or(gate.wire));
            let y1 = origin.y + model::wire_y(g.above) as f32;
            let y2 = origin.y + model::wire_y(g.below) as f32;
            painter.line_segment([Pos2::new(cx, y1), Pos2::new(cx, y2)], stroke(lc, 2.5));
            control_dot(
                painter,
                Pos2::new(cx, origin.y + model::wire_y(gate.wire) as f32),
                lc,
            );
            let ty = origin.y + model::wire_y(gate.target.unwrap_or(gate.wire)) as f32;
            target_cross(painter, Pos2::new(cx, ty), lc);
        }
        GateKind::Ccx => {
            let span = gate_span(gate);
            let y1 = origin.y + model::wire_y(span.above) as f32;
            let y2 = origin.y + model::wire_y(span.below) as f32;
            painter.line_segment([Pos2::new(cx, y1), Pos2::new(cx, y2)], stroke(lc, 2.5));
            control_dot(
                painter,
                Pos2::new(cx, origin.y + model::wire_y(gate.wire) as f32),
                lc,
            );
            if let Some(c) = gate.control {
                control_dot(
                    painter,
                    Pos2::new(cx, origin.y + model::wire_y(c) as f32),
                    lc,
                );
            }
            let ty = origin.y + model::wire_y(gate.target.unwrap_or(gate.wire)) as f32;
            target_cross(painter, Pos2::new(cx, ty), lc);
        }
        GateKind::Measure => {
            let cy = origin.y + model::wire_y(gate.wire) as f32;
            let r = Rect::from_center_size(Pos2::new(cx, cy), egui::vec2(44.0, 44.0));
            painter.rect_stroke(
                r,
                CornerRadius::same(6),
                stroke(lc, 2.0),
                StrokeKind::Inside,
            );
            measure_symbol(painter, Pos2::new(cx, cy), lc);
        }
        GateKind::Custom => {
            let r = gate_rect(origin, gate);
            painter.rect_filled(r, CornerRadius::same(6), a(fill));
            painter.rect_stroke(
                r,
                CornerRadius::same(6),
                stroke(a(GATE_STROKE), 2.0),
                StrokeKind::Inside,
            );
            painter.text(
                r.center(),
                Align2::CENTER_CENTER,
                label(gate.kind, gate.name.as_deref()),
                FontId::proportional(14.0),
                lc,
            );
        }
        GateKind::Barrier => {
            let span = gate_span(gate);
            let y1 = origin.y + model::wire_y(span.above) as f32 - size / 2.0;
            let y2 = origin.y + model::wire_y(span.below) as f32 + size / 2.0;
            painter.add(Shape::dashed_line(
                &[Pos2::new(cx, y1), Pos2::new(cx, y2)],
                stroke(lc, 2.0),
                6.0,
                4.0,
            ));
        }
        _ => {
            let r = gate_rect(origin, gate);
            painter.rect_filled(r, CornerRadius::same(6), a(fill));
            painter.rect_stroke(
                r,
                CornerRadius::same(6),
                stroke(a(GATE_STROKE), 2.0),
                StrokeKind::Inside,
            );
            painter.text(
                r.center(),
                Align2::CENTER_CENTER,
                label(gate.kind, gate.name.as_deref()),
                FontId::proportional(18.0),
                lc,
            );
        }
    }
}

fn control_dot(painter: &Painter, c: Pos2, color: Color32) {
    painter.circle_filled(c, 10.0, color);
}

fn target_cross(painter: &Painter, c: Pos2, color: Color32) {
    painter.circle_stroke(c, 11.0, stroke(color, 2.5));
    painter.line_segment(
        [Pos2::new(c.x, c.y - 15.0), Pos2::new(c.x, c.y + 15.0)],
        stroke(color, 2.5),
    );
    painter.line_segment(
        [Pos2::new(c.x - 15.0, c.y), Pos2::new(c.x + 15.0, c.y)],
        stroke(color, 2.5),
    );
}

fn measure_symbol(painter: &Painter, c: Pos2, color: Color32) {
    let mut pts = Vec::new();
    let steps = 16;
    for i in 0..=steps {
        let t = std::f32::consts::PI * (i as f32 / steps as f32);
        pts.push(Pos2::new(c.x - 13.0 * t.cos(), c.y + 9.0 - 13.0 * t.sin()));
    }
    painter.add(Shape::line(pts, stroke(color, 2.5)));
    painter.line_segment(
        [Pos2::new(c.x, c.y + 9.0), Pos2::new(c.x - 8.0, c.y + 1.0)],
        stroke(color, 2.5),
    );
    painter.circle_filled(Pos2::new(c.x, c.y + 9.0), 2.0, color);
}

/// Draw a self-contained palette/legend symbol for `kind` inside `rect`.
pub fn paint_icon(
    painter: &Painter,
    rect: Rect,
    kind: GateKind,
    fill: Color32,
    label_color: Color32,
) {
    let c = rect.center();
    match kind {
        GateKind::Cx | GateKind::Ccx => {
            let n = if kind == GateKind::Cx { 2 } else { 3 };
            let top = rect.top() + 8.0;
            let bottom = rect.bottom() - 8.0;
            painter.line_segment(
                [Pos2::new(c.x, top), Pos2::new(c.x, bottom)],
                stroke(label_color, 2.0),
            );
            control_dot(painter, Pos2::new(c.x, top), label_color);
            if n == 3 {
                control_dot(painter, Pos2::new(c.x, c.y), label_color);
            }
            target_cross(painter, Pos2::new(c.x, bottom), label_color);
        }
        GateKind::Measure => {
            let r = Rect::from_center_size(c, egui::vec2(40.0, 40.0));
            painter.rect_stroke(
                r,
                CornerRadius::same(6),
                stroke(label_color, 2.0),
                StrokeKind::Inside,
            );
            measure_symbol(painter, c, label_color);
        }
        GateKind::Barrier => {
            painter.add(Shape::dashed_line(
                &[
                    Pos2::new(c.x, rect.top() + 4.0),
                    Pos2::new(c.x, rect.bottom() - 4.0),
                ],
                stroke(label_color, 2.0),
                6.0,
                4.0,
            ));
        }
        _ => {
            let r = Rect::from_center_size(c, egui::vec2(38.0, 38.0));
            painter.rect_filled(r, CornerRadius::same(6), fill);
            painter.rect_stroke(
                r,
                CornerRadius::same(6),
                stroke(GATE_STROKE, 2.0),
                StrokeKind::Inside,
            );
            painter.text(
                c,
                Align2::CENTER_CENTER,
                label(kind, None),
                FontId::proportional(16.0),
                label_color,
            );
        }
    }
}
