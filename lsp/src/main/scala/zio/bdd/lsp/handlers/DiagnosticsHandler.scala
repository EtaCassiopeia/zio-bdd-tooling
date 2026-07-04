package zio.bdd.lsp.handlers

import org.eclipse.lsp4j.*
import zio.*
import zio.bdd.gherkin.StepType
import zio.bdd.lsp.{GherkinBridge, MockTag, RuntimeMockSummary, StepMatcher, WorkspaceIndex}

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
    // Independent of a successful parse: the gherkin parser silently treats a
    // mis-cased keyword as description text, so it never reaches scenario.steps.
    val keywordDiags = miscasedKeywordDiagnostics(sourceLines)
    for
      allDefs <- index.allSteps
      mocks   <- index.allMocks
    yield
      val stepDiags = GherkinBridge.parseFeature(content, featureUri) match
        case Left(_) => Nil
        case Right(feature) =>
          for
            scenario <- feature.scenarios
            if !scenario.isIgnored // skip @ignore scenarios — unmatched steps there are intentional
            step <- scenario.steps
            diag <- diagnosticFor(step, allDefs, sourceLines)
          yield diag
      keywordDiags ++ stepDiags ++ mockCatalogDiagnostics(sourceLines, mocks)

  private val stepKeywords    = List("Given", "When", "Then", "And", "But")
  private val validStepStarts = ("* " :: stepKeywords.map(_ + " "))
  private val structuralWords = List("Feature", "Background", "Scenario", "Scenarios", "Rule", "Examples", "Example")

  /**
   * Flag step lines whose keyword is mis-capitalised (e.g. `then` → `Then`).
   * Gherkin keywords are case-sensitive; the runner ignores a mis-cased line,
   * so the step silently never runs. We only flag exact case-insensitive
   * matches of a real keyword — never fuzzy near-misses, which would
   * false-positive on ordinary prose (e.g. a description starting with "The").
   */
  private def miscasedKeywordDiagnostics(sourceLines: IndexedSeq[String]): List[Diagnostic] =
    var inDocString = false
    var inFeature   = false
    val out         = List.newBuilder[Diagnostic]
    sourceLines.zipWithIndex.foreach { (raw, idx) =>
      val trimmed = raw.trim
      if trimmed.startsWith("\"\"\"") || trimmed.startsWith("```") then inDocString = !inDocString
      else if !inDocString then
        if isStructural(trimmed) then inFeature = true
        else if inFeature && isPotentialStepLine(trimmed) then
          val word = trimmed.takeWhile(_.isLetter)
          stepKeywords.find(kw => kw.equalsIgnoreCase(word) && kw != word).foreach { kw =>
            val startCol = raw.indexWhere(!_.isWhitespace).max(0)
            val range    = new Range(new Position(idx, startCol), new Position(idx, startCol + word.length))
            out += new Diagnostic(
              range,
              s"""Step keyword "$word" must be capitalised as "$kw". The runner ignores mis-cased keywords, so this step will not run.""",
              DiagnosticSeverity.Error,
              "zio-bdd"
            )
          }
    }
    out.result()

  private def isStructural(trimmed: String): Boolean =
    val word = trimmed.takeWhile(_.isLetter)
    structuralWords.exists(_.equalsIgnoreCase(word))

  private def isPotentialStepLine(trimmed: String): Boolean =
    trimmed.nonEmpty &&
      !trimmed.startsWith("#") &&
      !trimmed.startsWith("@") &&
      !trimmed.startsWith("|") &&
      !validStepStarts.exists(trimmed.startsWith) &&
      !stepKeywords.contains(trimmed)

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

  /**
   * Flag each `@mock(name)` whose name is not in the discovered catalog — an
   * unknown name is a scenario-setup failure at run time (`no catalog entry
   * named …`). Only runs when the catalog is non-empty: with no authoritative
   * catalog (discovery not yet run, or a non-mock project) we emit nothing —
   * flagging a valid name as unknown is worse than saying nothing.
   */
  private def mockCatalogDiagnostics(
    sourceLines: IndexedSeq[String],
    mocks: List[RuntimeMockSummary]
  ): List[Diagnostic] =
    if mocks.isEmpty then Nil
    else
      val known = mocks.map(_.name).toSet
      sourceLines.zipWithIndex.flatMap { (line, idx) =>
        MockTag.refs(line).collect {
          case ref if !known.contains(ref.name) =>
            val range = new Range(new Position(idx, ref.start), new Position(idx, ref.end))
            new Diagnostic(
              range,
              s"@mock: no catalog entry named '${ref.name}'. Add it to the suite's mockCatalog, or fix the name.",
              DiagnosticSeverity.Warning,
              "zio-bdd"
            )
        }
      }.toList

  private def stepTypeToKeyword(st: StepType): String = st match
    case StepType.GivenStep => "Given"
    case StepType.WhenStep  => "When"
    case StepType.ThenStep  => "Then"
    case StepType.AndStep   => "And"
    case StepType.ButStep   => "But"
