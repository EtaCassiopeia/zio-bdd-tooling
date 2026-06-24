# zio-bdd-tooling build targets
#
# Prerequisites: JDK 21, sbt
# Optional:      Node/npm (VSCode extension), Gradle (IntelliJ plugin, or use ./gradlew)
#                GraalVM with native-image (deferred — see issue #93 M3)

.PHONY: all lsp-jar intellij vscode test clean help

all: lsp-jar intellij

## Build the LSP fat jar (works with any JDK 17+)
lsp-jar:
	@echo "==> Building LSP fat jar..."
	sbt lsp/assembly

## Build and package the IntelliJ plugin .zip (requires lsp-jar first)
intellij: lsp-jar
	@echo "==> Building IntelliJ plugin..."
	cd extensions/intellij && ./gradlew buildPlugin
	@echo "==> Plugin zip: extensions/intellij/build/distributions/zio-bdd-intellij-*.zip"

## Run the IntelliJ plugin in a sandboxed IDE for manual testing
intellij-run: lsp-jar
	cd extensions/intellij && ./gradlew runIde

## Build and package the VSCode extension .vsix (requires node/npm)
vscode:
	@which npm > /dev/null 2>&1 || (echo "ERROR: npm not found" && exit 1)
	@echo "==> Building VSCode extension..."
	cd extensions/vscode && npm install && npm run compile && npx vsce package --allow-missing-repository
	@echo "==> Extension: extensions/vscode/zio-bdd-*.vsix"

## Run LSP server unit tests
test:
	sbt lsp/test

## Remove all build artifacts
clean:
	sbt clean
	cd extensions/intellij && ./gradlew clean
	rm -f extensions/intellij/src/main/resources/bin/zio-bdd-lsp.jar
	rm -rf extensions/vscode/out extensions/vscode/node_modules

help:
	@grep -E '^##' Makefile | sed 's/## //'
