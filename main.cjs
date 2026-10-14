const { app, BrowserWindow, ipcMain, Menu, dialog } = require("electron");
const fs = require("node:fs");
const path = require("node:path");

let mainWindow = null;
const filePaths = new Map();

const DEFAULT_SNAPSHOT_MINUTES = 1;
const MIN_SNAPSHOT_MINUTES = 1;
const MAX_SNAPSHOT_MINUTES = 1440;
let snapshotTimer = null;

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
  win.on("closed", () => filePaths.delete(id));

  win.loadFile(path.join(__dirname, "index.html"));
  mainWindow = win;
  return win;
}

function sendToWindow(win, channel) {
  const target = win && !win.isDestroyed() ? win : mainWindow;
  if (target && !target.isDestroyed()) {
    target.webContents.send(channel);
  }
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
  const target = win && !win.isDestroyed() ? win : mainWindow;
  return target && !target.isDestroyed() ? target : null;
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
        { label: "Undo", accelerator: "CmdOrCtrl+Z", click: editCommand("undo") },
        { label: "Redo", accelerator: "CmdOrCtrl+Shift+Z", click: editCommand("redo") },
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
    { role: "viewMenu" },
    { role: "windowMenu" }
  ];
  Menu.setApplicationMenu(Menu.buildFromTemplate(template));
}

app.whenReady().then(() => {
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
      return { saved: true, filePath };
    } catch (error) {
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
      return { ok: true, content, filePath: chosen[0] };
    } catch (error) {
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
      return { saved: true, filePath };
    } catch (error) {
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
      return { saved: true, filePath };
    } catch (error) {
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
      return { saved: true, filePath };
    } catch (error) {
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
      return { saved: true, filePath: snapshotPath };
    } catch (error) {
      return { saved: false, error: String(error) };
    }
  });

  ipcMain.handle("set-snapshot-period", (_event, minutes) => {
    return { minutes: scheduleSnapshotTimer(minutes) };
  });

  scheduleSnapshotTimer(DEFAULT_SNAPSHOT_MINUTES);

  buildMenu();
  createWindow();
});

app.on("window-all-closed", () => {
  if (process.platform !== "darwin") app.quit();
});
