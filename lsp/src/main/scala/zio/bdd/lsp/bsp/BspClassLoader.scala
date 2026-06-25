package zio.bdd.lsp.bsp

import zio.*
import zio.bdd.lsp.RuntimeStepSummary

import java.lang.{System => JSystem}
import java.nio.file.{Files, Paths}
import java.util.concurrent.TimeUnit

/**
 * Discovers runtime-accurate step definitions by launching a subprocess that
 * runs `zio.bdd.cli.StepLoader` on the user's JVM test classpath.
 *
 * The subprocess finds all concrete `ZIOSteps` subclasses on the classpath via
 * ClassGraph, calls `allDefinitions` on each (requires zio-bdd PR #107), and
 * writes a JSON array to stdout. The LSP parses the array and calls
 * `WorkspaceIndex.mergeRuntimeSteps` so accurate extractor regexes replace the
 * static-scan approximations.
 *
 * The loader jar is resolved from the code-source of this class — that is, the
 * fat jar that the LSP server is running from. This works when launched via
 * `java -jar zio-bdd-lsp.jar` and also when the fat jar is on the classpath. It
 * does NOT work when running as a native-image binary (code source returns a
 * directory); in that case pass the loader jar path explicitly via the
 * `zio.bdd.loader.jar` system property.
 */
object BspClassLoader:

  /**
   * Run the StepLoader subprocess against the given classpath entries and parse
   * the resulting JSON array into `RuntimeStepSummary` values. Returns an empty
   * list if the loader jar cannot be found or the subprocess fails.
   */
  def loadSteps(classpath: List[String]): UIO[List[RuntimeStepSummary]] =
    resolveLoaderJar match
      case None =>
        ZIO.logWarning("BspClassLoader: cannot locate the zio-bdd-cli jar; skipping runtime step loading") *>
          ZIO.succeed(Nil)
      case Some(loaderJar) =>
        ZIO
          .attemptBlocking(runSubprocess(classpath, loaderJar))
          .flatMap {
            case None    => ZIO.succeed(Nil)
            case Some(j) => ZIO.succeed(parseJson(j))
          }
          .catchAllCause { c =>
            ZIO.logWarningCause("BspClassLoader: subprocess failed; falling back to static scan", c) *>
              ZIO.succeed(Nil)
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

  // Minimal JSON array parser — expects the format StepLoader emits:
  //   [{"keyword":"...","pattern":"...","displayText":"..."},...]
  // Not a general JSON parser; relies on the known fixed field order.
  private def parseJson(json: String): List[RuntimeStepSummary] =
    if json.isEmpty || json == "[]" then return Nil
    // Extract individual object strings: {...}
    val objPattern = """\{[^}]+\}""".r
    val strPattern = """"([^"\\]*(\\.[^"\\]*)*)"""".r

    objPattern
      .findAllIn(json)
      .toList
      .flatMap { obj =>
        val fields = strPattern.findAllMatchIn(obj).map(_.group(1)).toList
        // Expected order: keyword, pattern, displayText (6 tokens — key then value ×3)
        if fields.length >= 6 then
          val keyword     = unescape(fields(1))
          val pattern     = unescape(fields(3))
          val displayText = unescape(fields(5))
          Some(RuntimeStepSummary(keyword, pattern, displayText))
        else None
      }

  private def unescape(s: String): String =
    s.replace("\\\"", "\"")
      .replace("\\n", "\n")
      .replace("\\r", "\r")
      .replace("\\t", "\t")
      .replace("\\\\", "\\")
