const { app, BrowserWindow, ipcMain, Menu, dialog } = require("electron");
const fs = require("node:fs");
const path = require("node:path");

let mainWindow = null;

function createWindow() {
  mainWindow = new BrowserWindow({
    width: 900,
    height: 650,
    webPreferences: {
      preload: path.join(__dirname, "preload.cjs"),
      contextIsolation: true,
      nodeIntegration: false
    }
  });

  mainWindow.loadFile(path.join(__dirname, "index.html"));
}

function buildMenu() {
  const isMac = process.platform === "darwin";
  const template = [
    ...(isMac ? [{ role: "appMenu" }] : []),
    {
      label: "File",
      submenu: [
        {
          label: "Export OpenQASM…",
          accelerator: "CmdOrCtrl+E",
          click: (_item, win) => {
            const target = win || mainWindow;
            if (target) target.webContents.send("menu-export-qasm");
          }
        },
        {
          label: "Import OpenQASM…",
          accelerator: "CmdOrCtrl+O",
          click: (_item, win) => {
            const target = win || mainWindow;
            if (target) target.webContents.send("menu-import-qasm");
          }
        },
        {
          label: "Export SVG…",
          accelerator: "CmdOrCtrl+Shift+E",
          click: (_item, win) => {
            const target = win || mainWindow;
            if (target) target.webContents.send("menu-export-svg");
          }
        },
        { type: "separator" },
        isMac ? { role: "close" } : { role: "quit" }
      ]
    },
    {
      label: "Edit",
      submenu: [
        { role: "undo" },
        { role: "redo" },
        { type: "separator" },
        { role: "cut" },
        { role: "copy" },
        { role: "paste" },
        { role: "pasteAndMatchStyle" },
        { role: "delete" },
        { type: "separator" },
        { role: "selectAll" }
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
    const { canceled, filePaths } = await dialog.showOpenDialog(win, {
      title: "Import OpenQASM",
      properties: ["openFile"],
      filters: [
        { name: "OpenQASM", extensions: ["qasm", "qasm3", "inc"] },
        { name: "All Files", extensions: ["*"] }
      ]
    });
    if (canceled || !filePaths || !filePaths.length) return { ok: false };
    try {
      const content = fs.readFileSync(filePaths[0], "utf8");
      return { ok: true, content, filePath: filePaths[0] };
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

  buildMenu();
  createWindow();
});

app.on("window-all-closed", () => {
  if (process.platform !== "darwin") app.quit();
});