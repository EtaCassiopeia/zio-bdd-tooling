package zio.bdd.lsp

import zio.bdd.gherkin.{Feature, GherkinParser}
import zio.{Runtime, Unsafe}

/**
 * Synchronous bridge to zio-bdd-gherkin's ZIO-effect-based GherkinParser. Used
 * by the LSP server to parse .feature files from synchronous contexts.
 *
 * The parser emits `ZIO.logWarning` for malformed lines. This runtime installs
 * [[StderrLogger]] in place of ZIO's default ConsoleLogger so those warnings
 * never reach stdout — stdout is the JSON-RPC channel and any stray byte there
 * corrupts the LSP framing.
 */
object GherkinBridge:
  private val runtime: Runtime[Any] =
    Unsafe.unsafe { implicit u =>
      Runtime.unsafe.fromLayer(StderrLogger.layer)
    }

  def parseFeature(content: String, file: String): Either[Throwable, Feature] =
    Unsafe.unsafe { implicit u =>
      runtime.unsafe.run(GherkinParser.parseFeature(content, file)).toEither
    }
