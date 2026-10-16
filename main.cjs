const { app, BrowserWindow, ipcMain, Menu, dialog } = require("electron");
const fs = require("node:fs");
const path = require("node:path");
const { createLogger, LOG_FILE_NAME } = require("./logger.cjs");

const filePaths = new Map();
const mainWindows = new Set();
const mainToEditor = new Map(); // main window webContents.id -> editor window
const editorToMain = new Map(); // editor window webContents.id -> main window

const DEFAULT_SNAPSHOT_MINUTES = 1;
const MIN_SNAPSHOT_MINUTES = 1;
const MAX_SNAPSHOT_MINUTES = 1440;
let snapshotTimer = null;

// Replaced with a real file logger once the application is ready. Until then
// (and if the log file cannot be written) these calls are harmless no-ops.
let logger = { debug() {}, info() {}, warn() {}, error() {} };

function logFilePath() {
  const directory = process.env.QC_SCHEMATIC_LOG_DIR || app.getPath("logs");
  return path.join(directory, LOG_FILE_NAME);
}

process.on("uncaughtException", (error) => {
  logger.error("uncaughtException", error);
  app.exit(1);
});

process.on("unhandledRejection", (reason) => {
  logger.error("unhandledRejection", reason);
});

function createWindow() {
  const win = new BrowserWindow({
    width: 900,
    height: 650,
    webPreferences: {
      preload: path.join(__dirname, "preload.cjs"),
      contextIsolation: true,
      nodeIntegration: false
    }
  });

  const id = win.webContents.id;
  mainWindows.add(win);
  logger.info("main window created", { id });
  win.webContents.on("render-process-gone", (_event, details) => {
    logger.error("render process gone", {
      id,
      reason: details && details.reason,
      exitCode: details && details.exitCode
    });
  });
  win.on("closed", () => {
    filePaths.delete(id);
    mainWindows.delete(win);
    const editor = mainToEditor.get(id);
    mainToEditor.delete(id);
    if (editor && !editor.isDestroyed()) editor.close();
    logger.info("main window closed", { id });
  });

  win.loadFile(path.join(__dirname, "index.html"));
  return win;
}

// Menu actions addressed at any window must reach the window that owns the
// circuit model: for an editor window that is the main window it is paired with.
function resolveMainWindow(win) {
  if (win && !win.isDestroyed()) {
    const paired = editorToMain.get(win.webContents.id);
    if (paired) {
      if (!paired.isDestroyed()) return paired;
    } else if (mainWindows.has(win)) {
      return win;
    }
  }
  return anyMainWindow();
}

function anyMainWindow() {
  for (const candidate of mainWindows) {
    if (!candidate.isDestroyed()) return candidate;
  }
  return null;
}

function sendToWindow(win, channel) {
  const target = resolveMainWindow(win);
  if (target) target.webContents.send(channel);
}

function openEditorWindow(win) {
  const main = resolveMainWindow(win);
  if (!main) return null;
  const existing = mainToEditor.get(main.webContents.id);
  if (existing && !existing.isDestroyed()) {
    existing.focus();
    return existing;
  }

  const editor = new BrowserWindow({
    width: 560,
    height: 650,
    minWidth: 360,
    minHeight: 300,
    parent: main,
    title: "Editor",
    webPreferences: {
      preload: path.join(__dirname, "preload.cjs"),
      contextIsolation: true,
      nodeIntegration: false
    }
  });

  const mainId = main.webContents.id;
  const editorId = editor.webContents.id;
  mainToEditor.set(mainId, editor);
  editorToMain.set(editorId, main);
  logger.info("editor window created", { mainId, editorId });
  editor.webContents.on("render-process-gone", (_event, details) => {
    logger.error("render process gone", {
      id: editorId,
      reason: details && details.reason,
      exitCode: details && details.exitCode
    });
  });
  editor.on("closed", () => {
    mainToEditor.delete(mainId);
    editorToMain.delete(editorId);
    logger.info("editor window closed", { mainId, editorId });
  });

  // The editor window is a focused editing surface; it keeps the native
  // cut/copy/paste/undo shortcuts of editable content but shows no menu bar.
  editor.removeMenu();

  editor.loadFile(path.join(__dirname, "editor.html"));
  return editor;
}

