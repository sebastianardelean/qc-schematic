// Unit tests for logger.cjs. Pure Node, no Electron required.
//
// Run with: npm run test:logger   (node test/system/logger-test.cjs)

"use strict";

const assert = require("node:assert");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");

const { createLogger, LOG_FILE_NAME } = require("../../logger.cjs");

const results = [];

function check(name, fn) {
  try {
    fn();
    results.push({ name, ok: true });
  } catch (error) {
    results.push({ name, ok: false, detail: error && error.message });
  }
}

function tempDir() {
  return fs.mkdtempSync(path.join(os.tmpdir(), "qc-logger-"));
}

function read(filePath) {
  return fs.readFileSync(filePath, "utf8");
}

// 1. A single line is written with timestamp, level and message.
check("writes a formatted line to the log file", () => {
  const dir = tempDir();
  const filePath = path.join(dir, LOG_FILE_NAME);
  const logger = createLogger({ filePath, now: () => new Date("2026-01-01T00:00:00.000Z") });
  logger.info("hello", { a: 1 });
  const lines = read(filePath).trimEnd().split("\n");
  assert.strictEqual(lines.length, 1);
  assert.strictEqual(lines[0], '2026-01-01T00:00:00.000Z [INFO] hello {"a":1}');
  fs.rmSync(dir, { recursive: true, force: true });
});

// 2. The target directory is created recursively.
check("creates missing directories", () => {
  const dir = tempDir();
  const filePath = path.join(dir, "nested", "deeper", LOG_FILE_NAME);
  const logger = createLogger({ filePath });
  logger.info("nested");
  assert.ok(fs.existsSync(filePath));
  fs.rmSync(dir, { recursive: true, force: true });
});

// 3. Rotation happens at maxBytes and the previous file is kept as .1.
check("rotates once the size limit is reached", () => {
  const dir = tempDir();
  const filePath = path.join(dir, LOG_FILE_NAME);
  const logger = createLogger({ filePath, maxBytes: 200, backups: 1 });
  const label = "x".repeat(60);
  for (let i = 0; i < 6; i += 1) logger.info(label);
  assert.ok(fs.existsSync(`${filePath}.1`), "backup should exist");
  assert.ok(fs.statSync(filePath).size < 200, "active file should stay under the limit");
  fs.rmSync(dir, { recursive: true, force: true });
});

// 4. Only `backups` numbered files are retained; the oldest is dropped.
check("keeps at most `backups` rotated files", () => {
  const dir = tempDir();
  const filePath = path.join(dir, LOG_FILE_NAME);
  const logger = createLogger({ filePath, maxBytes: 200, backups: 2 });
  const label = "y".repeat(60);
  for (let i = 0; i < 12; i += 1) logger.info(label);
  assert.ok(fs.existsSync(`${filePath}.1`), ".1 should exist");
  assert.ok(fs.existsSync(`${filePath}.2`), ".2 should exist");
  assert.ok(!fs.existsSync(`${filePath}.3`), ".3 must not exist");
  fs.rmSync(dir, { recursive: true, force: true });
});

// 5. backups=0 disables backups entirely.
check("backups:0 discards the previous file without keeping copies", () => {
  const dir = tempDir();
  const filePath = path.join(dir, LOG_FILE_NAME);
  const logger = createLogger({ filePath, maxBytes: 150, backups: 0 });
  const label = "z".repeat(60);
  for (let i = 0; i < 6; i += 1) logger.info(label);
  assert.ok(!fs.existsSync(`${filePath}.1`), "no backup should be kept");
  assert.ok(fs.existsSync(filePath));
  fs.rmSync(dir, { recursive: true, force: true });
});

// 6. Default rotation budget is 5 MB with a single backup.
check("defaults to a 5 MB limit and one backup", () => {
  const logger = createLogger({ filePath: path.join(tempDir(), LOG_FILE_NAME) });
  assert.strictEqual(logger.maxBytes, 5 * 1024 * 1024);
  assert.strictEqual(logger.backups, 1);
});

// 7. Errors and circular values are serialized without throwing.
check("serializes errors and circular data safely", () => {
  const dir = tempDir();
  const filePath = path.join(dir, LOG_FILE_NAME);
  const logger = createLogger({ filePath });
  const circular = {};
  circular.self = circular;
  logger.error("boom", new Error("kaput"));
  logger.warn("loop", circular);
  const contents = read(filePath);
  assert.ok(contents.includes("[ERROR] boom"));
  assert.ok(contents.includes("kaput"));
  assert.ok(contents.includes("[WARN] loop"));
  fs.rmSync(dir, { recursive: true, force: true });
});

// 8. A bad path never throws out of the logger (best-effort logging).
check("swallows filesystem errors instead of throwing", () => {
  const logger = createLogger({ filePath: path.join(os.tmpdir(), "qc-missing-\0", LOG_FILE_NAME) });
  logger.info("should not throw");
});

// 9. A missing filePath is tolerated (used before the app is ready).
check("tolerates a missing filePath", () => {
  const logger = createLogger({});
  logger.info("no path");
});

const failed = results.filter((result) => !result.ok);
for (const result of results) {
  const status = result.ok ? "PASS" : "FAIL";
  const detail = result.ok ? "" : `  -> ${result.detail}`;
  console.log(`  ${status}  ${result.name}${detail}`);
}
console.log(`\nLogger tests: ${results.length - failed.length}/${results.length} passed`);
process.exit(failed.length > 0 ? 1 : 0);
