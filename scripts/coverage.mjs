import { readFileSync, existsSync } from "node:fs";

const LCOV = "coverage/lcov.info";
const THRESHOLD = Number(process.env.COVERAGE_THRESHOLD ?? 50);
const FILE_MARKER = "/cljs-runtime/app/";

if (!existsSync(LCOV)) {
  console.error(`Missing ${LCOV}. Run the coverage command first.`);
  process.exit(1);
}

const sources = [];
let current = null;

for (const rawLine of readFileSync(LCOV, "utf8").split(/\r?\n/)) {
  const line = rawLine.trim();
  if (line.startsWith("SF:")) {
    current = { file: line.slice(3).replaceAll("\\", "/"), found: 0, hit: 0 };
  } else if (line.startsWith("LF:") && current) {
    current.found = Number(line.slice(3));
  } else if (line.startsWith("LH:") && current) {
    current.hit = Number(line.slice(3));
    sources.push(current);
    current = null;
  }
}

const app = sources.filter(
  (s) => s.file.includes(FILE_MARKER) && !s.file.endsWith("_test.cljs")
);

if (app.length === 0) {
  console.error("No application source files found in lcov.");
  process.exit(1);
}

const totalFound = app.reduce((sum, s) => sum + s.found, 0);
const totalHit = app.reduce((sum, s) => sum + s.hit, 0);
const percent = totalFound === 0 ? 0 : (totalHit / totalFound) * 100;

const nameWidth = Math.max(...app.map((s) => s.file.split("/").pop().length), 9);
console.log("Application source coverage (src/app/**)");
console.log("-".repeat(nameWidth + 22));
console.log(`${"File".padEnd(nameWidth)}  Hit / Found    Lines %`);
for (const s of app) {
  const pct = s.found === 0 ? 100 : (s.hit / s.found) * 100;
  console.log(
    `${s.file.split("/").pop().padEnd(nameWidth)}  ${String(s.hit).padStart(4)} / ${String(s.found).padEnd(6)} ${pct.toFixed(2).padStart(7)}`
  );
}
console.log("-".repeat(nameWidth + 22));
console.log(
  `${"All app files".padEnd(nameWidth)}  ${String(totalHit).padStart(4)} / ${String(totalFound).padEnd(6)} ${percent.toFixed(2).padStart(7)}`
);

if (percent < THRESHOLD) {
  console.error(`\nCoverage ${percent.toFixed(2)}% is below the ${THRESHOLD}% threshold.`);
  process.exit(1);
}

console.log(`\nCoverage ${percent.toFixed(2)}% meets the ${THRESHOLD}% threshold.`);
