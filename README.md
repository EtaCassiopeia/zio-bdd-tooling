# zio-bdd-tooling

LSP server, VSCode extension, and IntelliJ plugin for
[zio-bdd](https://github.com/EtaCassiopeia/zio-bdd) — the Scala 3 + ZIO 2 BDD test framework.

Implements [zio-bdd#93](https://github.com/EtaCassiopeia/zio-bdd/issues/93)'s LSP/CLI/IDE
tooling RFC, scoped down to what's achievable without a compile step (no SemanticDB/BSP yet —
see "Status" below).

## Status

This repo currently has:

- **`lsp/`** — a working Scala 3 LSP server (diagnostics, go-to-definition, hover, completion,
  document outline, code lens) that statically scans `.scala`/`.feature` source text — no `sbt
  compile` required, feedback within ~1s of saving a file.
- **`extensions/vscode/`** — a VSCode extension: Gherkin syntax highlighting, LSP client,
  Test Explorer integration, and the commands the LSP's code lenses invoke.
- **`extensions/intellij/`** — an IntelliJ plugin: Gherkin syntax highlighting + customisable
  colour scheme, wired to the LSP via the [LSP4IJ](https://github.com/redhat-developer/lsp4ij)
  client plugin (not a from-scratch PSI implementation — see "Deferred" below).

Not yet implemented (tracked as follow-ups against zio-bdd#93, deliberately deferred — see that
issue's M2/M3 phasing):

- CLI (`zio-bdd check`/`snippet`/`list`).
- GraalVM native-image distribution — both extensions currently launch the LSP via
  `java -jar zio-bdd-lsp.jar` rather than a standalone binary.
- SemanticDB/BSP-aware resolution (would let the LSP reuse zio-bdd's actual runtime
  `StepRegistry` instead of a static approximation — requires loading compiled user classes).
- Native IntelliJ PSI plugin (own lexer/parser/index walking Scala PSI directly, per #93's
  original architecture decision) — using the generic LSP4IJ client instead is far less work
  and was judged good enough for now; revisit if its run/debug/refactor support proves limiting.
- Run configurations / gutter run icons / "generate step registry" action for IntelliJ —
  the LSP4IJ-based plugin currently only does syntax highlighting + the LSP-provided features
  (diagnostics, completion, hover, definition, code lens via the generic LSP4IJ UI).

## Design note: no hand-copied extractor patterns

Earlier drafts of this LSP hand-copied each built-in extractor's regex (`int`, `string`,
`double`, ...) into a local table. That's a correctness trap: a hand-copy can silently drift
from the pattern zio-bdd's runtime actually matches against. This LSP instead resolves built-in
extractor patterns from `zio.bdd.core.step.DefaultTypedExtractor.byName` — added to zio-bdd core
specifically so tooling has one source of truth (see
[zio-bdd#103](https://github.com/EtaCassiopeia/zio-bdd/pull/103)). `StepExtractorSpec` has a
regression test pinning this.

## Project structure

```
zio-bdd-tooling/
├── build.sbt
├── lsp/                               # Scala 3 LSP server
│   └── src/
│       ├── main/scala/zio/bdd/lsp/
│       │   ├── Main.scala             # stdio entrypoint
│       │   ├── Model.scala            # StepDefinition, ExtractorInfo
│       │   ├── StepExtractor.scala    # source-text → StepDefinition
│       │   ├── StepMatcher.scala      # regex match + Levenshtein hint
│       │   ├── WorkspaceIndex.scala   # in-memory step + feature index
│       │   ├── GherkinBridge.scala    # sync adapter to zio-bdd-gherkin
│       │   ├── ZIOBddServer.scala     # LSP4J LanguageServer impl
│       │   ├── ZIOBddLanguageServer.scala  # stdio launcher
│       │   └── handlers/
│       │       ├── DiagnosticsHandler.scala
│       │       ├── DefinitionHandler.scala
│       │       ├── HoverHandler.scala
│       │       ├── CompletionHandler.scala
│       │       ├── CodeLensHandler.scala
│       │       └── DocumentSymbolsHandler.scala
│       └── test/scala/zio/bdd/lsp/
│           ├── StepExtractorSpec.scala
│           ├── StepMatcherSpec.scala
│           └── handlers/CodeLensHandlerSpec.scala
└── extensions/
    ├── vscode/                        # TypeScript VSCode extension
    │   ├── src/
    │   │   ├── extension.ts           # LSP client + activation
    │   │   ├── commands.ts            # generateRegistry, restart, showOutput
    │   │   └── testController.ts      # VSCode Test Explorer
    │   ├── syntaxes/gherkin.tmLanguage.json
    │   ├── package.json
    │   └── tsconfig.json
    └── intellij/                      # Kotlin IntelliJ plugin (LSP4IJ-based)
        ├── build.gradle.kts
        ├── settings.gradle.kts
        ├── gradlew / gradlew.bat
        └── src/main/
            ├── kotlin/zio/bdd/intellij/
            │   ├── ZioBddLspServerDefinition.kt
            │   └── lang/                # FileType, Lexer, SyntaxHighlighter, ColorSettingsPage
            └── resources/
                ├── META-INF/plugin.xml
                └── bin/zio-bdd-lsp.jar  ← produced by `sbt lsp/assembly`
```

## How it works

1. **Scans `.scala` files** in the workspace using a regex + paren-counting source parser (not
   scalameta — see `StepExtractor`'s doc comment for why). Extracts `StepDefinition` records
   from `Given("..." / int / string)` call sites, including `GivenS`/`WhenS`/`ThenS`,
   `oneOf(...)`, `optional(...)`, `table[T]`, and `regex(...)`.
2. **Parses `.feature` files** using **zio-bdd-gherkin** — the same parser the framework uses
   at test runtime.
3. **Responds to file-save events** — diagnostics and completions update within ~1s of saving.
4. **Code lens** "▶ Run feature" / "▶ Run scenario" run real zio-bdd CLI flags
   (`--feature-file`, `--scenario-name` — see zio-bdd's `docs/running.md`), not synthetic
   commands.

## Building

```sh
sbt lsp/assembly   # -> lsp/target/.../zio-bdd-lsp.jar (also see build.sbt's assemblyOutputPath)
sbt lsp/test
```

By default the LSP depends on the latest released `io.github.etacassiopeia:zio-bdd` /
`zio-bdd-gherkin` artifacts. To develop against an unreleased zio-bdd core change, run `sbt
publishLocal` in a zio-bdd checkout and pass `-DzioBdd.version=<printed-version>` to sbt here.

See [BUILDING.md](BUILDING.md) for the VSCode extension and IntelliJ plugin build steps.

## Tests

```sh
sbt lsp/test
```
