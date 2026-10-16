// End-to-end (system) test for qc-schematic.
//
// It boots the REAL Electron application (main.cjs + preload.cjs + the compiled
// renderer in public/js/main.js and public/js/editor.js), then drives the live
// windows through webContents.executeJavaScript exactly like a user would:
// opening the editor window from the View menu, editing the OpenQASM editor,
// dragging a gate from the palette and using the application menu.
//
// Run with: npm run test:system   (electron test/system/electron-entry.cjs)

const { app, BrowserWindow, Menu, dialog } = require("electron");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");

app.disableHardwareAcceleration();
app.commandLine.appendSwitch("no-sandbox");

const repoRoot = path.join(__dirname, "..", "..");
const results = [];

function check(name, ok, detail) {
  results.push({ name, ok: Boolean(ok), detail });
}

function delay(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function waitForLoad(win) {
  return new Promise((resolve) => {
    if (!win.webContents.isLoading()) resolve();
    else win.webContents.once("did-finish-load", resolve);
  });
}

// Prepare a throw-away workspace and patch the native dialogs BEFORE the
// application is required, so the `dialog` object the app captured is patched.
const workspace = fs.mkdtempSync(path.join(os.tmpdir(), "qc-system-"));
const savePath = path.join(workspace, "circuit.qasm");
const svgPath = path.join(workspace, "circuit.svg");
const importPath = path.join(workspace, "import.qasm");
const logDir = path.join(workspace, "logs");
process.env.QC_SCHEMATIC_LOG_DIR = logDir;
fs.writeFileSync(
  importPath,
  'OPENQASM 3;\ninclude "stdgates.inc";\nqubit[2] q;\nh q[0];\ncx q[0], q[1];\n',
  "utf8"
);

dialog.showSaveDialog = async (_win, options = {}) => {
  if (/svg/i.test(options.title || "")) {
    return { canceled: false, filePath: svgPath };
  }
  return { canceled: false, filePath: savePath };
};
dialog.showOpenDialog = async () => ({ canceled: false, filePaths: [importPath] });

// Boot the real application (registers IPC handlers, builds the menu, opens a window).
require(path.join(repoRoot, "main.cjs"));

function clickMenuItem(labels, targetWin) {
  let items = (Menu.getApplicationMenu() || { items: [] }).items;
  let item = null;
  for (const label of labels) {
    item = items.find((candidate) => candidate.label === label);
    if (!item) return false;
    items = item.submenu ? item.submenu.items : [];
  }
  if (typeof item.click !== "function") return false;
  const win = targetWin || BrowserWindow.getFocusedWindow() || BrowserWindow.getAllWindows()[0];
  item.click(item, win);
  return true;
}

async function run() {
  try {
    await runSteps();
  } catch (error) {
    console.error("STEP ERROR:", error && error.message, "\n", error && error.stack);
    check("system test completed without a thrown error", false, String(error));
    finish();
  }
}

async function runSteps() {
  const win = BrowserWindow.getAllWindows()[0];
  if (!win) {
    check("application window was created", false);
    return finish();
  }

  await waitForLoad(win);
  await delay(400);

  const js = (code) => win.webContents.executeJavaScript(code, true);

  const program =
    'OPENQASM 3;\ninclude "stdgates.inc";\nqubit[2] q;\nh q[0];\ncx q[0], q[1];\nbarrier q[0], q[1];\n';

  // 1. Preload bridge is alive.
  const pong = await js("window.electronAPI.ping()");
  check("preload exposes ping and the main process replies", pong === "pong from Electron", pong);

  // 2. The main window is split into operations + canvas only.
  const mainDom = await js(`({
    operations: !!document.getElementById("operations"),
    canvas: !!document.getElementById("canvas"),
    codePanel: !!document.getElementById("code-panel")
  })`);
  check(
    "the main window is split into operations and canvas only",
    mainDom.operations && mainDom.canvas && !mainDom.codePanel,
    mainDom
  );

  // 3. View -> Windows -> Editor launches the editor in a separate window.
  const opened = clickMenuItem(["View", "Windows", "Editor"], win);
  await delay(600);
  const editorWin = BrowserWindow.getAllWindows().find((candidate) => candidate !== win);
  check("View -> Windows -> Editor opens a separate editor window", opened && !!editorWin);
  if (!editorWin) return finish();

  // The editor window shows no application menu bar. On macOS the menu bar is
  // global to the app, so the per-window check only applies elsewhere.
  if (process.platform !== "darwin") {
    check("the editor window has no menu bar", editorWin.isMenuBarVisible() === false, editorWin.isMenuBarVisible());
  }

  await waitForLoad(editorWin);
  await delay(400);
  const edJs = (code) => editorWin.webContents.executeJavaScript(code, true);

  const editorPanel = await edJs(`({
    textarea: !!document.getElementById("qasm"),
    status: document.getElementById("qasm-status") && document.getElementById("qasm-status").textContent
  })`);
  check("the editor window hosts the OpenQASM editor", editorPanel.textarea, editorPanel);

  // 4. Editing the OpenQASM editor rebuilds the canvas (renderer integration).
  await edJs(`(() => {
    const editor = document.getElementById("qasm");
    editor.value = ${JSON.stringify(program)};
    editor.dispatchEvent(new Event("input", { bubbles: true }));
    editor.dispatchEvent(new Event("blur", { bubbles: true }));
  })()`);
  await delay(300);

  const dom = await js(`({
    gates: document.querySelectorAll("#gates-layer .gate").length,
    barriers: document.querySelectorAll("#gates-layer .barrier-line").length,
    wires: document.querySelectorAll("#wires-layer .wire").length
  })`);
  check("editor edit renders three gates on the canvas", dom.gates === 3, dom.gates);
  check("the barrier element is drawn", dom.barriers === 1, dom.barriers);
  check("the qubit wires are drawn", dom.wires === 2, dom.wires);

  // 5. Dragging a gate from the palette adds it to the circuit.
  const dragover = await js(`(() => {
    const before = document.querySelectorAll("#gates-layer .gate").length;
    const canvas = document.getElementById("canvas");
    const palette = document.querySelector("#palette .palette-gate");
    const dataTransfer = new DataTransfer();
    palette.dispatchEvent(new DragEvent("dragstart", { bubbles: true, dataTransfer }));
    canvas.dispatchEvent(new DragEvent("drop", {
      bubbles: true, cancelable: true, dataTransfer, clientX: 320, clientY: 120
    }));
    return { before, after: document.querySelectorAll("#gates-layer .gate").length };
  })()`);
  check("dragging a palette gate adds exactly one gate", dragover.after === dragover.before + 1, dragover);

  // 6. File -> Save writes the current circuit through IPC.
  const editorValue = await edJs('document.getElementById("qasm").value');
  win.webContents.send("menu-save-qasm");
  await delay(600);
  const savedExists = fs.existsSync(savePath);
  const savedContent = savedExists ? fs.readFileSync(savePath, "utf8") : "";
  check("File -> Save writes the circuit to disk", savedExists, savePath);
  check("the saved file matches the editor contents", savedContent === editorValue, {
    saved: savedContent.slice(0, 40),
    editor: editorValue.slice(0, 40)
  });

  // 7. File -> Export SVG writes a valid SVG.
  win.webContents.send("menu-export-svg");
  await delay(600);
  const svgExists = fs.existsSync(svgPath);
  const svgContent = svgExists ? fs.readFileSync(svgPath, "utf8") : "";
  check("File -> Export SVG writes an svg document", svgExists && svgContent.includes("<svg"), svgPath);

  // 8. File -> Open imports a circuit and redraws the canvas + editor.
  win.webContents.send("menu-import-qasm");
  await delay(600);
  const imported = await js('document.querySelectorAll("#gates-layer .gate").length');
  const importedQasm = await edJs('document.getElementById("qasm").value');
  check("File -> Open loads and renders the chosen circuit", imported === 2, imported);
  check("the imported editor contents are canonicalised", importedQasm.includes("cx q[0], q[1];"), importedQasm);

  // 9. Snapshot autosave targets the last saved file.
  win.webContents.send("request-snapshot");
  await delay(400);
  const snapshotPath = path.join(workspace, `.${path.basename(savePath)}.autosave.qasm`);
  check("autosave snapshot is written beside the saved file", fs.existsSync(snapshotPath), snapshotPath);

  // 10. Snapshot period IPC round-trips.
  const period = await js("window.electronAPI.setSnapshotPeriod(5)");
  check("snapshot period is forwarded to the main process", period && period.minutes === 5, period);

  // 11. Preferences -> Settings toggles the settings overlay.
  const hiddenBefore = await js('document.getElementById("settings-panel").classList.contains("hidden")');
  win.webContents.send("menu-settings");
  await delay(200);
  const hiddenAfter = await js('document.getElementById("settings-panel").classList.contains("hidden")');
  check("Preferences -> Settings opens the settings panel", hiddenBefore === true && hiddenAfter === false, {
    before: hiddenBefore,
    after: hiddenAfter
  });

  // 12. Edit -> Undo operates on the focused editor window.
  await edJs(`(() => {
    const editor = document.getElementById("qasm");
    editor.value = "";
    editor.focus();
    editor.setSelectionRange(0, 0);
    document.execCommand("insertText", false, "hello");
  })()`);
  const beforeUndo = await edJs('document.getElementById("qasm").value');
  const undoClicked = clickMenuItem(["Edit", "Undo"], editorWin);
  await delay(250);
  const afterUndo = await edJs('document.getElementById("qasm").value');
  check("Edit -> Undo is wired to the editor window", undoClicked && afterUndo !== beforeUndo, {
    before: beforeUndo,
    after: afterUndo
  });

  // 13. The main process writes a rotating log file.
  const logFile = path.join(logDir, "qc-schematic.log");
  const logExists = fs.existsSync(logFile);
  const logContents = logExists ? fs.readFileSync(logFile, "utf8") : "";
  check("the main process writes a log file", logExists && logContents.includes("application ready"), logFile);
  check(
    "the log records window lifecycle events",
    logContents.includes("[INFO] main window created") && logContents.includes("[INFO] editor window created"),
    logContents.slice(0, 120)
  );

  finish();
}

function finish() {
  for (const result of results) {
    const status = result.ok ? "PASS" : "FAIL";
    const detail = result.ok ? "" : `  -> ${JSON.stringify(result.detail)}`;
    console.log(`  ${status}  ${result.name}${detail}`);
  }
  const failed = results.filter((result) => !result.ok).length;
  console.log(`\nSystem tests: ${results.length - failed}/${results.length} passed`);
  try {
    fs.rmSync(workspace, { recursive: true, force: true });
  } catch {
    // best effort cleanup
  }
  app.exit(failed > 0 || results.length === 0 ? 1 : 0);
}

process.on("unhandledRejection", (error) => {
  console.error("Unhandled rejection:", error);
  app.exit(1);
});

app.whenReady().then(run);
