package zio.bdd.cli

import com.monovore.decline.*
import cats.syntax.all.*
import java.nio.file.{Path, Paths}

object Main extends CommandApp(
  name    = "zio-bdd",
  header  = "Scan zio-bdd step definitions and feature files",
  version = "0.1.0",
  main = {
    val dirArg = Opts.argument[Path](metavar = "dir")
      .withDefault(Paths.get("."))

    val checkCmd = Opts.subcommand(
      name   = "check",
      help   = "Report unmatched Gherkin steps; exits with status 1 if any are found"
    )(dirArg.map(runCheck))

    val snippetCmd = Opts.subcommand(
      name   = "snippet",
      help   = "Print a step-definition skeleton for each unmatched step"
    )(dirArg.map(runSnippet))

    val listCmd = Opts.subcommand(
      name   = "list",
      help   = "List all discovered step definitions with their source location"
    )(dirArg.map(runList))

    checkCmd orElse snippetCmd orElse listCmd
  }
)

private def runCheck(dir: Path): Unit =
  val unmatched = Scanner.unmatchedSteps(dir)
  if unmatched.isEmpty then
    println("✓ All steps matched.")
  else
    println(s"✗ ${unmatched.size} unmatched step(s):")
    unmatched.foreach { u =>
      val rel = dir.relativize(u.featureFile)
      println(s"  ${u.keyword} \"${u.text}\"")
      println(s"    at $rel:${u.line}")
    }
    sys.exit(1)

private def runSnippet(dir: Path): Unit =
  val unmatched = Scanner.unmatchedSteps(dir)
  if unmatched.isEmpty then
    println("// All steps are already matched — no skeletons to generate.")
  else
    println("// ── Generated step skeletons ─────────────────────────────────────────")
    println("// Paste into your step trait and implement each body.")
    println()
    unmatched.foreach { u =>
      println(Scanner.toSkeleton(u))
      println()
    }

private def runList(dir: Path): Unit =
  val defs = Scanner.collectStepDefs(dir)
  if defs.isEmpty then
    println("No step definitions found.")
  else
    defs.foreach { d =>
      val rel = try dir.relativize(Path.of(d.file)).toString catch case _: Exception => d.file
      println(s"${d.keyword} ${d.displayText}")
      println(s"  $rel:${d.line + 1}")
    }
