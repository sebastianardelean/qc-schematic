//! Pure domain logic for QC Schematic: circuit model, geometry, OpenQASM 3
//! generation/parsing, grammar validation, and SVG export.
//!
//! This crate has no GUI or operating-system dependencies so that it can be
//! tested headlessly and reused by the egui application in `qc-app`.

pub mod export;
pub mod grammar;
pub mod model;
pub mod qasm;
