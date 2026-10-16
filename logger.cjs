// Minimal, dependency-free rotating file logger for the Electron main process.
//
// It is plain Node (no Electron imports) so it can be unit-tested outside of a
// running application. Each logger writes to a single file and rotates it once
// it reaches `maxBytes`, keeping a bounded number of numbered backups.
//
// Logging must never take the application down, so every filesystem error is
// swallowed: if the log file cannot be written the application keeps running.

"use strict";

const fs = require("node:fs");
const path = require("node:path");

const DEFAULT_MAX_BYTES = 5 * 1024 * 1024; // 5 MB
const DEFAULT_BACKUPS = 1;
const LOG_FILE_NAME = "qc-schematic.log";

function serialize(value) {
  if (value instanceof Error) {
    return JSON.stringify({
      name: value.name,
      message: value.message,
      stack: value.stack
    });
  }
  if (typeof value === "string") return JSON.stringify(value);
  try {
    const json = JSON.stringify(value);
    return json === undefined ? String(value) : json;
  } catch {
    return String(value);
  }
}

function formatLine(level, message, meta, date) {
  const suffix = meta === undefined ? "" : ` ${serialize(meta)}`;
  return `${date.toISOString()} [${level}] ${message}${suffix}\n`;
}

function createLogger(options = {}) {
  const filePath = options.filePath;
  const maxBytes =
    Number.isFinite(options.maxBytes) && options.maxBytes > 0
      ? options.maxBytes
      : DEFAULT_MAX_BYTES;
  const backups =
    Number.isInteger(options.backups) && options.backups >= 0
      ? options.backups
      : DEFAULT_BACKUPS;
  const now = typeof options.now === "function" ? options.now : () => new Date();

  let initialized = false;

  function ensureDirectory() {
    if (initialized) return;
    if (!filePath) throw new Error("logger: a filePath is required");
    fs.mkdirSync(path.dirname(filePath), { recursive: true });
    initialized = true;
  }

  function currentSize() {
    try {
      return fs.statSync(filePath).size;
    } catch {
      return 0;
    }
  }

  function backupPath(index) {
    return `${filePath}.${index}`;
  }

  // Rotate before writing so the active file never grows unbounded. With
  // `backups` set, the oldest backup is dropped and the rest shift down.
  function rotate() {
    if (backups < 1) {
      fs.rmSync(filePath, { force: true });
      return;
    }
    fs.rmSync(backupPath(backups), { force: true });
    for (let index = backups - 1; index >= 1; index -= 1) {
      const source = backupPath(index);
      if (fs.existsSync(source)) fs.renameSync(source, backupPath(index + 1));
    }
    if (fs.existsSync(filePath)) fs.renameSync(filePath, backupPath(1));
  }

  function write(level, message, meta) {
    try {
      ensureDirectory();
      const line = formatLine(level, message, meta, now());
      // Rotate before appending when the new line would push the active file
      // over the limit. A single oversized line is still written so logging
      // never silently drops an entry.
      if (currentSize() + Buffer.byteLength(line) > maxBytes && currentSize() > 0) {
        rotate();
      }
      fs.appendFileSync(filePath, line);
    } catch {
      // Logging is best-effort and must not crash the application.
    }
  }

  return {
    filePath,
    maxBytes,
    backups,
    rotate,
    debug: (message, meta) => write("DEBUG", message, meta),
    info: (message, meta) => write("INFO", message, meta),
    warn: (message, meta) => write("WARN", message, meta),
    error: (message, meta) => write("ERROR", message, meta)
  };
}

module.exports = {
  createLogger,
  LOG_FILE_NAME,
  DEFAULT_MAX_BYTES,
  DEFAULT_BACKUPS
};
