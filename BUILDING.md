# Building zio-bdd-tooling

## Prerequisites

| Tool | Version | Notes |
|------|---------|-------|
| JDK  | 21 (required by IntelliJ Platform 2024.3+; the LSP itself only needs 17+) | sdkman: `sdk install java 21-amzn` |
| sbt  | 1.10+ | sdkman: `sdk install sbt` |
| Node / npm | 20+ | VSCode extension only |
| Gradle | 8.8 (bundled via `./gradlew`) | IntelliJ plugin only |

## Build the LSP fat jar

```sh
sbt lsp/assembly
```

Output path is configured in `build.sbt`'s `assembly / assemblyOutputPath` —
`extensions/intellij/src/main/resources/bin/zio-bdd-lsp.jar`, since the IntelliJ plugin bundles
it. Building the IntelliJ plugin requires this jar to exist first; the VSCode extension doesn't
bundle it (the extension looks for it at `zio-bdd.lspBinaryPath`, on `PATH`, or falls back to an
error — see `extension.ts`'s `resolveLspLaunch`).

## Run against an unreleased zio-bdd core change

The LSP depends on `io.github.etacassiopeia:zio-bdd` / `zio-bdd-gherkin`. By default that's the
latest Maven Central release. To test against a local change (e.g. a pending core PR):

```sh
# In a zio-bdd checkout, on the branch with the change:
sbt publishLocal
# Note the version sbt prints, e.g. "1.0.0+9-d5ecdd8a-SNAPSHOT"

# Back in zio-bdd-tooling:
sbt -DzioBdd.version=1.0.0+9-d5ecdd8a-SNAPSHOT lsp/test
```

`sbt publishLocal` publishes to `~/.ivy2/local`, which sbt resolves from automatically — no
extra resolver configuration needed.

## Run the LSP server manually

```sh
sbt lsp/assembly
java -jar lsp/target/scala-3.3.4/zio-bdd-lsp.jar
```

It speaks LSP over stdio (Content-Length-framed JSON-RPC) and logs to stderr — stdout is
reserved for protocol traffic.

## Build the VSCode extension

```sh
cd extensions/vscode
npm install
npm run compile        # -> out/extension.js
npx vsce package --allow-missing-repository   # -> zio-bdd-0.1.0.vsix
```

Install the `.vsix` via VSCode's "Install from VSIX..." command, or `code --install-extension
zio-bdd-0.1.0.vsix`. The extension looks for the LSP at, in order: `zio-bdd.lspBinaryPath`
setting, a bundled `bin/zio-bdd-lsp(.jar)` next to the extension (not currently packaged into
the `.vsix` — copy `lsp/target/.../zio-bdd-lsp.jar` to `extensions/vscode/bin/` first if you
want a self-contained install), then `zio-bdd-lsp` on `PATH`.

## Build the IntelliJ plugin

```sh
sbt lsp/assembly   # must run first — build.gradle.kts verifies the jar exists
cd extensions/intellij
./gradlew buildPlugin   # -> build/distributions/zio-bdd-intellij-0.9.0.zip
```

Install via IntelliJ → Settings → Plugins → ⚙ → Install Plugin from Disk, pointing at the
generated zip. **No external plugins are required** — the plugin uses native PSI and has no
dependency on LSP4IJ or any other third-party plugin.

By default `build.gradle.kts` resolves IntelliJ Community 2024.3 from JetBrains' CDN — no
local IDE install needed. To build against a local IDE sandbox instead (e.g. no CDN access or
testing against an unreleased IDE version), pass:

```sh
./gradlew buildPlugin -PzioBdd.intellij.localPath="/Applications/IntelliJ IDEA.app"
```

Verified end-to-end: `./gradlew buildPlugin verifyPluginStructure verifyPluginProjectConfiguration`
all pass clean against the CDN defaults above.

## Tests

```sh
sbt lsp/test
```

## Releasing

Cutting a release is one command:

```sh
git tag v0.1.0
git push --tags
```

Pushing a `v*` tag triggers `.github/workflows/release.yml`, which runs four jobs:

| Job | What it does |
|-----|-------------|
| `build` | publishLocal zio-bdd → `sbt lsp/assembly` → VS Code `vsce package` → IntelliJ `buildPlugin`; uploads `.vsix` + `.zip` + `lsp.jar` as artifacts |
| `release` | Creates a GitHub release with the `.vsix` and `.zip` attached and auto-generated release notes |
| `publish-vscode` | Publishes the `.vsix` to the VS Code Marketplace (and optionally Open VSX) |
| `publish-intellij` | Downloads the pre-built `lsp.jar`, runs `signPlugin publishPlugin` to sign and publish to JetBrains Marketplace |

The version stamped into both `package.json` and `build.gradle.kts` is derived from the tag
(e.g. `v0.1.0` → `0.1.0`) at release time.

### Required GitHub secrets

Configure these in the repository's **Settings → Secrets and variables → Actions**:

| Secret | Used by | How to obtain |
|--------|---------|---------------|
| `VSCE_PAT` | VS Code Marketplace publish | marketplace.visualstudio.com → user settings → Personal Access Tokens |
| `OVSX_PAT` | Open VSX (optional — job skips if absent) | open-vsx.org → user settings → Access Tokens |
| `JETBRAINS_MARKETPLACE_TOKEN` | JetBrains Marketplace publish | plugins.jetbrains.com → developer account → Tokens |
| `CERTIFICATE_CHAIN` | Plugin signing — PEM cert chain | JetBrains signing docs: [plugins.jetbrains.com/docs/intellij/plugin-signing.html](https://plugins.jetbrains.com/docs/intellij/plugin-signing.html) |
| `PRIVATE_KEY` | Plugin signing — PEM private key | (same doc; generated with `openssl`) |
| `PRIVATE_KEY_PASSWORD` | Plugin signing — passphrase (may be empty string) | (same) |

The `publish-vscode` job will fail cleanly if `VSCE_PAT` is not yet set. The `publish-intellij`
job will fail if any of the four JetBrains secrets are missing. The `build` and `release` jobs
require no secrets and always run on a tag push.
