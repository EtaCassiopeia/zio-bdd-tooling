// Gate for issue #37: the Rift imposter JSON schema must be a valid draft-07
// schema, accept real Rift imposters and reject malformed ones, be wired into
// package.json's jsonValidation, and stay byte-identical to the IntelliJ copy.
//
// Run: npm run test:schema   (from extensions/vscode)
// No test framework — a plain node script that exits non-zero on the first
// failure, so it is a cheap, dependency-light gate usable in CI.

import { readFileSync, existsSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, resolve } from "node:path";
import Ajv from "ajv";
import addFormats from "ajv-formats";

const here = dirname(fileURLToPath(import.meta.url));
const vscodeRoot = resolve(here, "..");
const repoRoot = resolve(vscodeRoot, "..", "..");

let failures = 0;
function check(name, cond) {
  if (cond) {
    console.log(`  ok  ${name}`);
  } else {
    console.error(`FAIL  ${name}`);
    failures++;
  }
}

const canonicalPath = resolve(vscodeRoot, "schemas", "rift-imposter.schema.json");
const intellijPath = resolve(
  repoRoot,
  "extensions",
  "intellij",
  "src",
  "main",
  "resources",
  "schemas",
  "rift-imposter.schema.json",
);

// ── Schema exists and is a valid draft-07 schema ────────────────────────────
check("canonical schema file exists", existsSync(canonicalPath));
const rawSchema = readFileSync(canonicalPath, "utf8");
const schema = JSON.parse(rawSchema);
check("declares draft-07 $schema", schema.$schema === "http://json-schema.org/draft-07/schema#");
check("has an $id (hostable url)", typeof schema.$id === "string" && schema.$id.startsWith("http"));

const ajv = new Ajv({ allErrors: true, strict: false });
addFormats(ajv);
let validate;
try {
  validate = ajv.compile(schema);
  check("schema compiles under ajv", true);
} catch (e) {
  check(`schema compiles under ajv (${e.message})`, false);
}

// ── Accepts real Rift imposters (from rift RiftProtocol.scala) ──────────────
const good = {
  "minimal GET stub imposter": {
    port: 0,
    protocol: "http",
    stubs: [
      {
        predicates: [{ equals: { method: "GET", path: "/widgets" } }],
        responses: [{ is: { statusCode: 200, body: "ok" } }],
      },
    ],
  },
  "imposter with _rift.flowState + recording + defaultResponse": {
    port: 0,
    protocol: "http",
    name: "cart-space",
    recordRequests: true,
    defaultResponse: { statusCode: 404 },
    stubs: [],
    _rift: { flowState: { backend: "inmemory", ttlSeconds: 300, flowIdSource: "imposter_port" } },
  },
  "stateful scenario stub": {
    protocol: "http",
    stubs: [
      {
        scenarioName: "checkout",
        requiredScenarioState: "Started",
        newScenarioState: "Paid",
        predicates: [{ equals: { method: "POST", path: "/pay" } }],
        responses: [{ is: { statusCode: 200 } }],
      },
    ],
  },
  "fault response via _rift.fault": {
    protocol: "http",
    stubs: [
      {
        predicates: [{ equals: { path: "/flaky" } }],
        responses: [{ is: { statusCode: 200 }, _rift: { fault: { tcp: "CONNECTION_RESET_BY_PEER" } } }],
      },
    ],
  },
};

// ── Rejects malformed imposters ─────────────────────────────────────────────
const bad = {
  "missing protocol": { port: 0, stubs: [] },
  "port as string": { protocol: "http", port: "8080", stubs: [] },
  "stubs not an array": { protocol: "http", stubs: { predicates: [] } },
  "predicates not an array": {
    protocol: "http",
    stubs: [{ predicates: { equals: {} }, responses: [] }],
  },
  "protocol not a known value": { protocol: "ftp", stubs: [] },
  "port below range": { protocol: "http", port: -1, stubs: [] },
  "port above range": { protocol: "http", port: 65536, stubs: [] },
  "recordRequests as string": { protocol: "http", recordRequests: "true", stubs: [] },
  "unknown tcp fault token": {
    protocol: "http",
    stubs: [{ responses: [{ is: { statusCode: 200 }, _rift: { fault: { tcp: "NOT_A_REAL_FAULT" } } }] }],
  },
};

check("has good and bad fixtures", Object.keys(good).length > 0 && Object.keys(bad).length > 0);

if (validate) {
  for (const [name, doc] of Object.entries(good)) {
    const ok = validate(doc);
    check(`accepts ${name}`, ok === true || (console.error(JSON.stringify(validate.errors)), false));
  }
  for (const [name, doc] of Object.entries(bad)) {
    check(`rejects ${name}`, validate(doc) === false);
  }
}

// ── IntelliJ copy is byte-identical (drift guard) ───────────────────────────
check("intellij schema copy exists", existsSync(intellijPath));
if (existsSync(intellijPath)) {
  check("intellij copy is byte-identical to canonical", readFileSync(intellijPath, "utf8") === rawSchema);
}

// ── VSCode wiring: package.json jsonValidation ──────────────────────────────
const pkg = JSON.parse(readFileSync(resolve(vscodeRoot, "package.json"), "utf8"));
const jv = pkg.contributes && pkg.contributes.jsonValidation;
check("package.json declares contributes.jsonValidation", Array.isArray(jv) && jv.length > 0);
if (Array.isArray(jv) && jv.length) {
  const entry = jv.find((e) => Array.isArray(e.fileMatch) && e.fileMatch.some((m) => m.includes("imposter")));
  check("a jsonValidation entry targets imposter files", !!entry);
  if (entry) {
    // Assert every documented association glob is present — a `some(includes("imposter"))`
    // check would pass even if the directory glob were dropped, silently regressing it.
    for (const glob of ["*.imposter.json", "*.rift.json", "**/imposters/*.json"]) {
      check(`jsonValidation fileMatch includes ${glob}`, entry.fileMatch.includes(glob));
    }
    const url = resolve(vscodeRoot, entry.url);
    check("jsonValidation.url points to an existing bundled schema", existsSync(url) && url === canonicalPath);
  }
}

// ── IntelliJ wiring is present (structural) ─────────────────────────────────
const pluginXml = readFileSync(
  resolve(repoRoot, "extensions", "intellij", "src", "main", "resources", "META-INF", "plugin.xml"),
  "utf8",
);
check("plugin.xml depends on the JSON module", pluginXml.includes("com.intellij.modules.json"));
check(
  "plugin.xml registers a JsonSchema ProviderFactory",
  pluginXml.includes("JavaScript.JsonSchema") && pluginXml.includes("RiftImposterSchemaProviderFactory"),
);

if (failures > 0) {
  console.error(`\n${failures} schema gate check(s) failed.`);
  process.exit(1);
}
console.log("\nAll schema gate checks passed.");
