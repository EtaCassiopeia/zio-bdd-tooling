package zio.bdd.lsp

import java.util.regex.Pattern as JPattern

/**
 * Matches a parsed Gherkin step text against registered step definitions.
 *
 * Implements cross-keyword fallback (mirrors zio-bdd's runtime
 * `StepRegistry.candidatesWithFallback` semantics): And/But steps try all five
 * keyword types; any keyword can fall back to And definitions.
 */
object StepMatcher:

  enum MatchResult:
    case Matched(definition: StepDefinition)
    case NoMatch(closest: Option[(StepDefinition, Int)])

  private val allKeywords = List("Given", "When", "Then", "And", "But")

  /**
   * Find the best matching step definition for the given step text and keyword.
   * Returns Matched if an exact regex match is found, NoMatch with the closest
   * (by Levenshtein on literal parts) otherwise.
   */
  def find(keyword: String, text: String, defs: List[StepDefinition]): MatchResult =
    val candidates = candidatesFor(keyword, defs)
    candidates.find(d => matches(text, d)) match
      case Some(d) => MatchResult.Matched(d)
      case None    => MatchResult.NoMatch(closest(text, defs))

  /** True if the step text matches the definition's compiled pattern. */
  def matches(text: String, defn: StepDefinition): Boolean =
    try JPattern.compile(defn.pattern).matcher(text).matches()
    catch case _: Exception => false

  /**
   * Find all candidates for a given keyword, including cross-keyword fallback.
   * And/But try all keywords; primary keywords also check And/But definitions.
   */
  def candidatesFor(keyword: String, defs: List[StepDefinition]): List[StepDefinition] =
    keyword match
      case "And" | "But" => defs // try everything
      case kw =>
        val primary = defs.filter(d => d.keyword == kw)
        val andBut  = defs.filter(d => d.keyword == "And" || d.keyword == "But")
        (primary ++ andBut).distinctBy(_.pattern)

  /**
   * Find the closest matching definition using Levenshtein distance on the
   * display text. Returns the definition and its edit distance. Only returns a
   * hint when distance ≤ 15 (avoids noise for completely different steps).
   */
  def closest(text: String, defs: List[StepDefinition]): Option[(StepDefinition, Int)] =
    if defs.isEmpty then None
    else
      val ranked = defs.map(d => d -> levenshtein(text.toLowerCase, d.displayText.toLowerCase))
      val best   = ranked.minBy(_._2)
      if best._2 <= 15 then Some(best) else None

  private def levenshtein(a: String, b: String): Int =
    val m = a.length
    val n = b.length
    val dp = Array.tabulate(m + 1, n + 1) { (i, j) =>
      if i == 0 then j
      else if j == 0 then i
      else 0
    }
    for i <- 1 to m; j <- 1 to n do
      dp(i)(j) =
        if a(i - 1) == b(j - 1) then dp(i - 1)(j - 1)
        else 1 + (dp(i - 1)(j) min dp(i)(j - 1) min dp(i - 1)(j - 1))
    dp(m)(n)
