# zio-bdd-tooling build targets
#
# Prerequisites: JDK 17+, sbt
# Optional:      GraalVM with native-image (deferred — see issue #93 M3)

.PHONY: all lsp-jar test clean help

all: lsp-jar

## Build the fat jar (works with any JDK 17+)
lsp-jar:
	@echo "==> Building LSP fat jar..."
	sbt lsp/assembly

## Run LSP server unit tests
test:
	sbt lsp/test

## Remove all build artifacts
clean:
	sbt clean

help:
	@grep -E '^##' Makefile | sed 's/## //'
