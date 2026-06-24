package zio.bdd.lsp.handlers

import org.eclipse.lsp4j.*
import zio.*
import zio.bdd.gherkin.{Feature, Scenario}
import zio.bdd.lsp.GherkinBridge

import scala.jdk.CollectionConverters.*

/**
 * textDocument/codeLens — "▶ Run feature" / "▶ Run scenario" above the
 * `Feature:` / `Scenario:` lines of a .feature file.
 *
 * Each lens runs `zio-bdd.runCommand` (registered by the VSCode client in
 * extension.ts) with an sbt command using zio-bdd's real CLI flags
 * (`--feature-file`, `--scenario-name`; see docs/running.md) — not a synthetic
 * command the runtime wouldn't understand. `testOnly *` matches whichever test
 * classes are on the workspace's test classpath; precise enough since
 * `--feature-file` narrows to this exact file.
 *
 * Note: Gherkin parser line numbers are 1-based; LSP positions are 0-based.
 */
object CodeLensHandler:

  def codeLenses(uri: String, content: String): UIO[List[CodeLens]] =
    ZIO.succeed {
      GherkinBridge.parseFeature(content, uri) match
        case Left(_) => Nil
        case Right(feature) =>
          val path = uri.stripPrefix("file://")
          featureLens(feature, path) :: feature.scenarios.map(scenarioLens(_, path))
    }

  private def featureLens(feature: Feature, path: String): CodeLens =
    val line    = toLsp(feature.line)
    val command = new Command("▶ Run feature", "zio-bdd.runCommand")
    command.setArguments(List[Object](runCommand(s"--feature-file ${shellQuote(path)}")).asJava)
    new CodeLens(lineRange(line), command, null)

  private def scenarioLens(scenario: Scenario, path: String): CodeLens =
    val line    = toLsp(scenario.line)
    val command = new Command("▶ Run scenario", "zio-bdd.runCommand")
    command.setArguments(
      List[Object](
        runCommand(s"--feature-file ${shellQuote(path)} --scenario-name ${shellQuote(scenario.name)}")
      ).asJava
    )
    new CodeLens(lineRange(line), command, null)

  // The whole sbt argument is wrapped in double quotes (runCommand), so individual
  // flag values must use single quotes — nesting double quotes inside double quotes
  // breaks the shell's parsing of the outer argument.
  private def shellQuote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"

  private def runCommand(flags: String): String =
    s"""sbt "testOnly * -- $flags""""

  private def lineRange(line: Int): Range = new Range(new Position(line, 0), new Position(line, 0))

  private def toLsp(line: Option[Int]): Int = line.map(n => (n - 1).max(0)).getOrElse(0)
