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
      GherkinBridge.parseFeature(content, uri) match
        case Left(_) => ZIO.succeed(Nil)
        case Right(feature) =>
          val path = uri.stripPrefix("file://")
          index.ownerSuiteForFeature(path).zipWith(index.suiteFilesForFeature(path)) { (owner, suiteFiles) =>
            // Prefer the suite that declares this feature's directory (a single,
            // correct target); fall back to step-match file names, then "*" (#49).
            val selector = owner.map(o => s"*$o*").getOrElse(suiteSelector(suiteFiles))
            // A Scenario Outline is expanded by the parser into one Scenario per
            // Examples row, all sharing the outline's header line. Group by line so
            // each outline yields a run-outline lens (all rows) plus one lens per row.
            val scenarioLensList =
              feature.scenarios
                .groupBy(s => toLsp(s.line))
                .toList
                .sortBy(_._1)
                .flatMap((_, group) => scenarioLenses(group, path, selector))
            featureLens(feature, path, selector) :: scenarioLensList
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

  private def featureLens(feature: Feature, path: String, selector: String): CodeLens =
    val line    = toLsp(feature.line)
    val command = new Command("▶ Run feature", "zio-bdd.runCommand")
    command.setArguments(List[Object](buildRunCommand(selector, s"--feature-file ${shellQuote(path)}")).asJava)
    new CodeLens(lineRange(line), command, null)

  // `group` is the set of scenarios sharing one source line: a single Scenario,
  // or every expanded row of one Scenario Outline.
  //   - single scenario  -> one "Run scenario" lens with its exact name.
  //   - outline (N rows) -> a "Run outline (N examples)" lens that globs all rows
  //     (`<common-prefix>*`, matched case-insensitively), plus one exact-name lens
  //     per row so a single Example can be run on its own.
  private def scenarioLenses(group: List[Scenario], path: String, selector: String): List[CodeLens] =
    val line = toLsp(group.head.line)
    if group.sizeIs <= 1 then List(scenarioLens("▶ Run scenario", group.head.name, path, selector, line))
    else
      val outline = scenarioLens(
        s"▶ Run outline (${group.size} examples)",
        commonPrefix(group.map(_.name)) + "*",
        path,
        selector,
        line
      )
      outline :: group.map(s => scenarioLens(s"▶ Run ${exampleLabel(s.name)}", s.name, path, selector, line))

  private def scenarioLens(label: String, namePattern: String, path: String, selector: String, line: Int): CodeLens =
    val command = new Command(label, "zio-bdd.runCommand")
    command.setArguments(
      List[Object](
        buildRunCommand(
          selector,
          s"--feature-file ${shellQuote(path)} --scenario-name ${shellQuote(namePattern)} --focused"
        )
      ).asJava
    )
    new CodeLens(lineRange(line), command, null)

  // "Subtraction table - Example 2" -> "Example 2"; falls back to the full name.
  private def exampleLabel(name: String): String =
    val idx = name.lastIndexOf(" - Example ")
    if idx >= 0 then name.substring(idx + " - ".length) else name

  private def commonPrefix(names: List[String]): String =
    if names.isEmpty then ""
    else names.reduce((a, b) => a.zip(b).takeWhile((x, y) => x == y).map(_._1).mkString)

  // Derives an sbt test selector from matched Scala file paths.
  // e.g. List("/…/CalculatorSuite.scala") → "*CalculatorSuite*"
  // Falls back to "*" (all suites) when the index has no match yet.
  private[lsp] def suiteSelector(files: List[String]): String =
    if files.isEmpty then "*"
    else
      files
        .map(f => "*" + java.nio.file.Paths.get(f).getFileName.toString.stripSuffix(".scala") + "*")
        .mkString(" ")

  // Wraps value in \" ... \" so sbt's QuotedString parser strips the outer quotes and
  // preserves internal spaces. Single-quoted bash strings are NOT parsed by sbt — it
  // passes the quotes through as literal characters, causing paths like '/file' to fail.
  private[lsp] def shellQuote(value: String): String = "\\\"" + value + "\\\""

  // --scenario-name pattern for `name`: the exact name, or a "<name>*" glob when
  // `name` is a Scenario Outline the parser expanded into several
  // "<name> - Example N" rows. --scenario-name is matched as a case-insensitive
  // glob with a *full* match, so a bare outline name would match none of its rows.
  private[lsp] def scenarioRunPattern(scenarios: List[Scenario], name: String): String =
    val expandedRows = scenarios.count(s => s.name == name || s.name.startsWith(s"$name - "))
    if expandedRows > 1 then s"$name*" else name

  private[lsp] def buildRunCommand(selector: String, flags: String): String =
    s"""sbt "testOnly $selector -- $flags""""

  private def lineRange(line: Int): Range = new Range(new Position(line, 0), new Position(line, 0))

  private def toLsp(line: Option[Int]): Int = line.map(n => (n - 1).max(0)).getOrElse(0)
