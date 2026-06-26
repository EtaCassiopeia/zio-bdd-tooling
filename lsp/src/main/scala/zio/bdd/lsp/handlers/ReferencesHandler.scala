package zio.bdd.lsp.handlers

import org.eclipse.lsp4j.*
import zio.*
import zio.bdd.lsp.{StepDefinition, StepExtractor, WorkspaceIndex}

import java.util.regex.Pattern as JPattern
import scala.util.Try

/**
 * textDocument/references from a .scala step-definition file.
 *
 * Given the cursor position on a Given/When/Then call, returns the location of
 * every step in every indexed .feature file whose text matches that
 * definition's pattern. This is the inverse of textDocument/definition (feature
 * → Scala).
 */
object ReferencesHandler:

  def references(
    uri: String,
    position: Position,
    content: String,
    index: WorkspaceIndex
  ): UIO[List[Location]] =
    if !uri.endsWith(".scala") then ZIO.succeed(Nil)
    else
      val path    = uri.stripPrefix("file://")
      val defs    = StepExtractor.extractFromSource(content, path)
      val curLine = position.getLine // 0-based, same as StepDefinition.line
      defNear(defs, curLine) match
        case None => ZIO.succeed(Nil)
        case Some(defn) =>
          index.allFeatures.map { features =>
            for
              feature  <- features
              scenario <- feature.scenarios
              step     <- scenario.steps
              if matchesDefn(step.pattern, defn)
              file   <- feature.file.toList
              lspLine = step.line.map(_ - 1).getOrElse(0).max(0) // Gherkin 1-based → LSP 0-based
            yield new Location(
              s"file://$file",
              new Range(
                new Position(lspLine, 0),
                new Position(lspLine, step.pattern.length)
              )
            )
          }

  // Count usages without building full Location objects (used by code-lens).
  def usageCount(defn: StepDefinition, index: WorkspaceIndex): UIO[Int] =
    index.allFeatures.map { features =>
      features.iterator
        .flatMap(_.scenarios)
        .flatMap(_.steps)
        .count(s => matchesDefn(s.pattern, defn))
    }

  // The nearest step definition to `line` within ±3 lines (allows cursor to be
  // on the body or closing brace of the step, not just the keyword line).
  def defNear(defs: List[StepDefinition], line: Int): Option[StepDefinition] =
    defs
      .filter(d => math.abs(d.line - line) <= 3)
      .minByOption(d => math.abs(d.line - line))

  private def matchesDefn(stepText: String, defn: StepDefinition): Boolean =
    Try(JPattern.compile(defn.pattern).matcher(stepText).matches()).getOrElse(false)
