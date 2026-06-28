package zio.bdd.lsp

import zio.*

/**
 * LSP server entry point.
 *
 * IMPORTANT: stdout is reserved for JSON-RPC traffic — any logging written to
 * stdout will corrupt the protocol. We replace ZIO's default ConsoleLogger
 * (which writes to stdout) with [[StderrLogger]].
 */
object Main extends ZIOAppDefault:

  override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] = StderrLogger.layer

  def run =
    ZIOBddLanguageServer
      .start()
      .tapErrorCause(c => ZIO.logErrorCause("LSP server crashed", c))
