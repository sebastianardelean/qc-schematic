// End-to-end (system) test for qc-schematic.
//
// It boots the REAL Electron application (main.cjs + preload.cjs + the compiled
// renderer in public/js/main.js), then drives the live renderer through
// webContents.executeJavaScript exactly like a user would: editing the OpenQASM
// editor, dragging a gate from the palette and using the application menu.
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

// Prepare a throw-away workspace and patch the native dialogs BEFORE the
// application is required, so the `dialog` object the app captured is patched.
const workspace = fs.mkdtempSync(path.join(os.tmpdir(), "qc-system-"));
const savePath = path.join(workspace, "circuit.qasm");
const svgPath = path.join(workspace, "circuit.svg");
const importPath = path.join(workspace, "import.qasm");
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

function clickMenuItem(labels) {
  let items = (Menu.getApplicationMenu() || { items: [] }).items;
  let item = null;
  for (const label of labels) {
    item = items.find((candidate) => candidate.label === label);
    if (!item) return false;
    items = item.submenu ? item.submenu.items : [];
  }
  if (typeof item.click !== "function") return false;
  item.click(item, BrowserWindow.getFocusedWindow() || BrowserWindow.getAllWindows()[0]);
  return true;
}

async function run() {
  const win = BrowserWindow.getAllWindows()[0];
  if (!win) {
    check("application window was created", false);
    return finish();
  }

  await new Promise((resolve) => {
    if (!win.webContents.isLoading()) resolve();
    else win.webContents.once("did-finish-load", resolve);
  });
  await delay(400);

  const js = (code) => win.webContents.executeJavaScript(code, true);

  const program =
    'OPENQASM 3;\ninclude "stdgates.inc";\nqubit[2] q;\nh q[0];\ncx q[0], q[1];\nbarrier q[0], q[1];\n';

  // 1. Preload bridge is alive.
  const pong = await js("window.electronAPI.ping()");
  check("preload exposes ping and the main process replies", pong === "pong from Electron", pong);

  // 2. Editing the OpenQASM editor rebuilds the canvas (renderer integration).
  await js(`(() => {
    const editor = document.getElementById("qasm");
    editor.value = ${JSON.stringify(program)};
    editor.dispatchEvent(new Event("input", { bubbles: true }));
    editor.dispatchEvent(new Event("blur", { bubbles: true }));
  })()`);
  await delay(150);

  const dom = await js(`({
    gates: document.querySelectorAll("#gates-layer .gate").length,
    barriers: document.querySelectorAll("#gates-layer .barrier-line").length,
    wires: document.querySelectorAll("#wires-layer .wire").length,
    qasm: document.getElementById("qasm").value
  })`);
  check("editor edit renders three gates on the canvas", dom.gates === 3, dom.gates);
  check("the barrier element is drawn", dom.barriers === 1, dom.barriers);
  check("the qubit wires are drawn", dom.wires === 2, dom.wires);

  // 3. Dragging a gate from the palette adds it to the circuit.
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

  // 4. File -> Save writes the current circuit through IPC.
  const editorValue = await js('document.getElementById("qasm").value');
  win.webContents.send("menu-save-qasm");
  await delay(600);
  const savedExists = fs.existsSync(savePath);
  const savedContent = savedExists ? fs.readFileSync(savePath, "utf8") : "";
  check("File -> Save writes the circuit to disk", savedExists, savePath);
  check("the saved file matches the editor contents", savedContent === editorValue, {
    saved: savedContent.slice(0, 40),
    editor: editorValue.slice(0, 40)
  });

  // 5. File -> Export SVG writes a valid SVG.
  win.webContents.send("menu-export-svg");
  await delay(600);
  const svgExists = fs.existsSync(svgPath);
  const svgContent = svgExists ? fs.readFileSync(svgPath, "utf8") : "";
  check("File -> Export SVG writes an svg document", svgExists && svgContent.includes("<svg"), svgPath);

  // 6. File -> Open imports a circuit and redraws the canvas.
  win.webContents.send("menu-import-qasm");
  await delay(600);
  const imported = await js(`({
    gates: document.querySelectorAll("#gates-layer .gate").length,
    qasm: document.getElementById("qasm").value
  })`);
  check("File -> Open loads and renders the chosen circuit", imported.gates === 2, imported.gates);
  check("the imported editor contents are canonicalised", imported.qasm.includes("cx q[0], q[1];"), imported.qasm);

  // 7. Snapshot autosave targets the last saved file.
  win.webContents.send("request-snapshot");
  await delay(400);
  const snapshotPath = path.join(workspace, `.${path.basename(savePath)}.autosave.qasm`);
  check("autosave snapshot is written beside the saved file", fs.existsSync(snapshotPath), snapshotPath);

  // 8. Snapshot period IPC round-trips.
  const period = await js("window.electronAPI.setSnapshotPeriod(5)");
  check("snapshot period is forwarded to the main process", period && period.minutes === 5, period);

  // 9. Preferences -> Settings toggles the settings overlay.
  const hiddenBefore = await js('document.getElementById("settings-panel").classList.contains("hidden")');
  win.webContents.send("menu-settings");
  await delay(200);
  const hiddenAfter = await js('document.getElementById("settings-panel").classList.contains("hidden")');
  check("Preferences -> Settings opens the settings panel", hiddenBefore === true && hiddenAfter === false, {
    before: hiddenBefore,
    after: hiddenAfter
  });

  // 10. Edit -> Undo operates on the focused editor.
  await js(`(() => {
    const editor = document.getElementById("qasm");
    editor.value = "";
    editor.focus();
    editor.setSelectionRange(0, 0);
    document.execCommand("insertText", false, "hello");
  })()`);
  const beforeUndo = await js('document.getElementById("qasm").value');
  const undoClicked = clickMenuItem(["Edit", "Undo"]);
  await delay(250);
  const afterUndo = await js('document.getElementById("qasm").value');
  check("Edit -> Undo is wired to the editor", undoClicked && afterUndo !== beforeUndo, {
    before: beforeUndo,
    after: afterUndo
  });

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
