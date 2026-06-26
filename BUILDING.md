# Building zio-bdd-tooling

## Prerequisites

| Tool | Version | Notes |
|------|---------|-------|
| JDK  | 21 (required by IntelliJ Platform 2024.3+; the LSP itself only needs 17+) | sdkman: `sdk install java 21-amzn` |
| sbt  | 1.10+ | sdkman: `sdk install sbt` |
| Node / npm | 20+ | VS Code extension only |
| Gradle | 8.8 (bundled via `./gradlew`) | IntelliJ plugin only |

## Build the LSP fat jar

```sh
sbt lsp/assembly
```

The output path is configured in `build.sbt`'s `assembly / assemblyOutputPath` —
`extensions/intellij/src/main/resources/bin/zio-bdd-lsp.jar`. Building the IntelliJ plugin
requires this jar to exist first; the VS Code extension doesn't bundle it (the extension looks
for it at `zio-bdd.lspBinaryPath`, on `PATH`, or falls back to an error — see
`extension.ts`'s `resolveLspLaunch`).

## Run the LSP server manually

```sh
sbt lsp/assembly
java -jar lsp/target/scala-3.3.4/zio-bdd-lsp.jar
```

It speaks LSP over stdio (Content-Length-framed JSON-RPC) and logs to stderr — stdout is
reserved for protocol traffic.

## Run against an unreleased zio-bdd core change

By default the LSP depends on the Maven Central release of `io.github.etacassiopeia:zio-bdd`.
To test against a local change on a pending core PR:

```sh
# In a zio-bdd checkout, on the branch with the change:
sbt publishLocal
# Note the version sbt prints, e.g. "1.1.0+9-d5ecdd8a-SNAPSHOT"

# Back in zio-bdd-tooling:
sbt -DzioBdd.version=1.1.0+9-d5ecdd8a-SNAPSHOT lsp/test
```

`sbt publishLocal` publishes to `~/.ivy2/local`, which sbt resolves from automatically — no
extra resolver configuration needed.

## Build the VS Code extension

```sh
cd extensions/vscode
npm install
npm run compile        # -> out/extension.js
npx vsce package       # -> zio-bdd-<version>.vsix
```

Install the `.vsix` via VS Code's "Install from VSIX..." command, or
`code --install-extension zio-bdd-<version>.vsix`.

The extension looks for the LSP at, in order:
1. `zio-bdd.lspBinaryPath` setting
2. A bundled `bin/zio-bdd-lsp(.jar)` next to the extension
3. `zio-bdd-lsp` on `PATH`

For a self-contained local install, copy `lsp/target/.../zio-bdd-lsp.jar` to
`extensions/vscode/bin/` before packaging.

## Build the IntelliJ plugin

```sh
sbt lsp/assembly   # must run first — build.gradle.kts verifies the jar exists
cd extensions/intellij
./gradlew buildPlugin   # -> build/distributions/zio-bdd-<version>.zip
```

Install via IntelliJ → Settings → Plugins → ⚙ → Install Plugin from Disk, pointing at the
generated zip. **No external plugins are required** — the plugin uses native PSI and has no
dependency on LSP4IJ or any other third-party plugin.

By default `build.gradle.kts` resolves IntelliJ Community 2024.3 from JetBrains' CDN — no
local IDE install needed. To build against a local IDE sandbox instead:

```sh
./gradlew buildPlugin -PzioBdd.intellij.localPath="/Applications/IntelliJ IDEA.app"
```

To also verify plugin structure (recommended before release):

```sh
./gradlew buildPlugin verifyPluginStructure --no-daemon
```

## Build a GraalVM native binary (optional)

`sbt-native-image` is configured for both `lsp` and `cli`. To produce a standalone binary
instead of a fat jar, install GraalVM JDK 21, then:

```sh
sbt lsp/nativeImage   # → bin/zio-bdd-lsp
sbt cli/nativeImage   # → bin/zio-bdd
```

The native binary starts in ~50 ms vs ~1–2 s for the jar. `resolveLspLaunch` in the VS Code
extension already prefers `bin/zio-bdd-lsp` over the jar if it exists at the expected path.
Native builds are not part of the standard release pipeline (which uses the jar for
platform-agnostic distribution).

## Tests

```sh
sbt test          # runs lsp + cli tests
```

## Releasing

Tooling versions follow the zio-bdd core version. Cutting a release is one command:

```sh
git tag v1.1.0
git push --tags
```

Pushing a `v*` tag triggers `.github/workflows/release.yml`, which runs four jobs:

| Job | What it does |
|-----|-------------|
| `build` | `sbt lsp/assembly` → VS Code `vsce package` → IntelliJ `buildPlugin`; uploads `.vsix`, `.zip`, and `lsp.jar` as artifacts |
| `release` | Creates a GitHub release with the `.vsix` and `.zip` attached and auto-generated release notes |
| `publish-vscode` | Publishes the `.vsix` to the VS Code Marketplace (and optionally Open VSX if `OVSX_PAT` is set) |
| `publish-intellij` | Downloads the pre-built `lsp.jar`, runs `signPlugin publishPlugin` to sign and publish to JetBrains Marketplace |

The version stamped into both `package.json` and `build.gradle.kts` is derived from the tag
(e.g. `v1.1.0` → `1.1.0`) at release time.

### Required GitHub secrets

Configure these in the repository's **Settings → Secrets and variables → Actions**:

| Secret | Used by | Notes |
|--------|---------|-------|
| `VSCE_PAT` | VS Code Marketplace publish | marketplace.visualstudio.com → user settings → Personal Access Tokens |
| `OVSX_PAT` | Open VSX (optional — job step skips if absent) | open-vsx.org → user settings → Access Tokens |
| `JETBRAINS_MARKETPLACE_TOKEN` | JetBrains Marketplace publish | plugins.jetbrains.com → developer account → Tokens |
| `CERTIFICATE_CHAIN` | Plugin signing — PEM cert chain | Generated with `openssl req -key private.pem -new -x509 -days 3650 -out chain.crt` |
| `PRIVATE_KEY` | Plugin signing — PEM private key | Generated with `openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:4096 -out private.pem` |
| `PRIVATE_KEY_PASSWORD` | Plugin signing — key passphrase | Passphrase used when generating the key (empty string if unencrypted) |
