package zio.bdd.lsp.handlers

import org.eclipse.lsp4j.*
import zio.*
import zio.bdd.gherkin.{Feature, Scenario}
import zio.bdd.lsp.{GherkinBridge, StepExtractor, WorkspaceIndex}

import scala.jdk.CollectionConverters.*

/**
 * textDocument/codeLens for .feature and .scala files.
 *
 * .feature files: "▶ Run feature" / "▶ Run scenario" above Feature/Scenario
 * lines. Each lens runs `zio-bdd.runCommand` via the VS Code client.
 *
 * .scala files: "N usages" above every Given/When/Then step-definition call.
 * Clicking a usage lens invokes `zio-bdd.findStepUsages`, which calls the LSP
 * references provider and shows results in the VS Code References panel.
 *
 * Note: Gherkin parser line numbers are 1-based; LSP positions are 0-based.
 * StepExtractor line numbers are 0-based (count newlines from start).
 */
object CodeLensHandler:

  def codeLenses(uri: String, content: String, index: WorkspaceIndex): UIO[List[CodeLens]] =
    if uri.endsWith(".scala") then scalaLenses(uri, content, index)
    else
      ZIO.succeed {
        GherkinBridge.parseFeature(content, uri) match
          case Left(_) => Nil
          case Right(feature) =>
            val path = uri.stripPrefix("file://")
            featureLens(feature, path) :: feature.scenarios.map(scenarioLens(_, path))
      }

  // Emit an "N usages" lens above each step definition in a .scala file.
  private def scalaLenses(uri: String, content: String, index: WorkspaceIndex): UIO[List[CodeLens]] =
    val path = uri.stripPrefix("file://")
    val defs = StepExtractor.extractFromSource(content, path)
    ZIO
      .foreachPar(defs) { defn =>
        ReferencesHandler.usageCount(defn, index).map { count =>
          val label   = if count == 1 then "1 usage" else s"$count usages"
          val command = new Command(label, "zio-bdd.findStepUsages")
          // Arguments forwarded to the extension command: file URI, line, character.
          // The extension resolves them into a position and calls vscode.executeReferenceProvider.
          command.setArguments(
            List[Object](uri, java.lang.Integer.valueOf(defn.line), java.lang.Integer.valueOf(0)).asJava
          )
          new CodeLens(lineRange(defn.line), command, null)
        }
      }
      .map(_.toList)

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

  private def shellQuote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"

  private def runCommand(flags: String): String =
    s"""sbt "testOnly * -- $flags""""

  private def lineRange(line: Int): Range = new Range(new Position(line, 0), new Position(line, 0))

  private def toLsp(line: Option[Int]): Int = line.map(n => (n - 1).max(0)).getOrElse(0)