function snapshotPathFor(filePath) {
  return path.join(
    path.dirname(filePath),
    `.${path.basename(filePath)}.autosave.qasm`
  );
}

function removeSnapshot(filePath) {
  if (!filePath) return;
  try {
    fs.rmSync(snapshotPathFor(filePath), { force: true });
  } catch {
    // snapshot may not exist; nothing to clean up
  }
}

function normalizeSnapshotMinutes(value) {
  const minutes = Math.round(Number(value));
  if (!Number.isFinite(minutes)) return DEFAULT_SNAPSHOT_MINUTES;
  return Math.min(Math.max(minutes, MIN_SNAPSHOT_MINUTES), MAX_SNAPSHOT_MINUTES);
}

function runSnapshotTick() {
  for (const win of BrowserWindow.getAllWindows()) {
    if (!win.isDestroyed() && filePaths.has(win.webContents.id)) {
      win.webContents.send("request-snapshot");
    }
  }
}

function scheduleSnapshotTimer(minutes) {
  const effective = normalizeSnapshotMinutes(minutes);
  if (snapshotTimer) clearInterval(snapshotTimer);
  snapshotTimer = setInterval(runSnapshotTick, effective * 60_000);
  return effective;
}

function activeWindow(win) {
  if (win && !win.isDestroyed()) return win;
  return anyMainWindow();
}

function editCommand(command) {
  return (_item, win) => {
    const target = activeWindow(win);
    if (target) target.webContents[command]();
  };
}

function buildMenu() {
  const isMac = process.platform === "darwin";
  const template = [
    ...(isMac ? [{ role: "appMenu" }] : []),
    {
      label: "File",
      submenu: [
        {
          label: "New",
          accelerator: "CmdOrCtrl+N",
          click: () => {
            createWindow();
          }
        },
        {
          label: "Open…",
          accelerator: "CmdOrCtrl+O",
          click: (_item, win) => sendToWindow(win, "menu-import-qasm")
        },
        { type: "separator" },
        {
          label: "Save",
          accelerator: "CmdOrCtrl+S",
          click: (_item, win) => sendToWindow(win, "menu-save-qasm")
        },
        {
          label: "Save As…",
          accelerator: "CmdOrCtrl+Shift+S",
          click: (_item, win) => sendToWindow(win, "menu-save-as-qasm")
        },
        { type: "separator" },
        {
          label: "Export SVG…",
          accelerator: "CmdOrCtrl+Shift+E",
          click: (_item, win) => sendToWindow(win, "menu-export-svg")
        },
        { type: "separator" },
        isMac ? { role: "close" } : { role: "quit" }
      ]
    },
    {
      label: "Edit",
      submenu: [
        { label: "Undo", accelerator: "CmdOrCtrl+Z", click: (_item, win) => sendToWindow(win, "menu-undo") },
        { label: "Redo", accelerator: "CmdOrCtrl+Shift+Z", click: (_item, win) => sendToWindow(win, "menu-redo") },
        { type: "separator" },
        { label: "Cut", accelerator: "CmdOrCtrl+X", click: editCommand("cut") },
        { label: "Copy", accelerator: "CmdOrCtrl+C", click: editCommand("copy") },
        { label: "Paste", accelerator: "CmdOrCtrl+V", click: editCommand("paste") },
        {
          label: "Paste and Match Style",
          accelerator: "CmdOrCtrl+Shift+V",
          click: editCommand("pasteAndMatchStyle")
        },
        { label: "Delete", click: editCommand("delete") },
        { type: "separator" },
        { label: "Select All", accelerator: "CmdOrCtrl+A", click: editCommand("selectAll") }
      ]
    },
    {
      label: "Preferences",
      submenu: [
        {
          label: "Settings…",
          accelerator: "CmdOrCtrl+,",
          click: (_item, win) => sendToWindow(win, "menu-settings")
        }
      ]
    },
    {
      label: "View",
      submenu: [
        { role: "reload" },
        { role: "forceReload" },
        { role: "toggleDevTools" },
        { type: "separator" },
        { role: "resetZoom" },
        { role: "zoomIn" },
        { role: "zoomOut" },
        { type: "separator" },
        { role: "togglefullscreen" },
        { type: "separator" },
        {
          label: "Windows",
          submenu: [
            {
              label: "Editor",
              click: (_item, win) => openEditorWindow(win)
            }
          ]
        }
      ]
    },
    { role: "windowMenu" }
  ];
  Menu.setApplicationMenu(Menu.buildFromTemplate(template));
}

