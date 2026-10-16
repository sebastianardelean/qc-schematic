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
  exportSvg: (svg) => ipcRenderer.invoke("export-svg", svg),
  onMenuSave: (callback) => {
    ipcRenderer.on("menu-save-qasm", () => callback());
  },
  saveQasm: (code) => ipcRenderer.invoke("save-qasm", code),
  onMenuSaveAs: (callback) => {
    ipcRenderer.on("menu-save-as-qasm", () => callback());
  },
  saveQasmAs: (code) => ipcRenderer.invoke("save-as-qasm", code),
  onRequestSnapshot: (callback) => {
    ipcRenderer.on("request-snapshot", () => callback());
  },
  saveSnapshot: (code) => ipcRenderer.invoke("save-snapshot", code),
  onMenuSettings: (callback) => {
    ipcRenderer.on("menu-settings", () => callback());
  },
  onMenuUndo: (callback) => {
    ipcRenderer.on("menu-undo", () => callback());
  },
  onMenuRedo: (callback) => {
    ipcRenderer.on("menu-redo", () => callback());
  },
  setSnapshotPeriod: (minutes) => ipcRenderer.invoke("set-snapshot-period", minutes),
  onEditorChange: (callback) => {
    ipcRenderer.on("editor-change", (_event, code) => callback(code));
  },
  onRequestEditorCode: (callback) => {
    ipcRenderer.on("request-editor-code", () => callback());
  },
  publishCode: (code) => ipcRenderer.send("publish-code", code),
  onCircuitCode: (callback) => {
    ipcRenderer.on("circuit-code", (_event, code) => callback(code));
  },
  sendEditorChange: (code) => ipcRenderer.send("editor-change", code),
  editorReady: () => ipcRenderer.invoke("editor-ready")
});
