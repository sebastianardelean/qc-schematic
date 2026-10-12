const { contextBridge, ipcRenderer } = require("electron");

contextBridge.exposeInMainWorld("electronAPI", {
  ping: () => ipcRenderer.invoke("ping"),
  onMenuExport: (callback) => {
    ipcRenderer.on("menu-export-qasm", () => callback());
  },
  exportQasm: (code) => ipcRenderer.invoke("export-qasm", code)
});