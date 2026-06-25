# zio-bdd-tooling

Language tooling for [zio-bdd](https://github.com/EtaCassiopeia/zio-bdd) — the Scala 3 + ZIO 2 BDD test framework. Includes a Scala LSP server, VS Code extension, IntelliJ plugin, and CLI.

## Features

- **Syntax highlighting** for `.feature` files — keywords, tags, `@flags(k=v)`, doc strings, tables; customisable colour scheme in IntelliJ
- **Diagnostics** — unmatched steps highlighted with closest-match hints
- **Go-to-definition** — Cmd+Click a step → jump to the Scala source line
- **Hover** — parameter types and extractor names for matched steps
- **Completion** — step suggestions in `.feature` files; step skeletons in `.scala` files
- **Quick-fix** — "Create step definition" generates a `{ ??? }` stub in the target Scala file
- **Run configurations** — gutter ▶ icons and right-click → run a specific scenario (IntelliJ); Test Explorer integration (VS Code)
- **BSP integration** — after each `sbt compile`, runtime-accurate step patterns are loaded from the user's compiled classes via a subprocess; no approximation from source text
- **CLI** — `zio-bdd check` / `snippet` / `list` for CI workflows and scripting

No compile step required. Static source scanning gives instant feedback; BSP upgrades accuracy after each build.

## Architecture

Three protocols cooperate to give accurate, low-latency feedback:

```
┌─────────────────────────────────────────────────────────────────────────────┐
│  Editor (IntelliJ or VS Code)                                               │
│                                                                             │
│  ┌──────────────┐    LSP (JSON-RPC / stdio)    ┌─────────────────────────┐ │
│  │ IDE plugin   │◄───────────────────────────►│ zio-bdd-lsp             │ │
│  │              │                              │  • static source scan   │ │
│  │  (IntelliJ:  │    BSP (JSON-RPC / socket)  │  • hover / goto / diag  │ │
│  │   native PSI │◄───────────────────────────►│  • completion / lens    │ │
│  │   VS Code:   │    (sbt/Bloop BSP server)   │                         │ │
│  │   LSP client)│                              │  BspClient              │ │
│  └──────────────┘                              │  BspClassLoader         │ │
│                                                └─────────────────────────┘ │
│                                                           │                 │
│                                          java subprocess  │                 │
│                                          (StepLoader)     ▼                 │
│                                                ┌──────────────────────┐    │
│                                                │ User's test JVM      │    │
│                                                │  ZIOSteps subclasses │    │
│                                                │  → runtime-accurate  │    │
│                                                │    step patterns     │    │
│                                                └──────────────────────┘    │
└─────────────────────────────────────────────────────────────────────────────┘
```

### LSP — Language Server Protocol

`zio-bdd-lsp` speaks JSON-RPC over stdio and provides:

| Capability | Description |
|-----------|-------------|
| `textDocument/hover` | Keyword, display text, parameter types, source location |
| `textDocument/definition` | Jump to Scala step definition |
| `textDocument/completion` | Step suggestions in `.feature`; step skeletons in `.scala` |
| `textDocument/publishDiagnostics` | Unmatched steps with closest-match hints |
| `textDocument/codeLens` | "▶ Run scenario" / "▶ Run feature" lenses |
| `textDocument/codeAction` | "Create step definition" action |
| `textDocument/documentSymbol` | File outline for `.feature` files |

**VS Code** uses this server as its primary intelligence layer — LSP was designed by Microsoft for VS Code and is the idiomatic path there. The extension is a thin LSP client plus a TextMate grammar for syntax highlighting.

**IntelliJ** replaces the LSP for all editor features with a native PSI implementation. The LSP server still runs via BSP — it's the jar that the BSP class-loading subprocess uses.

### BSP — Build Server Protocol

BSP is editor-agnostic (developed by JetBrains + Scala Center, used by Metals/sbt/Bloop). After each `sbt compile`, `BspClient` connects to the project's existing BSP socket, reads the exact source roots and test classpath, then launches `StepLoader` as a subprocess. `StepLoader` loads each `ZIOSteps` subclass via reflection and calls `ZIOSteps.allDefinitions` to get runtime-authoritative step patterns, which upgrade the static-scan results in `WorkspaceIndex`.

Both IntelliJ and VS Code (via the LSP server) use this path. The IntelliJ plugin also has a direct `BspStepLoader` that does the same subprocess launch using `OrderEnumerator` for the classpath.

### PSI — Program Structure Interface (IntelliJ-only)

PSI is JetBrains' proprietary API for typed source element trees. The IntelliJ plugin has a full native PSI implementation — no external plugins required:

| Layer | What it does |
|-------|-------------|
| Lexer + Parser | Typed PSI tree: `ZioBddFile`, `ZioBddStep`, `ZioBddScenarioHeader`, `ZioBddFeatureHeader` |
| Syntax highlighting | Customisable colour scheme in Settings → Editor → Color Scheme |
| Annotator | Matches each step against `ZioBddStepCache`; warns on mismatches |
| Go-to-definition | Cmd+Click → Scala source line |
| Completion | Step suggestions with typed parameter tab stops |
| Hover docs | Keyword, display text, parameter table, source location |
| Quick-fix | "Create step definition" inserts a `{ ??? }` stub |
| Gutter icons | ▶ on every Scenario/Feature line |
| Run configurations | Right-click → run a specific scenario via sbt |
| Step cache | Per-project service; static scan + BSP runtime upgrade |

### VS Code vs IntelliJ

| Feature | VS Code (LSP) | IntelliJ (native PSI) |
|---------|--------------|----------------------|
| Syntax highlighting | TextMate grammar | Custom lexer (typed tokens) |
| Hover | LSP server | `ZioBddDocumentationProvider` |
| Go-to-definition | LSP server | `ZioBddGotoStepHandler` |
| Diagnostics | LSP server | `ZioBddAnnotator` |
| Completion | LSP server | `ZioBddCompletionContributor` |
| Quick-fix | LSP server (code action) | `ZioBddGenerateStepFix` |
| Run scenario | VS Code Test Explorer | Run configurations + gutter ▶ icons |
| External plugin dependency | None | None |

VS Code is better suited to LSP — the extension is trivially thin and the heavy logic lives in the server. IntelliJ requires the native API for run configurations, gutter icons, and precise PSI manipulation.

## Design notes

### Runtime accuracy via BSP class-loading

Static source scanning gives immediate results without compiling but approximates extractor patterns from source text. After each `sbt compile`, `BspClassLoader` launches `StepLoader` on the test classpath. `StepLoader` loads each `ZIOSteps` subclass via reflection and calls `ZIOSteps.allDefinitions` to get runtime-authoritative `StepSummary` records. `WorkspaceIndex.mergeRuntimeSteps` then upgrades the static scan results.

### No hand-copied extractor patterns

Built-in extractor patterns are resolved from `zio.bdd.core.step.DefaultTypedExtractor.byName` in the zio-bdd core — one source of truth, no risk of silent drift.

## Building

See [BUILDING.md](BUILDING.md) for full instructions.

```sh
sbt lsp/test                   # run LSP server tests
sbt lsp/assembly               # build fat jar (required before IntelliJ plugin build)

cd extensions/vscode && npm install && npm run compile  # VS Code extension
cd extensions/intellij && ./gradlew buildPlugin         # IntelliJ plugin
```

## Releasing

```sh
git tag v0.1.0 && git push --tags
```

Triggers the release pipeline: build → GitHub release → VS Code Marketplace → JetBrains Marketplace. See [BUILDING.md § Releasing](BUILDING.md#releasing) for required secrets.
