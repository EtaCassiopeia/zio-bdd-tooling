package zio.bdd.lsp.handlers

import org.eclipse.lsp4j.*
import zio.*
import zio.bdd.gherkin.{Feature, Scenario}
import zio.bdd.lsp.GherkinBridge

import scala.jdk.CollectionConverters.*

/**
 * textDocument/documentSymbol — outline view (Feature with Scenarios as
 * children).
 *
 * Note: Gherkin parser line numbers are 1-based; LSP positions are 0-based.
 */
object DocumentSymbolsHandler:

  def symbols(uri: String, content: String): UIO[List[DocumentSymbol]] =
    ZIO.succeed {
      GherkinBridge.parseFeature(content, uri) match
        case Left(_)        => Nil
        case Right(feature) => List(featureSymbol(feature))
    }

  private def featureSymbol(feature: Feature): DocumentSymbol =
    val featureLine = toLsp(feature.line)
    val lastLine = feature.scenarios.lastOption
      .flatMap(s => s.steps.lastOption.flatMap(_.line).orElse(s.line))
      .map(zeroBased)
      .getOrElse(featureLine)

    val sym = new DocumentSymbol(
      s"Feature: ${feature.name}",
      SymbolKind.Module,
      lineRange(featureLine, lastLine),
      lineRange(featureLine, featureLine)
    )
    sym.setChildren(feature.scenarios.map(scenarioSymbol).asJava)
    sym

  private def scenarioSymbol(scenario: Scenario): DocumentSymbol =
    val startLine = toLsp(scenario.line)
    val endLine   = scenario.steps.lastOption.flatMap(_.line).map(zeroBased).getOrElse(startLine)
    new DocumentSymbol(
      s"Scenario: ${scenario.name}",
      SymbolKind.Function,
      lineRange(startLine, endLine),
      lineRange(startLine, startLine)
    )

  private def lineRange(start: Int, end: Int): Range =
    new Range(new Position(start, 0), new Position(end, 200))

  /**
   * Convert an `Option[Int]` 1-based parser line to 0-based LSP line,
   * defaulting to 0.
   */
  private def toLsp(line: Option[Int]): Int = line.map(zeroBased).getOrElse(0)

  private def zeroBased(n: Int): Int = (n - 1).max(0)
