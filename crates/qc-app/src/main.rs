//! QC Schematic: an egui port of the ClojureScript/Electron application.

mod app;
mod canvas;
mod editor;
mod files;
mod logging;
mod paint;

fn main() -> eframe::Result<()> {
    let options = eframe::NativeOptions {
        viewport: eframe::egui::ViewportBuilder::default()
            .with_title("QC Schematic")
            .with_inner_size([1100.0, 720.0])
            .with_min_inner_size([640.0, 420.0]),
        ..Default::default()
    };
    eframe::run_native(
        "QC Schematic",
        options,
        Box::new(|cc| Ok(Box::new(app::QcApp::new(cc)))),
    )
}
