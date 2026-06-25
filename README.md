# zio-bdd-tooling

LSP server, VS Code extension, and IntelliJ plugin for
[zio-bdd](https://github.com/EtaCassiopeia/zio-bdd) — the Scala 3 + ZIO 2 BDD test framework.

## What's implemented

| Component | Status |
|-----------|--------|
| **`lsp/`** — Scala 3 LSP server | ✅ complete |
| **`extensions/vscode/`** — VS Code extension | ✅ complete |
| **`extensions/intellij/`** — IntelliJ plugin (native PSI, no external plugins) | ✅ complete |
| **`cli/`** — `zio-bdd check` / `snippet` / `list` CLI | 🚧 in progress |
| GraalVM native-image distribution | 🔜 planned |

## Architecture

Three protocols cooperate to give the tooling accurate, low-latency feedback:

```
┌─────────────────────────────────────────────────────────────────────────────┐
│  Editor (IntelliJ or VS Code)                                               │
│                                                                             │
│  ┌──────────────┐    LSP (JSON-RPC / stdio)    ┌─────────────────────────┐ │
│  │ IDE plugin   │◄───────────────────────────►│ zio-bdd-lsp.jar         │ │
│  │              │                              │  • static source scan   │ │
│  │  (IntelliJ:  │    BSP (JSON-RPC / socket)  │  • hover / goto / diag  │ │
│  │   native PSI │◄───────────────────────────►│  • completion / lens    │ │
│  │   VS Code:   │    (sbt/Bloop BSP server)   │                         │ │
│  │   LSP client)│                              │  BspClient ─────────── │ │
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

`zio-bdd-lsp` is a standard LSP server speaking JSON-RPC over stdio. It statically scans
`.scala` files for `Given`/`When`/`Then` call sites and `.feature` files using zio-bdd's own
Gherkin parser — no `sbt compile` needed. Responds within ~1s of a file save.

**Capabilities provided:**
- `textDocument/hover` — keyword, display text, parameter types, source location
- `textDocument/definition` — jump to Scala step definition
- `textDocument/completion` — step suggestions in `.feature`, step skeletons in `.scala`
- `textDocument/publishDiagnostics` — unmatched steps with closest-match hints
- `textDocument/codeLens` — "▶ Run scenario" / "▶ Run feature" lenses
- `textDocument/codeAction` — "Create step definition" action
- `textDocument/documentSymbol` — outline for `.feature` files

**VS Code** uses this server as its primary language intelligence layer — LSP was designed by
Microsoft for VS Code and is the idiomatic path there. The VS Code extension is a thin LSP
client (~50 lines) plus a TextMate grammar for syntax highlighting.

**IntelliJ** replaces the LSP for all editor features with a native PSI implementation (see
below). The LSP server still runs via BSP — it's what the BSP class-loading subprocess is
packaged in.

### BSP — Build Server Protocol

BSP is editor-agnostic (developed by JetBrains + Scala Center, used by Metals/sbt/Bloop).
`BspClient` connects to the project's existing sbt/Bloop BSP socket and listens for
`buildTarget/didCompile` notifications. On each compile it:

1. Reads the exact source roots from BSP (`buildTarget/sources`)
2. Re-scans those roots for step definitions
3. Launches `StepLoader` as a subprocess on the test classpath (`buildTarget/scalacOptions`)
   to load runtime-accurate step patterns via `ZIOSteps.allDefinitions`

BSP is used the same way in both IntelliJ and VS Code (via the LSP server). The IntelliJ
plugin also has `BspStepLoader`, which does the same subprocess launch from the IDE side
using `OrderEnumerator` for the classpath.

### PSI — Program Structure Interface (IntelliJ-only)

PSI is JetBrains' proprietary API for representing source files as typed element trees. It
has no VS Code equivalent.

The IntelliJ plugin has a **full native PSI implementation** for `.feature` files — no
external plugins required:

| PSI layer | Implementation |
|-----------|---------------|
| Lexer | `ZioBddLexer` — hand-written, handles keywords, tags, `@flags(k=v)`, doc strings, tables |
| Parser | `ZioBddParser` — builds a typed PSI tree (`ZioBddFile`, `ZioBddStep`, `ZioBddScenarioHeader`, `ZioBddFeatureHeader`) |
| Syntax highlighting | `ZioBddSyntaxHighlighter` + `ZioBddColorSettingsPage` (customisable colour scheme) |
| Annotator | `ZioBddAnnotator` — matches each step against `ZioBddStepCache`; warns on mismatches with closest-match hints |
| Go-to-definition | `ZioBddGotoStepHandler` — Cmd+Click → Scala source line |
| Completion | `ZioBddCompletionContributor` — step suggestions with typed parameter tab stops |
| Hover docs | `ZioBddDocumentationProvider` — keyword, display text, parameter table, source location |
| Quick-fix | `ZioBddGenerateStepFix` — "Create step definition" inserts a `{ ??? }` stub in the target Scala file |
| Gutter icons | `ZioBddLineMarkerProvider` — ▶ icons on Scenario/Feature lines |
| Run configs | `ZioBddRunConfigurationType` + `ZioBddRunConfigurationProducer` — right-click → run a specific scenario |
| Step cache | `ZioBddStepCache` — per-project service; static scan + BSP runtime upgrade |
| File watcher | `ZioBddFileChangeListener` — invalidates cache on `.scala` change |

The PSI approach gives IntelliJ deeper integration than LSP: run configurations, gutter
icons, refactor support, and full IDE-native navigation — none of which LSP4IJ's generic
client would provide.

### VS Code vs IntelliJ capability comparison

| Feature | VS Code (LSP) | IntelliJ (native PSI) |
|---------|--------------|----------------------|
| Syntax highlighting | TextMate grammar (regex-based) | `ZioBddLexer` / `ZioBddSyntaxHighlighter` (typed tokens) |
| Hover | LSP `textDocument/hover` | `ZioBddDocumentationProvider` |
| Go-to-definition | LSP `textDocument/definition` | `ZioBddGotoStepHandler` |
| Diagnostics | LSP `textDocument/publishDiagnostics` | `ZioBddAnnotator` |
| Completion | LSP `textDocument/completion` | `ZioBddCompletionContributor` |
| Quick-fix | LSP `textDocument/codeAction` | `ZioBddGenerateStepFix` (IntentionAction) |
| Run scenario | VS Code Test Explorer API (`vscode.TestController`) | `ZioBddRunConfigurationType` + gutter ▶ icons |
| BSP integration | Via LSP server (`BspClient` inside `zio-bdd-lsp`) | Both LSP server + `BspStepLoader` in the plugin |
| Runtime step accuracy | Via BSP → LSP → `BspClassLoader` subprocess | Via BSP → `BspStepLoader` subprocess |
| External plugin dependency | None | None (LSP4IJ was removed in v0.9.0) |

VS Code is *better suited* to LSP because that's the protocol it was designed around — the
extension is trivially thin. IntelliJ is *better at depth*: run configs, gutter icons, custom
refactors, and precise PSI manipulation require the native API regardless of LSP quality.

## Design notes

### No hand-copied extractor patterns

The LSP resolves built-in extractor patterns from `zio.bdd.core.step.DefaultTypedExtractor.byName`
— added to the zio-bdd core specifically so tooling has one source of truth
([zio-bdd#103](https://github.com/EtaCassiopeia/zio-bdd/pull/103)). `StepExtractorSpec` has a
regression test pinning this.

### Runtime accuracy via BSP class-loading

Static source scanning (`StepExtractor`) gives immediate results without compiling but
approximates extractor patterns from source text. After each `sbt compile`, `BspClassLoader`
launches `StepLoader` as a subprocess on the test classpath. `StepLoader` loads each
`ZIOSteps` subclass via reflection and calls `ZIOSteps.allDefinitions` to get the
runtime-authoritative `StepSummary` records. `WorkspaceIndex.mergeRuntimeSteps` upgrades the
static scan results with the accurate regex patterns.

### No `sbt generateStepRegistry` or `sbt zioBddSnippets`

Those were build auto-plugins in zio-bdd's own build — not part of the published library.
They're not needed here: the LSP does live source scanning without an intermediate JSON file,
and `CompletionHandler.unimplementedStepCompletion` already surfaces step skeletons as
completion items.

## Project structure

```
zio-bdd-tooling/
├── build.sbt
├── lsp/                                       # Scala 3 LSP server
│   └── src/main/scala/zio/bdd/lsp/
│       ├── Main.scala                         # stdio entrypoint
│       ├── Model.scala                        # StepDefinition, ExtractorInfo
│       ├── StepExtractor.scala                # source-text → StepDefinition (no compile)
│       ├── StepMatcher.scala                  # regex match + Levenshtein hint
│       ├── WorkspaceIndex.scala               # in-memory step + feature index
│       ├── GherkinBridge.scala                # sync adapter to zio-bdd-gherkin
│       ├── ZIOBddServer.scala                 # LSP4J LanguageServer implementation
│       ├── ZIOBddLanguageServer.scala         # stdio launcher
│       ├── bsp/
│       │   ├── BspClient.scala                # BSP socket client + compile listener
│       │   ├── BspClassLoader.scala           # launches StepLoader subprocess
│       │   ├── BspConnectionFile.scala        # reads .bsp/*.json
│       │   ├── BspJsonRpc.scala               # minimal BSP JSON-RPC codec
│       │   └── StepLoader.scala               # subprocess entry point (ClassGraph scan)
│       └── handlers/
│           ├── DiagnosticsHandler.scala
│           ├── DefinitionHandler.scala
│           ├── HoverHandler.scala
│           ├── CompletionHandler.scala
│           ├── CodeLensHandler.scala
│           ├── CodeActionHandler.scala
│           └── DocumentSymbolsHandler.scala
├── extensions/
│   ├── vscode/                                # TypeScript VS Code extension
│   │   ├── src/
│   │   │   ├── extension.ts                   # LSP client + activation (~50 lines)
│   │   │   ├── commands.ts                    # restart, showOutput
│   │   │   └── testController.ts              # VS Code Test Explorer integration
│   │   ├── syntaxes/gherkin.tmLanguage.json   # TextMate grammar (syntax highlighting)
│   │   ├── package.json
│   │   └── tsconfig.json
│   └── intellij/                              # Kotlin IntelliJ plugin (native PSI, no LSP4IJ)
│       ├── build.gradle.kts
│       └── src/main/kotlin/zio/bdd/intellij/
│           ├── lang/
│           │   ├── psi/
│           │   │   ├── ZioBddFile.kt
│           │   │   ├── ZioBddStep.kt
│           │   │   ├── ZioBddScenarioHeader.kt
│           │   │   ├── ZioBddFeatureHeader.kt
│           │   │   └── ZioBddCompositeElement.kt
│           │   ├── ZioBddLanguage.kt
│           │   ├── ZioBddFileType.kt
│           │   ├── ZioBddLexer.kt
│           │   ├── ZioBddParser.kt
│           │   ├── ZioBddParserDefinition.kt
│           │   ├── ZioBddTokenTypes.kt
│           │   ├── ZioBddElementTypes.kt
│           │   ├── ZioBddSyntaxHighlighter.kt
│           │   ├── ZioBddSyntaxHighlighterFactory.kt
│           │   ├── ZioBddColorSettingsPage.kt
│           │   ├── ZioBddAnnotator.kt          # diagnostics + quick-fix attachment
│           │   ├── ZioBddGotoStepHandler.kt    # go-to-definition
│           │   ├── ZioBddCompletionContributor.kt
│           │   ├── ZioBddDocumentationProvider.kt  # hover docs
│           │   ├── ZioBddGenerateStepFix.kt    # "Create step definition" intent
│           │   ├── ZioBddLineMarkerProvider.kt # gutter ▶ icons
│           │   ├── ZioBddStepCache.kt          # per-project step cache (static + BSP)
│           │   ├── ZioBddFileChangeListener.kt # cache invalidation on .scala save
│           │   ├── KtStepExtractor.kt          # static .scala → KtStepDefinition
│           │   └── BspStepLoader.kt            # subprocess launcher (OrderEnumerator cp)
│           └── execution/
│               ├── ZioBddRunConfigurationType.kt
│               ├── ZioBddRunConfigurationFactory.kt
│               ├── ZioBddRunConfiguration.kt
│               ├── ZioBddRunConfigurationEditor.kt
│               └── ZioBddRunConfigurationProducer.kt
```

## Building

See [BUILDING.md](BUILDING.md) for full build instructions.

Quick start:

```sh
sbt lsp/test                   # run LSP server tests
sbt lsp/assembly               # build fat jar (required before IntelliJ plugin build)

cd extensions/vscode && npm install && npm run compile  # VS Code extension
cd extensions/intellij && ./gradlew buildPlugin         # IntelliJ plugin
```
