const { contextBridge, ipcRenderer } = require("electron");

contextBridge.exposeInMainWorld("electronAPI", {
  ping: () => ipcRenderer.invoke("ping"),
  onMenuExport: (callback) => {
    ipcRenderer.on("menu-export-qasm", () => callback());
  },
  exportQasm: (code) => ipcRenderer.invoke("export-qasm", code),
  onMenuImport: (callback) => {
    ipcRenderer.on("menu-import-qasm", () => callback());
  },
  importQasm: () => ipcRenderer.invoke("import-qasm"),
  onMenuExportSvg: (callback) => {
    ipcRenderer.on("menu-export-svg", () => callback());
  },
  exportSvg: (svg) => ipcRenderer.invoke("export-svg", svg)
});
