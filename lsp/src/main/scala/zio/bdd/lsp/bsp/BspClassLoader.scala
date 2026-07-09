package zio.bdd.lsp.bsp

import zio.*
import zio.bdd.lsp.{RuntimeMockSummary, RuntimeStepSummary}

import java.lang.{System => JSystem}
import java.nio.file.{Files, Paths}
import java.util.concurrent.TimeUnit

/**
 * Discovers runtime-accurate step definitions by launching a subprocess that
 * runs `zio.bdd.cli.StepLoader` on the user's JVM test classpath.
 *
 * The subprocess finds all concrete `ZIOSteps` subclasses on the classpath via
 * ClassGraph, calls `allDefinitions` on each (requires zio-bdd PR #107) plus
 * `allMocks` on `MockSteps` suites (zio-bdd 1.3.0), and writes a JSON object
 * `{"steps":[…],"mocks":[…]}` to stdout. The LSP parses it and calls
 * `WorkspaceIndex.mergeRuntimeSteps` (accurate extractor regexes replace the
 * static-scan approximations) and `WorkspaceIndex.setMocks` (the `@mock(name)`
 * catalog for completion / diagnostics).
 *
 * The loader jar is resolved from the code-source of this class — that is, the
 * fat jar that the LSP server is running from. This works when launched via
 * `java -jar zio-bdd-lsp.jar` and also when the fat jar is on the classpath. It
 * does NOT work when running as a native-image binary (code source returns a
 * directory); in that case pass the loader jar path explicitly via the
 * `zio.bdd.loader.jar` system property.
 */
/** The runtime step definitions and @mock catalog from one StepLoader run. */
case class RuntimeLoadResult(steps: List[RuntimeStepSummary], mocks: List[RuntimeMockSummary]):
  def isEmpty: Boolean = steps.isEmpty && mocks.isEmpty

object RuntimeLoadResult:
  val empty: RuntimeLoadResult = RuntimeLoadResult(Nil, Nil)

