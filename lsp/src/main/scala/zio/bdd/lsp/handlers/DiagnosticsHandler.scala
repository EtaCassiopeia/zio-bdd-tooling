package zio.bdd.lsp.handlers

import org.eclipse.lsp4j.*
import zio.*
import zio.bdd.gherkin.StepType
import zio.bdd.lsp.{GherkinBridge, StepMatcher, WorkspaceIndex}

/**
 * Compute LSP warnings for missing step definitions in a .feature file.
 *
 * Note: Gherkin parser line numbers are 1-based; LSP positions are 0-based. We
 * convert at the boundary.
 */
object DiagnosticsHandler:

  private val MaxLevenshteinForHint = 8

  def computeDiagnostics(
    featureUri: String,
    content: String,
    index: WorkspaceIndex
  ): UIO[List[Diagnostic]] =
    val sourceLines = content.linesIterator.toIndexedSeq
    index.allSteps.map { allDefs =>
      GherkinBridge.parseFeature(content, featureUri) match
        case Left(_) => Nil
        case Right(feature) =>
          for
            scenario <- feature.scenarios
            if !scenario.isIgnored // skip @ignore scenarios — unmatched steps there are intentional
            step <- scenario.steps
            diag <- diagnosticFor(step, allDefs, sourceLines)
          yield diag
    }

  private def diagnosticFor(
    step: zio.bdd.gherkin.Step,
    allDefs: List[zio.bdd.lsp.StepDefinition],
    sourceLines: IndexedSeq[String]
  ): Option[Diagnostic] =
    val keyword = stepTypeToKeyword(step.stepType)
    StepMatcher.find(keyword, step.pattern, allDefs) match
      case StepMatcher.MatchResult.Matched(_)    => None
      case StepMatcher.MatchResult.NoMatch(hint) =>
        // Convert 1-based parser line → 0-based LSP line.
        val line     = step.line.map(_ - 1).getOrElse(0).max(0)
        val lineText = sourceLines.lift(line).getOrElse("")
        // Highlight the actual step text (skip leading whitespace).
        val startCol = lineText.indexWhere(!_.isWhitespace).max(0)
        val endCol   = lineText.length
        val range    = new Range(new Position(line, startCol), new Position(line, endCol))
        // Not "run `sbt zioBddSnippets`" — that sbt task is defined in zio-bdd's own build
        // (project/*.scala auto-plugins) and isn't available to a project that just depends on
        // the published zio-bdd library (see EtaCassiopeia/zio-bdd#104). Point at this LSP's own
        // completion instead: opening the step-definition .scala file and typing inside a
        // Given/When/Then(...) call surfaces this exact unmatched step text as a completion item
        // (CompletionHandler.unimplementedStepCompletion) — a skeleton with no extra command.
        val msg = hint match
          case Some((closest, dist)) if dist <= MaxLevenshteinForHint =>
            val fileName = closest.file.split("[/\\\\]").last
            s"""No step definition found.
               |Closest match: "${closest.displayText}"
               |  ($fileName:${closest.line + 1})
               |Open a step-definition file and start typing inside Given/When/Then(...) for a completion skeleton.""".stripMargin
          case _ =>
            s"""No step definition found for: "${step.pattern}"
               |Open a step-definition file and start typing inside Given/When/Then(...) for a completion skeleton.""".stripMargin
        Some(new Diagnostic(range, msg, DiagnosticSeverity.Warning, "zio-bdd"))

  private def stepTypeToKeyword(st: StepType): String = st match
    case StepType.GivenStep => "Given"
    case StepType.WhenStep  => "When"
    case StepType.ThenStep  => "Then"
    case StepType.AndStep   => "And"
    case StepType.ButStep   => "But"
