package zio.bdd.lsp.handlers

import org.eclipse.lsp4j.*
import zio.*
import zio.bdd.lsp.{GherkinLine, StepDefinition, StepMatcher, WorkspaceIndex}

/** textDocument/hover — show parameter types for the matched step. */
object HoverHandler:

  def hover(
    position: Position,
    content: String,
    index: WorkspaceIndex
  ): UIO[Option[Hover]] =
    val line = content.linesIterator.toList.lift(position.getLine).getOrElse("")
    GherkinLine.extractStep(line) match
      case None => ZIO.none
      case Some((keyword, text)) =>
        index.allSteps.map { defs =>
          StepMatcher.find(keyword, text, defs) match
            case StepMatcher.MatchResult.NoMatch(_) => None
            case StepMatcher.MatchResult.Matched(defn) =>
              val md       = renderMarkdown(defn)
              val contents = new MarkupContent(MarkupKind.MARKDOWN, md)
              Some(new Hover(contents))
        }

  private def renderMarkdown(defn: StepDefinition): String =
    val sb = new StringBuilder
    sb.append(s"**${defn.keyword}** ${defn.displayText}\n\n")

    if defn.isStateInjecting then
      sb.append(s"_State-injecting (`${defn.keyword}S`) — scenario state is the first argument._\n\n")

    if defn.extractors.nonEmpty then
      sb.append("| Parameter | Type | Description |\n")
      sb.append("|-----------|------|-------------|\n")
      defn.extractors.foreach { e =>
        sb.append(s"| `{${e.name}}` | `${e.scalaType}` | ${e.description} |\n")
      }
      sb.append("\n")

    val fileName = defn.file.split("[/\\\\]").lastOption.getOrElse(defn.file)
    sb.append(s"_Defined in_ `$fileName:${defn.line + 1}`")
    sb.toString