object BspClassLoader:

  /**
   * Run the StepLoader subprocess against the given classpath entries and parse
   * the resulting `{"steps":[…],"mocks":[…]}` object into runtime step and mock
   * summaries. Returns an empty result if the loader jar cannot be found or the
   * subprocess fails.
   */
  def loadAll(classpath: List[String]): UIO[RuntimeLoadResult] =
    resolveLoaderJar match
      case None =>
        ZIO.logWarning("BspClassLoader: cannot locate the zio-bdd-cli jar; skipping runtime class loading") *>
          ZIO.succeed(RuntimeLoadResult.empty)
      case Some(loaderJar) =>
        ZIO
          .attemptBlocking(runSubprocess(classpath, loaderJar))
          .flatMap {
            case Left(reason) =>
              // A timeout / non-zero exit used to fall back to an empty result with
              // no log, so runtime steps + the @mock catalog vanished silently (#40).
              ZIO.logWarning(s"BspClassLoader: $reason; falling back to static scan") *>
                ZIO.succeed(RuntimeLoadResult.empty)
            case Right(j) =>
              val dropped = unrecognizedObjectCount(j)
              ZIO
                .logWarning(s"BspClassLoader: dropped $dropped unrecognized JSON object(s) from the StepLoader output")
                .when(dropped > 0) *>
                ZIO.succeed(RuntimeLoadResult(parseSteps(j), parseMocks(j)))
          }
          .catchAllCause { cause =>
            // Fall back only for genuine failures/defects; re-raise interruption
            // (e.g. LSP shutdown) rather than masking it as an empty success.
            if cause.isInterrupted then ZIO.interrupt
            else
              ZIO.logWarningCause("BspClassLoader: subprocess failed; falling back to static scan", cause) *>
                ZIO.succeed(RuntimeLoadResult.empty)
          }

  // ── internals ─────────────────────────────────────────────────────────────

  private def resolveLoaderJar: Option[String] =
    // System property override (required for native-image deployments)
    Option(JSystem.getProperty("zio.bdd.loader.jar"))
      .filter(p => Files.exists(Paths.get(p)))
      .orElse {
        // Derive from the fat jar this class was loaded from
        Option(getClass.getProtectionDomain)
          .flatMap(pd => Option(pd.getCodeSource))
          .map(_.getLocation.toURI)
          .map(Paths.get(_).toAbsolutePath.toString)
          .filter(_.endsWith(".jar"))
      }

  private def runSubprocess(classpath: List[String], loaderJar: String): Either[String, String] =
    val cp = (classpath :+ loaderJar).mkString(java.io.File.pathSeparator)
    val pb = new ProcessBuilder(
      "java",
      "-cp",
      cp,
      "zio.bdd.lsp.bsp.StepLoader"
    )
    pb.redirectErrorStream(false)
    captureStdout(pb.start(), 30)

  /**
   * Drain both stdout and stderr on their own daemon threads, then wait with a
   * deadline. StepLoader logs a `System.err` line per unloadable suite; reading
   * only stdout while stderr fills the OS pipe buffer (~64KB) deadlocks both
   * sides (#40). Draining stdout on a thread too means `waitFor` (not a
   * blocking stream read) bounds the call, so a child that stalls *either*
   * stream still hits the timeout instead of hanging forever. Returns
   * `Right(json)` on a clean exit, or `Left(reason)` on timeout / non-zero exit
   * so the caller can log why rather than silently emptying.
   */
  private[bsp] def captureStdout(proc: Process, timeoutSeconds: Int): Either[String, String] =
    val out       = new StringBuilder
    val errTail   = new StringBuilder
    val outThread = drainer(proc.getInputStream, out, bounded = false)
    val errThread = drainer(proc.getErrorStream, errTail, bounded = true)
    outThread.start()
    errThread.start()
    val ok = proc.waitFor(timeoutSeconds, TimeUnit.SECONDS)
    // On timeout, kill the child first so both streams reach EOF and the drain
    // threads terminate — otherwise join() below would race their buffers.
    if !ok then proc.destroyForcibly()
    outThread.join(2000)
    errThread.join(1000)
    if !ok then Left(s"StepLoader subprocess timed out after ${timeoutSeconds}s${tailSuffix(errTail)}")
    else if proc.exitValue() != 0 then Left(s"StepLoader subprocess exited ${proc.exitValue()}${tailSuffix(errTail)}")
    else Right(out.toString.trim)

  // Fully consume a stream (so the child never blocks writing to it) on a daemon
  // thread. `bounded` caps retained text for the diagnostic stderr tail; stdout
  // is read whole. A read failure is recorded in the buffer, not swallowed, so
  // it surfaces in the eventual warning rather than vanishing.
  private def drainer(in: java.io.InputStream, buf: StringBuilder, bounded: Boolean): Thread =
    val t = new Thread(() =>
      try
        val reader = new java.io.BufferedReader(new java.io.InputStreamReader(in, "UTF-8"))
        var line   = reader.readLine()
        while line != null do
          if !bounded || buf.length < 8192 then buf.append(line).append('\n')
          line = reader.readLine()
      catch case e: java.io.IOException => buf.append(s"[stream drain aborted: ${e.getMessage}]")
      ()
    )
    t.setDaemon(true)
    t

  private def tailSuffix(errTail: StringBuilder): String =
    if errTail.isEmpty then "" else s"; stderr: ${errTail.toString.trim}"

  // Minimal JSON object scanner — StepLoader emits `{"steps":[…],"mocks":[…]}`
  // where each inner object is brace-free. `\{[^{}]+\}` matches only the
  // innermost objects, so the envelope's outer brace is skipped; we then
  // dispatch by first key ("keyword" → step, "name" → mock), so step and mock
  // objects never cross-contaminate. Not a general JSON parser; relies on the
  // known fixed field order StepLoader emits (runtime displayText/pattern carry
  // no `{}`).
  private val objPattern = """\{[^{}]+\}""".r
  private val strPattern = """"([^"\\]*(\\.[^"\\]*)*)"""".r

  private def objectFields(json: String): List[List[String]] =
    objPattern.findAllIn(json).toList.map(obj => strPattern.findAllMatchIn(obj).map(_.group(1)).toList)

  private[bsp] def parseSteps(json: String): List[RuntimeStepSummary] =
    if json.isEmpty then Nil
    else
      objectFields(json).flatMap { fields =>
        // Step object: {"keyword":…,"pattern":…,"displayText":…} — 6 tokens.
        if fields.length >= 6 && fields.head == "keyword" then
          Some(RuntimeStepSummary(unescape(fields(1)), unescape(fields(3)), unescape(fields(5))))
        else None
      }

  private[bsp] def parseMocks(json: String): List[RuntimeMockSummary] =
    if json.isEmpty then Nil
    else
      objectFields(json).flatMap { fields =>
        // Mock object: {"name":…,"sourceKind":…} — 4 tokens.
        if fields.length >= 4 && fields.head == "name" then
          Some(RuntimeMockSummary(unescape(fields(1)), unescape(fields(3))))
        else None
      }

  /**
   * How many scanned JSON objects were recognized as neither a step nor a mock
   * — i.e. silently dropped by both parsers (e.g. a truncated object). The
   * caller logs when this is non-zero so a silent drop is diagnosable rather
   * than invisible (#47).
   */
  private[bsp] def unrecognizedObjectCount(json: String): Int =
    if json.isEmpty then 0
    else
      // Exclude envelope-marker objects: with both arrays empty the envelope has no
      // inner braces and is itself matched as an object, which is not a dropped entry.
      val objects = objectFields(json).filterNot(f => f.headOption.exists(h => h == "steps" || h == "mocks"))
      math.max(0, objects.length - parseSteps(json).length - parseMocks(json).length)

  // Single-pass unescaper. A chain of String.replace calls cannot decode this
  // correctly: a real backslash is escaped to `\\` while a brace is escaped to a
  // single-backslash `\u007b` (#40), so `\\u007b` (an escaped backslash then the
  // text u007b) is indistinguishable from the brace token under substring
  // replacement. Scanning once, consuming each escape whole, removes the
  // ambiguity and handles any `\uXXXX` uniformly.
  private def unescape(s: String): String =
    val sb = new StringBuilder(s.length)
    var i  = 0
    while i < s.length do
      val c = s.charAt(i)
      if c == '\\' && i + 1 < s.length then
        s.charAt(i + 1) match
          case '"'  => sb.append('"'); i += 2
          case 'n'  => sb.append('\n'); i += 2
          case 'r'  => sb.append('\r'); i += 2
          case 't'  => sb.append('\t'); i += 2
          case '\\' => sb.append('\\'); i += 2
          case 'u' if i + 6 <= s.length =>
            val code =
              try Some(Integer.parseInt(s.substring(i + 2, i + 6), 16))
              catch case _: NumberFormatException => None
            code match
              case Some(cp) => sb.append(cp.toChar); i += 6
              case None     => sb.append(c); i += 1
          case _ => sb.append(c); i += 1
      else
        sb.append(c); i += 1
    sb.toString
