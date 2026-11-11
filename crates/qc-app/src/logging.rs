//! Best-effort rotating file logger. Port of `logger.cjs`.
//!
//! Writes plain-text lines to a single file and rotates it once it reaches
//! `max_bytes`, keeping a bounded number of numbered backups. Every filesystem
//! error is swallowed so logging can never take the application down.

use std::fs::{self, OpenOptions};
use std::io::Write;
use std::path::{Path, PathBuf};
use std::time::{SystemTime, UNIX_EPOCH};

pub const LOG_FILE_NAME: &str = "qc-schematic.log";
pub const DEFAULT_MAX_BYTES: u64 = 5 * 1024 * 1024;
pub const DEFAULT_BACKUPS: usize = 1;

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Level {
    Debug,
    Info,
    Warn,
    Error,
}

impl Level {
    fn label(self) -> &'static str {
        match self {
            Level::Debug => "DEBUG",
            Level::Info => "INFO",
            Level::Warn => "WARN",
            Level::Error => "ERROR",
        }
    }
}

pub struct Logger {
    file_path: Option<PathBuf>,
    max_bytes: u64,
    backups: usize,
}

impl Logger {
    pub fn new(file_path: Option<PathBuf>) -> Self {
        Logger {
            file_path,
            max_bytes: DEFAULT_MAX_BYTES,
            backups: DEFAULT_BACKUPS,
        }
    }

    pub fn log(&self, level: Level, message: &str, meta: Option<&str>) {
        let _ = self.try_log(level, message, meta);
    }

    pub fn debug(&self, message: &str) {
        self.log(Level::Debug, message, None);
    }
    pub fn info(&self, message: &str) {
        self.log(Level::Info, message, None);
    }
    pub fn warn(&self, message: &str) {
        self.log(Level::Warn, message, None);
    }
    #[allow(dead_code)]
    pub fn error(&self, message: &str) {
        self.log(Level::Error, message, None);
    }

    pub fn info_meta(&self, message: &str, meta: &str) {
        self.log(Level::Info, message, Some(meta));
    }

    pub fn error_meta(&self, message: &str, meta: &str) {
        self.log(Level::Error, message, Some(meta));
    }

    fn try_log(&self, level: Level, message: &str, meta: Option<&str>) -> std::io::Result<()> {
        let path = match &self.file_path {
            Some(p) => p,
            None => return Ok(()),
        };
        if let Some(dir) = path.parent() {
            fs::create_dir_all(dir)?;
        }
        let line = format_line(level, message, meta, SystemTime::now());
        let size = fs::metadata(path).map(|m| m.len()).unwrap_or(0);
        if size + line.len() as u64 > self.max_bytes && size > 0 {
            self.rotate(path);
        }
        let mut file = OpenOptions::new().create(true).append(true).open(path)?;
        file.write_all(line.as_bytes())
    }

    fn rotate(&self, path: &Path) {
        let backup = |i: usize| PathBuf::from(format!("{}.{}", path.display(), i));
        if self.backups < 1 {
            let _ = fs::remove_file(path);
            return;
        }
        let _ = fs::remove_file(backup(self.backups));
        for index in (1..self.backups).rev() {
            let source = backup(index);
            if source.exists() {
                let _ = fs::rename(&source, backup(index + 1));
            }
        }
        if path.exists() {
            let _ = fs::rename(path, backup(1));
        }
    }
}

fn format_line(level: Level, message: &str, meta: Option<&str>, now: SystemTime) -> String {
    let suffix = meta.map(|m| format!(" {}", m)).unwrap_or_default();
    format!(
        "{} [{}] {}{}\n",
        iso8601(now),
        level.label(),
        message,
        suffix
    )
}

/// Format a `SystemTime` as an ISO-8601 UTC timestamp (seconds precision).
pub fn iso8601(time: SystemTime) -> String {
    let secs = time
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0) as i64;
    let days = secs.div_euclid(86_400);
    let rem = secs.rem_euclid(86_400);
    let (year, month, day) = civil_from_days(days);
    let hour = rem / 3600;
    let minute = (rem % 3600) / 60;
    let second = rem % 60;
    format!(
        "{:04}-{:02}-{:02}T{:02}:{:02}:{:02}.000Z",
        year, month, day, hour, minute, second
    )
}

/// Howard Hinnant's days-from-civil inverse.
fn civil_from_days(z: i64) -> (i64, u32, u32) {
    let z = z + 719_468;
    let era = if z >= 0 { z } else { z - 146_096 } / 146_097;
    let doe = z - era * 146_097;
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let y = yoe + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = (doy - (153 * mp + 2) / 5 + 1) as u32;
    let m = if mp < 10 { mp + 3 } else { mp - 9 } as u32;
    (if m <= 2 { y + 1 } else { y }, m, d)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn iso8601_epoch() {
        assert_eq!("1970-01-01T00:00:00.000Z", iso8601(UNIX_EPOCH));
    }

    #[test]
    fn iso8601_known_date() {
        let t = UNIX_EPOCH + std::time::Duration::from_secs(1_700_000_000);
        assert_eq!("2023-11-14T22:13:20.000Z", iso8601(t));
    }

    #[test]
    fn rotates_and_keeps_backup() {
        let dir = std::env::temp_dir().join(format!("qc-log-{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        let path = dir.join(LOG_FILE_NAME);
        let logger = Logger {
            file_path: Some(path.clone()),
            max_bytes: 40,
            backups: 1,
        };
        for _ in 0..10 {
            logger.info("a fairly long log line to force rotation");
        }
        assert!(path.exists());
        assert!(dir.join(format!("{}.1", LOG_FILE_NAME)).exists());
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn write_failure_is_swallowed() {
        let logger = Logger {
            file_path: Some(PathBuf::from("\0invalid\0path")),
            max_bytes: 10,
            backups: 1,
        };
        logger.info("this must not panic");
    }
}
