package zio.bdd.lsp.handlers

import org.eclipse.lsp4j.*
import zio.*
import zio.bdd.lsp.{GherkinLine, StepMatcher, WorkspaceIndex}

/**
 * textDocument/definition — jump from a feature step to its Scala source.
 *
 * Returns LocationLink (not bare Location) so the IDE knows:
 *   - originSelectionRange: which span in the .feature file is "the link" (i.e.
 *     what to underline)
 *   - targetRange / targetSelectionRange: where in the .scala file to land the
 *     cursor
 *
 * Without an originSelectionRange, IntelliJ has no PSI hint for what's
 * clickable in a Gherkin file and falls back to highlighting the whole
 * document.
 */
object DefinitionHandler:

  def definition(
    uri: String,
    position: Position,
    content: String,
    index: WorkspaceIndex
  ): UIO[List[LocationLink]] =
    val lines = content.linesIterator.toList
    val line  = lines.lift(position.getLine).getOrElse("")

    GherkinLine.extractStep(line) match
      case None =>
        ZIO.logDebug(s"definition: no step keyword on line ${position.getLine} of $uri").as(Nil)

      case Some((keyword, text)) =>
        for
          defs <- index.allSteps
          _    <- ZIO.logDebug(s"definition: $keyword '$text' against ${defs.size} step defs")
          out <- StepMatcher.find(keyword, text, defs) match
                   case StepMatcher.MatchResult.Matched(defn) =>
                     val targetUri = if defn.file.startsWith("file://") then defn.file else s"file://${defn.file}"
                     val link      = buildLink(position.getLine, line, keyword, targetUri, defn.line)
                     ZIO.logInfo(s"definition: matched -> $targetUri:${defn.line + 1}").as(List(link))

                   case StepMatcher.MatchResult.NoMatch(closest) =>
                     val hint = closest.map(_._1.displayText).getOrElse("(no near match)")
                     ZIO.logDebug(s"definition: no match for '$text', closest=$hint").as(Nil)
        yield out

  /**
   * Construct a tight LocationLink:
   *   - origin: from the keyword to end-of-line in the .feature file
   *   - target: a zero-width point at start of step definition line, which
   *     IntelliJ treats as "place the cursor here, don't select anything"
   */
  private def buildLink(
    sourceLine: Int,
    lineText: String,
    keyword: String,
    targetUri: String,
    targetLine: Int
  ): LocationLink =
    val keywordCol = lineText.indexOf(keyword).max(0)
    val origin = new Range(
      new Position(sourceLine, keywordCol),
      new Position(sourceLine, lineText.length)
    )
    val target = new Range(new Position(targetLine, 0), new Position(targetLine, 0))
    new LocationLink(targetUri, target, target, origin)
