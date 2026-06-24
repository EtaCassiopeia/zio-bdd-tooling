package zio.bdd.lsp

import zio.bdd.gherkin.{Feature, GherkinParser}
import zio.{Runtime, Unsafe}

/**
 * Synchronous bridge to zio-bdd-gherkin's ZIO-effect-based GherkinParser. Used
 * by the LSP server to parse .feature files from synchronous contexts.
 */
object GherkinBridge:
  private val runtime = Runtime.default

  def parseFeature(content: String, file: String): Either[Throwable, Feature] =
    Unsafe.unsafe { implicit u =>
      runtime.unsafe.run(GherkinParser.parseFeature(content, file)).toEither
    }