app.whenReady().then(() => {
  logger = createLogger({ filePath: logFilePath() });
  logger.info("application ready", {
    version: app.getVersion(),
    platform: process.platform,
    arch: process.arch,
    electron: process.versions.electron,
    chrome: process.versions.chrome,
    node: process.versions.node
  });

  ipcMain.handle("ping", () => "pong from Electron");

  ipcMain.handle("export-qasm", async (event, code) => {
    const win = BrowserWindow.fromWebContents(event.sender);
    const { canceled, filePath } = await dialog.showSaveDialog(win, {
      title: "Export OpenQASM",
      defaultPath: "circuit.qasm",
      filters: [
        { name: "OpenQASM", extensions: ["qasm"] },
        { name: "All Files", extensions: ["*"] }
      ]
    });
    if (canceled || !filePath) return { saved: false };
    try {
      fs.writeFileSync(filePath, String(code ?? ""), "utf8");
      logger.info("exported OpenQASM", { filePath });
      return { saved: true, filePath };
    } catch (error) {
      logger.error("failed to export OpenQASM", { filePath, error: String(error) });
      return { saved: false, error: String(error) };
    }
  });

  ipcMain.handle("import-qasm", async (event) => {
    const win = BrowserWindow.fromWebContents(event.sender);
    const { canceled, filePaths: chosen } = await dialog.showOpenDialog(win, {
      title: "Open OpenQASM",
      properties: ["openFile"],
      filters: [
        { name: "OpenQASM", extensions: ["qasm", "qasm3", "inc"] },
        { name: "All Files", extensions: ["*"] }
      ]
    });
    if (canceled || !chosen || !chosen.length) return { ok: false };
    try {
      const content = fs.readFileSync(chosen[0], "utf8");
      logger.info("opened OpenQASM", { filePath: chosen[0] });
      return { ok: true, content, filePath: chosen[0] };
    } catch (error) {
      logger.error("failed to open OpenQASM", { filePath: chosen[0], error: String(error) });
      return { ok: false, error: String(error) };
    }
  });

  ipcMain.handle("export-svg", async (event, svg) => {
    const win = BrowserWindow.fromWebContents(event.sender);
    const { canceled, filePath } = await dialog.showSaveDialog(win, {
      title: "Export SVG",
      defaultPath: "circuit.svg",
      filters: [
        { name: "SVG", extensions: ["svg"] },
        { name: "All Files", extensions: ["*"] }
      ]
    });
    if (canceled || !filePath) return { saved: false };
    try {
      fs.writeFileSync(filePath, String(svg ?? ""), "utf8");
      logger.info("exported SVG", { filePath });
      return { saved: true, filePath };
    } catch (error) {
      logger.error("failed to export SVG", { filePath, error: String(error) });
      return { saved: false, error: String(error) };
    }
  });

  ipcMain.handle("save-qasm", async (event, code) => {
    const win = BrowserWindow.fromWebContents(event.sender);
    const id = event.sender.id;
    let filePath = filePaths.get(id);
    if (!filePath) {
      const { canceled, filePath: chosen } = await dialog.showSaveDialog(win, {
        title: "Save OpenQASM",
        defaultPath: "circuit.qasm",
        filters: [
          { name: "OpenQASM", extensions: ["qasm"] },
          { name: "All Files", extensions: ["*"] }
        ]
      });
      if (canceled || !chosen) return { saved: false };
      filePath = chosen;
    }
    try {
      fs.writeFileSync(filePath, String(code ?? ""), "utf8");
      filePaths.set(id, filePath);
      removeSnapshot(filePath);
      logger.info("saved OpenQASM", { filePath });
      return { saved: true, filePath };
    } catch (error) {
      logger.error("failed to save OpenQASM", { filePath, error: String(error) });
      return { saved: false, error: String(error) };
    }
  });

  ipcMain.handle("save-as-qasm", async (event, code) => {
    const win = BrowserWindow.fromWebContents(event.sender);
    const id = event.sender.id;
    const previousPath = filePaths.get(id);
    const { canceled, filePath } = await dialog.showSaveDialog(win, {
      title: "Save OpenQASM As",
      defaultPath: previousPath || "circuit.qasm",
      filters: [
        { name: "OpenQASM", extensions: ["qasm"] },
        { name: "All Files", extensions: ["*"] }
      ]
    });
    if (canceled || !filePath) return { saved: false };
    try {
      fs.writeFileSync(filePath, String(code ?? ""), "utf8");
      filePaths.set(id, filePath);
      removeSnapshot(filePath);
      if (previousPath && previousPath !== filePath) removeSnapshot(previousPath);
      logger.info("saved OpenQASM as", { filePath });
      return { saved: true, filePath };
    } catch (error) {
      logger.error("failed to save OpenQASM as", { filePath, error: String(error) });
      return { saved: false, error: String(error) };
    }
  });

  ipcMain.handle("save-snapshot", async (event, code) => {
    const id = event.sender.id;
    const filePath = filePaths.get(id);
    if (!filePath) return { saved: false };
    const snapshotPath = snapshotPathFor(filePath);
    try {
      fs.writeFileSync(snapshotPath, String(code ?? ""), "utf8");
      logger.debug("wrote snapshot", { filePath: snapshotPath });
      return { saved: true, filePath: snapshotPath };
    } catch (error) {
      logger.error("failed to write snapshot", { filePath: snapshotPath, error: String(error) });
      return { saved: false, error: String(error) };
    }
  });

  ipcMain.handle("set-snapshot-period", (_event, minutes) => {
    const effective = scheduleSnapshotTimer(minutes);
    logger.info("snapshot period set", { minutes: effective });
    return { minutes: effective };
  });

  // Editor window <-> main window relay. The editor renderer never talks to the
  // circuit model directly; the main process only forwards between paired
  // windows and validates the payloads.
  ipcMain.on("editor-change", (event, code) => {
    if (typeof code !== "string") return;
    const main = editorToMain.get(event.sender.id);
    if (main && !main.isDestroyed()) {
      logger.debug("editor change forwarded", { chars: code.length });
      main.webContents.send("editor-change", code);
    }
  });

  ipcMain.on("publish-code", (event, code) => {
    if (typeof code !== "string") return;
    const editor = mainToEditor.get(event.sender.id);
    if (editor && !editor.isDestroyed()) editor.webContents.send("circuit-code", code);
  });

  ipcMain.handle("editor-ready", (event) => {
    const main = editorToMain.get(event.sender.id);
    if (main && !main.isDestroyed()) {
      main.webContents.send("request-editor-code");
      logger.info("editor ready");
      return { ok: true };
    }
    logger.warn("editor ready without a paired main window");
    return { ok: false };
  });

  app.on("child-process-gone", (_event, details) => {
    logger.error("child process gone", details);
  });

  scheduleSnapshotTimer(DEFAULT_SNAPSHOT_MINUTES);

  buildMenu();
  createWindow();
});

app.on("window-all-closed", () => {
  logger.info("all windows closed");
  if (process.platform !== "darwin") app.quit();
});

app.on("before-quit", () => {
  logger.info("application quitting");
});
