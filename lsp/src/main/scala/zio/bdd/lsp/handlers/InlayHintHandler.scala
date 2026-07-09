package zio.bdd.lsp.handlers

import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either as JEither
import zio.*
import zio.bdd.gherkin.{Scenario, Step, StepType}
import zio.bdd.lsp.{DataFlow, DataRef, GherkinBridge, StepDefinition, StepMatcher, WorkspaceIndex}

/**
 * textDocument/inlayHint for .feature files: an end-of-line hint per step
 * showing the reads/sets data-flow of its matched step definition (#58/#59).
 * Each *read* ref is annotated `(← set by step N)`, pointing at the nearest
 * earlier step in the same scenario that produced it.
 *
 * Only the steps whose line falls inside the requested [range] emit a hint; a
 * step with no match or an empty data-flow emits nothing.
 */
object InlayHintHandler:

  def inlayHints(uri: String, range: Range, content: String, index: WorkspaceIndex): UIO[List[InlayHint]] =
    GherkinBridge.parseFeature(content, uri) match
      case Left(_) => ZIO.succeed(Nil)
      case Right(feature) =>
        index.allSteps.map { defs =>
          val lineLengths = content.linesIterator.map(_.length).toVector
          // A Scenario Outline is expanded by the parser into one Scenario per Examples row (all
          // sharing the outline's step lines) and Background steps are prepended to every scenario,
          // so distinct scenarios can revisit the same source line. Dedupe by position — one hint
          // per line — as CodeLensHandler does for the same reason.
          feature.scenarios
            .flatMap(sc => scenarioHints(sc, defs, range, lineLengths))
            .distinctBy(h => (h.getPosition.getLine, h.getPosition.getCharacter))
        }

  private def scenarioHints(
    sc: Scenario,
    defs: List[StepDefinition],
    range: Range,
    lineLengths: Vector[Int]
  ): List[InlayHint] =
    val labels = scenarioLabels(sc.steps.map(flowOf(_, defs)))
    sc.steps.zip(labels).flatMap { (step, labelOpt) =>
      for
        label   <- labelOpt
        lspLine <- step.line.map(_ - 1)
        if lspLine >= range.getStart.getLine && lspLine <= range.getEnd.getLine
      yield
        val col  = lineLengths.lift(lspLine).getOrElse(0)
        val hint = new InlayHint(new Position(lspLine, col), JEither.forLeft(label))
        hint.setKind(InlayHintKind.Type)
        hint.setPaddingLeft(java.lang.Boolean.TRUE)
        hint
    }

  private def flowOf(step: Step, defs: List[StepDefinition]): DataFlow =
    StepMatcher.find(keywordOf(step.stepType), step.pattern, defs) match
      case StepMatcher.MatchResult.Matched(d) => d.dataFlow
      case _                                  => DataFlow.empty

  private def keywordOf(st: StepType): String = st match
    case StepType.GivenStep => "Given"
    case StepType.WhenStep  => "When"
    case StepType.ThenStep  => "Then"
    case StepType.AndStep   => "And"
    case StepType.ButStep   => "But"

  /**
   * Per-step inlay labels for one scenario's step flows, in order. A read ref
   * is annotated with the 1-based number of the nearest earlier step that set
   * it. Producers are updated *after* each step so a read-modify-write of the
   * same ref points at the prior producer, not itself.
   */
  private[lsp] def scenarioLabels(flows: List[DataFlow]): List[Option[String]] =
    val producers = scala.collection.mutable.Map.empty[DataRef, Int]
    flows.zipWithIndex.map { (flow, i) =>
      val label = labelFor(flow, producers.toMap)
      flow.sets.foreach(ref => producers(ref) = i + 1)
      label
    }

  /**
   * The label for one step's flow, or None when empty. Sets are listed plainly;
   * each read is suffixed `(← set by step N)` when [producers] knows an earlier
   * producer for it.
   */
  private[lsp] def labelFor(flow: DataFlow, producers: Map[DataRef, Int]): Option[String] =
    if flow.isEmpty then None
    else
      val parts = List.newBuilder[String]
      if flow.sets.nonEmpty then
        parts += "sets " + flow.sets.toList.sortBy(_.render.toLowerCase).map(_.render).mkString(", ")
      if flow.reads.nonEmpty then
        val rs = flow.reads.toList.sortBy(_.render.toLowerCase).map { ref =>
          producers.get(ref).fold(ref.render)(n => s"${ref.render} (← set by step $n)")
        }
        parts += "reads " + rs.mkString(", ")
      Some(parts.result().mkString(" · "))
