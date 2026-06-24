# Building zio-bdd-tooling

## Prerequisites

| Tool | Version | Notes |
|------|---------|-------|
| JDK  | 17+ (21 recommended) | sdkman: `sdk install java 21-amzn` |
| sbt  | 1.10+ | sdkman: `sdk install sbt` |

## Build the LSP fat jar

```sh
sbt lsp/assembly
```

Output path is configured in `build.sbt`'s `assembly / assemblyOutputPath` — currently
`extensions/intellij/src/main/resources/bin/zio-bdd-lsp.jar`, anticipating the IntelliJ plugin
that will bundle it (not yet implemented; see README's "Status" section). If you just want the
jar without that directory existing, override the output path or create the directory first.

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

## Tests

```sh
sbt lsp/test
```
