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
        { type: "separator" },
        isMac ? { role: "close" } : { role: "quit" }
      ]
    },
    { role: "editMenu" },
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

  buildMenu();
  createWindow();
});

app.on("window-all-closed", () => {
  if (process.platform !== "darwin") app.quit();
});