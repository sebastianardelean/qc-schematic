//! Native file dialogs (via `rfd`) and snapshot path helpers. Port of the
//! dialog handlers in `main.cjs`.

use std::path::{Path, PathBuf};

/// The remembered save path for the current document, plus its autosave
/// snapshot path.
#[derive(Clone, Debug, Default)]
pub struct Document {
    pub file_path: Option<PathBuf>,
}

impl Document {
    pub fn snapshot_path(&self) -> Option<PathBuf> {
        self.file_path.as_deref().map(snapshot_path_for)
    }

    /// Record a save at `path`, returning the snapshot path that must be
    /// removed (the new path) and, for Save As, the previous path's snapshot.
    pub fn set_saved(&mut self, path: PathBuf) -> (Option<PathBuf>, Option<PathBuf>) {
        let previous = self.file_path.replace(path.clone());
        let remove_new = Some(snapshot_path_for(&path));
        let remove_previous = previous
            .filter(|p| *p != path)
            .map(|p| snapshot_path_for(&p));
        (remove_new, remove_previous)
    }
}

/// `dir/.<basename>.autosave.qasm`
pub fn snapshot_path_for(file_path: &Path) -> PathBuf {
    let name = file_path
        .file_name()
        .map(|n| n.to_string_lossy().to_string())
        .unwrap_or_default();
    let dir = file_path.parent().unwrap_or_else(|| Path::new("."));
    dir.join(format!(".{}.autosave.qasm", name))
}

pub fn remove_file(path: &Path) {
    let _ = std::fs::remove_file(path);
}

const QASM_FILTERS: &[(&str, &[&str])] = &[
    ("OpenQASM", &["qasm", "qasm3", "inc"]),
    ("All Files", &["*"]),
];

const QASM_SAVE_FILTERS: &[(&str, &[&str])] = &[("OpenQASM", &["qasm"]), ("All Files", &["*"])];

const SVG_SAVE_FILTERS: &[(&str, &[&str])] = &[("SVG", &["svg"]), ("All Files", &["*"])];

/// Show an open dialog for OpenQASM files. Returns the chosen path.
pub fn pick_open_qasm() -> Option<PathBuf> {
    let mut dialog = rfd::FileDialog::new().set_title("Open OpenQASM");
    for (name, exts) in QASM_FILTERS {
        dialog = dialog.add_filter(*name, exts);
    }
    dialog.pick_file()
}

/// Show a save dialog for OpenQASM. `default_path` is remembered if provided.
pub fn pick_save_qasm(default_path: Option<&Path>) -> Option<PathBuf> {
    let mut dialog = rfd::FileDialog::new()
        .set_title("Save OpenQASM")
        .set_file_name(
            default_path
                .and_then(|p| p.file_name())
                .map(|n| n.to_string_lossy().to_string())
                .unwrap_or_else(|| "circuit.qasm".to_string()),
        );
    for (name, exts) in QASM_SAVE_FILTERS {
        dialog = dialog.add_filter(*name, exts);
    }
    dialog.save_file()
}

/// Show a save dialog for an SVG export.
pub fn pick_save_svg() -> Option<PathBuf> {
    let mut dialog = rfd::FileDialog::new()
        .set_title("Export SVG")
        .set_file_name("circuit.svg");
    for (name, exts) in SVG_SAVE_FILTERS {
        dialog = dialog.add_filter(*name, exts);
    }
    dialog.save_file()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn snapshot_path_shape() {
        let p = PathBuf::from("/tmp/dir/circuit.qasm");
        assert_eq!(
            PathBuf::from("/tmp/dir/.circuit.qasm.autosave.qasm"),
            snapshot_path_for(&p)
        );
    }

    #[test]
    fn save_removes_new_snapshot() {
        let mut doc = Document::default();
        let (remove_new, remove_prev) = doc.set_saved(PathBuf::from("/tmp/a.qasm"));
        assert_eq!(
            Some(PathBuf::from("/tmp/.a.qasm.autosave.qasm")),
            remove_new
        );
        assert_eq!(None, remove_prev);
    }

    #[test]
    fn save_as_removes_previous_snapshot() {
        let mut doc = Document {
            file_path: Some(PathBuf::from("/tmp/a.qasm")),
        };
        let (remove_new, remove_prev) = doc.set_saved(PathBuf::from("/tmp/b.qasm"));
        assert_eq!(
            Some(PathBuf::from("/tmp/.b.qasm.autosave.qasm")),
            remove_new
        );
        assert_eq!(
            Some(PathBuf::from("/tmp/.a.qasm.autosave.qasm")),
            remove_prev
        );
    }

    #[test]
    fn saving_same_path_does_not_remove_previous() {
        let mut doc = Document {
            file_path: Some(PathBuf::from("/tmp/a.qasm")),
        };
        let (_, remove_prev) = doc.set_saved(PathBuf::from("/tmp/a.qasm"));
        assert_eq!(None, remove_prev);
    }
}
