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
            case None    => ZIO.succeed(RuntimeLoadResult.empty)
            case Some(j) => ZIO.succeed(RuntimeLoadResult(parseSteps(j), parseMocks(j)))
          }
          .catchAllCause { c =>
            ZIO.logWarningCause("BspClassLoader: subprocess failed; falling back to static scan", c) *>
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

  private def runSubprocess(classpath: List[String], loaderJar: String): Option[String] =
    val cp = (classpath :+ loaderJar).mkString(java.io.File.pathSeparator)
    val pb = new ProcessBuilder(
      "java",
      "-cp",
      cp,
      "zio.bdd.lsp.bsp.StepLoader"
    )
    pb.redirectErrorStream(false)
    val proc = pb.start()
    val out  = scala.io.Source.fromInputStream(proc.getInputStream, "UTF-8").mkString
    val ok   = proc.waitFor(30, TimeUnit.SECONDS)
    if !ok then
      proc.destroyForcibly()
      None
    else if proc.exitValue() != 0 then None
    else Some(out.trim)

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

  private def unescape(s: String): String =
    s.replace("\\\"", "\"")
      .replace("\\n", "\n")
      .replace("\\r", "\r")
      .replace("\\t", "\t")
      .replace("\\\\", "\\")
