# zio-bdd-tooling build targets
#
# Prerequisites: JDK 21, sbt
# Optional:      Node/npm (VSCode extension), Gradle (IntelliJ plugin, or use ./gradlew)
#                GraalVM with native-image (deferred — see issue #93 M3)

.PHONY: all lsp-jar cli-jar intellij vscode test native clean help

all: lsp-jar cli-jar intellij

## Build the LSP fat jar (works with any JDK 17+)
lsp-jar:
	@echo "==> Building LSP fat jar..."
	sbt lsp/assembly

## Build the CLI fat jar
cli-jar:
	@echo "==> Building CLI fat jar..."
	sbt cli/assembly

## Build and package the IntelliJ plugin .zip (requires lsp-jar first)
intellij: lsp-jar
	@echo "==> Building IntelliJ plugin..."
	cd extensions/intellij && ./gradlew buildPlugin
	@echo "==> Plugin zip: extensions/intellij/build/distributions/zio-bdd-intellij-*.zip"

## Run the IntelliJ plugin in a sandboxed IDE for manual testing
intellij-run: lsp-jar
	cd extensions/intellij && ./gradlew runIde

## Build and package the VSCode extension .vsix (requires node/npm)
vscode: lsp-jar
	@which npm > /dev/null 2>&1 || (echo "ERROR: npm not found" && exit 1)
	@echo "==> Copying LSP jar into VSCode extension bin/..."
	@mkdir -p extensions/vscode/bin
	@cp extensions/intellij/src/main/resources/bin/zio-bdd-lsp.jar extensions/vscode/bin/
	@echo "==> Building VSCode extension..."
	cd extensions/vscode && npm install && npm run compile && npx vsce package
	@echo "==> Extension: extensions/vscode/zio-bdd-*.vsix"

## Build GraalVM native binaries (requires GraalVM JDK 21 on PATH)
## Also uncomment NativeImagePlugin lines in build.sbt and project/plugins.sbt first.
native:
	@which native-image > /dev/null 2>&1 || (echo "ERROR: native-image not found. Install GraalVM JDK 21 and run: gu install native-image" && exit 1)
	@echo "==> Building native binaries..."
	sbt lsp/nativeImage cli/nativeImage
	@echo "==> Binaries: bin/zio-bdd-lsp  bin/zio-bdd"

## Run all tests
test:
	sbt lsp/test cli/test

## Publish zio-bdd locally and pin .sbtopts to the new SNAPSHOT version.
## Run this whenever zio-bdd changes: make sync-zio-bdd ZIO_BDD_DIR=../zio-bdd
sync-zio-bdd:
	@ZIO_BDD_DIR=$${ZIO_BDD_DIR:-../zio-bdd}; \
	echo "==> Publishing zio-bdd from $$ZIO_BDD_DIR ..."; \
	cd "$$ZIO_BDD_DIR" && sbt publishLocal 2>&1 | tee /tmp/zio-bdd-publish.log; \
	VERSION=$$(ls ~/.ivy2/local/io.github.etacassiopeia/zio-bdd_3/ | grep SNAPSHOT | sort | tail -1); \
	echo "==> Pinning .sbtopts to $$VERSION"; \
	echo "-DzioBdd.version=$$VERSION" > "$(CURDIR)/.sbtopts"

## Remove all build artifacts
clean:
	sbt clean
	cd extensions/intellij && ./gradlew clean
	rm -f extensions/intellij/src/main/resources/bin/zio-bdd-lsp.jar
	rm -f extensions/vscode/bin/zio-bdd-lsp.jar
	rm -rf extensions/vscode/out extensions/vscode/node_modules
	rm -f bin/zio-bdd-lsp bin/zio-bdd

help:
	@grep -E '^##' Makefile | sed 's/## //'
