// Gate for issue #60: the VS Code extension must contribute a toggleable
// `zio-bdd.stepDataFlowHints.enabled` setting (default off) and wire the LSP
// client to gate step data-flow inlay hints on it.
//
// Run: npm run test:config   (from extensions/vscode)
// No test framework — a plain node script that exits non-zero on the first
// failing group, so it is a cheap, dependency-light CI gate.

import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, resolve } from "node:path";

const here = dirname(fileURLToPath(import.meta.url));
const vscodeRoot = resolve(here, "..");

let failures = 0;
function check(name, cond) {
  if (cond) {
    console.log(`  ok  ${name}`);
  } else {
    console.error(`FAIL  ${name}`);
    failures++;
  }
}

// ── package.json contributes the setting (default off) ──────────────────────
const pkg = JSON.parse(readFileSync(resolve(vscodeRoot, "package.json"), "utf8"));
const props = pkg?.contributes?.configuration?.properties ?? {};
const setting = props["zio-bdd.stepDataFlowHints.enabled"];

check("contributes zio-bdd.stepDataFlowHints.enabled", setting != null);
check("setting is a boolean", setting?.type === "boolean");
check("setting defaults to false (off by default)", setting?.default === false);
const desc = setting?.markdownDescription ?? setting?.description ?? "";
check("setting has a description", desc.length > 0);
check(
  "description mentions the global editor.inlayHints.enabled it respects",
  desc.includes("editor.inlayHints.enabled"),
);

// ── the client wires the setting into an inlay-hints gate + live refresh ─────
const ext = readFileSync(resolve(vscodeRoot, "src", "extension.ts"), "utf8");
check(
  "extension reads the stepDataFlowHints.enabled setting",
  ext.includes("stepDataFlowHints.enabled"),
);
check(
  "extension gates inlay hints via provideInlayHints middleware",
  ext.includes("provideInlayHints"),
);
check(
  "extension refreshes hints live via onDidChangeInlayHints",
  ext.includes("onDidChangeInlayHints"),
);
check(
  "extension refreshes on configuration change",
  ext.includes("onDidChangeConfiguration"),
);

if (failures > 0) {
  console.error(`\n${failures} data-flow-hints gate check(s) failed.`);
  process.exit(1);
}
console.log("\nAll data-flow-hints gate checks passed.");
