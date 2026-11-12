//! Embeds a Windows version resource (and application icon) into the binary.
//!
//! The version strings are taken from the crate version (`Cargo.toml`), so the
//! installer's `GetVersionNumbersString` lookup stays in sync automatically.

fn main() {
    #[cfg(windows)]
    {
        let icon = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("..")
            .join("..")
            .join("build")
            .join("qc-schematic.ico");
        println!("cargo:rerun-if-changed={}", icon.display());

        let mut res = winresource::WindowsResource::new();
        res.set_icon(&icon.to_string_lossy());
        res.set("ProductName", "QC Schematic");
        res.set("FileDescription", "Quantum circuit schematic editor");
        res.set("OriginalFilename", "qc-schematic.exe");
        res.set("LegalCopyright", "GPL-3.0-or-later");
        if let Err(e) = res.compile() {
            panic!("failed to compile Windows resources: {e}");
        }
    }
}
