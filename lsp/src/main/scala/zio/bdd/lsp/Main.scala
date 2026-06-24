package zio.bdd.lsp

import zio.*

import java.io.PrintStream
import java.lang.System as JSystem
import java.time.format.DateTimeFormatter

/**
 * LSP server entry point.
 *
 * IMPORTANT: stdout is reserved for JSON-RPC traffic — any logging written to
 * stdout will corrupt the protocol. We replace ZIO's default ConsoleLogger
 * (which writes to stdout) with a stderr-based logger.
 */
object Main extends ZIOAppDefault:

  override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] =
    Runtime.removeDefaultLoggers >>> Runtime.addLogger(StderrLogger)

  def run =
    ZIOBddLanguageServer
      .start()
      .tapErrorCause(c => ZIO.logErrorCause("LSP server crashed", c))

/** Minimal stderr logger; format: `[zio-bdd] LEVEL message`. */
private object StderrLogger extends ZLogger[String, Unit]:
  private val out: PrintStream = JSystem.err
  private val fmt              = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

  def apply(
    trace: Trace,
    fiberId: FiberId,
    logLevel: LogLevel,
    message: () => String,
    cause: Cause[Any],
    context: FiberRefs,
    spans: List[LogSpan],
    annotations: Map[String, String]
  ): Unit =
    val ts = java.time.LocalTime.now().format(fmt)
    out.println(s"[zio-bdd][$ts][${logLevel.label}] ${message()}")
    if !cause.isEmpty then out.println(cause.prettyPrint)
