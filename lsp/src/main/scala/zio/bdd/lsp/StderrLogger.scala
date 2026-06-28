package zio.bdd.lsp

import zio.*

import java.io.PrintStream
import java.lang.System as JSystem
import java.time.format.DateTimeFormatter

/**
 * Minimal stderr logger; format: `[zio-bdd][HH:mm:ss.SSS][LEVEL] message`.
 *
 * stdout is reserved for JSON-RPC traffic — any logging written to stdout
 * corrupts the protocol. Every runtime in the server (the main app runtime and
 * the synchronous [[GherkinBridge]] runtime) must install this in place of
 * ZIO's default ConsoleLogger, which writes to stdout.
 */
private[lsp] object StderrLogger extends ZLogger[String, Unit]:
  private val out: PrintStream = JSystem.err
  private val fmt              = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

  // Removes the stdout ConsoleLogger and installs this stderr logger.
  val layer: ZLayer[Any, Nothing, Unit] =
    Runtime.removeDefaultLoggers >>> Runtime.addLogger(this)

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
