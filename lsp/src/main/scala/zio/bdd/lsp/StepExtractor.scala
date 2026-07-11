package zio.bdd.lsp

import scala.util.matching.Regex

/**
 * Extracts StepDefinitions from Scala source text using regex pattern matching.
 *
 * Source-text scanning (not scalameta/SemanticDB) by design: it doesn't require
 * a compile step, so the LSP can give feedback within ~1s of saving a file.
 * Built-in extractor patterns (`int`, `string`, `double`, ...) are looked up
 * from `zio.bdd.core.step.DefaultTypedExtractor.byName` (see
 * `ExtractorInfo.builtins`) rather than hand-copied, so they can't silently
 * drift from what the runtime actually matches against.
 *
 * Coverage: all zio-bdd step registration forms: Given("literal" / ext /
 * "literal") { body } GivenS("...") { s => body } When/Then/And/But + S
 * variants
 *
 * The approach: find step keyword call sites, extract the first string
 * argument, then walk the / chain to pull out extractor names.
 */
object StepExtractor:

  // Matches: Given("...", When("...", GivenS("... at the start of a step registration.
  // Uses [ \t]* (not \s*) so the match never spans a blank line — \s includes \n which would
  // cause m.start to point at the preceding blank line, making line-number computation off by 1.
  private val stepCallRe: Regex =
    """(?m)^[ \t]*(Given|When|Then|And|But|GivenS|WhenS|ThenS|AndS|ButS)[ \t]*\(""".r

  def extractFromSource(content: String, filePath: String): List[StepDefinition] =
    // Resolve sibling helper `def`/`val` data-flow once so each step that calls a helper
    // (e.g. `def lastResponse = Stage.get[LastResponse]`) inherits its reads/sets (#57 partial).
    val helpers = StepDataFlow.resolveHelpers(content)
    stepCallRe
      .findAllMatchIn(content)
      .flatMap { m =>
        val kw                 = m.group(1)
        val startPos           = m.end
        val (exprText, endPos) = extractExpr(content, startPos)
        val lineNo             = content.take(m.start).count(_ == '\n')
        val flow =
          StepDataFlow.bodyAfter(content, endPos).map(StepDataFlow.analyze(_, helpers)).getOrElse(DataFlow.empty)
        parseExpr(kw, exprText, filePath, lineNo, flow)
      }
      .toList

  /**
   * Extract the step expression text (everything between the first `(` and the
   * matching `)` of the step keyword call's first argument). Stops at the
   * closing `)` that matches the opening `(` before the expression.
   */
  private def extractExpr(content: String, startPos: Int): (String, Int) =
    var depth = 1
    var i     = startPos
    val sb    = new StringBuilder
    while i < content.length && depth > 0 do
      content(i) match
        case '(' => depth += 1; sb.append('(')
        case ')' => depth -= 1; if depth > 0 then sb.append(')')
        case c   => sb.append(c)
      i += 1
    (sb.toString.trim, i)

  /**
   * Parse the expression text into literals and extractors. The expression is
   * either: "literal" → one literal, no extractors "literal" / ext → one
   * literal, one extractor "lit1" / ext1 / "lit2" / ext2 → alternating literals
   * and extractors ext → just an extractor (unusual but valid)
   */
  private def parseExpr(
    kw: String,
    expr: String,
    file: String,
    line: Int,
    dataFlow: DataFlow
  ): Option[StepDefinition] =
    // First pull out the leading string literal
    val (leadingLiteral, rest) = expr.trim match
      case s if s.startsWith("\"") =>
        val end = findClosingQuote(s, 1)
        (Some(s.substring(1, end)), s.drop(end + 1).trim)
      case s => (None, s)

    if leadingLiteral.isEmpty && !rest.startsWith("/") then return None // not a recognisable step expression

    // Parse the / chain
    val segments   = splitOnSlash(rest)
    val literals   = scala.collection.mutable.ListBuffer.empty[String]
    val extractors = scala.collection.mutable.ListBuffer.empty[ExtractorInfo]

    leadingLiteral.foreach(literals += _)

    segments.foreach { seg =>
      val trimmed = seg.trim
      if trimmed.startsWith("\"") then
        val end = findClosingQuote(trimmed, 1)
        literals += trimmed.substring(1, end)
      else
        // extractor: name, name(...), name[T], name[T](...)
        val name = trimmed.takeWhile(c => c.isLetterOrDigit || c == '_' || c == '[' || c == ']')
        if name.nonEmpty then extractors += resolveExtractor(name, trimmed)
    }

    if literals.isEmpty && extractors.isEmpty then return None

    val lits = literals.toList
    val exts = extractors.toList
    Some(
      StepDefinition(
        keyword = normaliseKeyword(kw),
        literals = lits,
        extractors = exts,
        displayText = buildDisplayText(lits, exts),
        pattern = buildPattern(lits, exts),
        file = file,
        line = line,
        isStateInjecting = kw.endsWith("S"),
        dataFlow = dataFlow
      )
    )

  /** Find the closing unescaped `"` starting search at `from`. */
  private def findClosingQuote(s: String, from: Int): Int =
    var i = from
    while i < s.length && s(i) != '"' do
      if s(i) == '\\' then i += 1 // skip escaped char
      i += 1
    i

  /**
   * Split expression on top-level `/` characters (not inside parens or
   * brackets, e.g. the `/` inside `oneOf("a/b", "c")`). Depth is tracked across
   * both bracket kinds, but the original character is preserved (a prior
   * version normalised `[`/`]` to `(`/`)`, which silently destroyed
   * `table[User]`'s type parameter before `resolveExtractor` could read it).
   */
  private def splitOnSlash(s: String): List[String] =
    val parts = scala.collection.mutable.ListBuffer.empty[String]
    val cur   = new StringBuilder
    var depth = 0
    s.foreach {
      case c @ ('(' | '[') => depth += 1; cur.append(c)
      case c @ (')' | ']') => depth -= 1; cur.append(c)
      case '/' if depth == 0 =>
        val part = cur.toString.trim
        if part.nonEmpty then parts += part
        cur.clear()
      case c => cur.append(c)
    }
    val last = cur.toString.trim
    if last.nonEmpty then parts += last
    parts.toList

  private def resolveExtractor(name: String, fullText: String): ExtractorInfo =
    // Handle table[T] / tableWithMapping[T]
    if name.startsWith("table") then
      val tpe     = fullText.dropWhile(_ != '[').drop(1).takeWhile(_ != ']')
      val display = if tpe.nonEmpty then s"table[$tpe]" else "table[T]"
      ExtractorInfo(display, s"List[${if tpe.nonEmpty then tpe else "T"}]", "", s"Gherkin data table")
    // Handle oneOf(...) — mirrors DefaultTypedExtractor.oneOf's pattern-building exactly
    // (longest-alternative-first, Pattern.quote per alternative) so the two can't drift.
    else if name == "oneOf" then
      val args = fullText.dropWhile(_ != '(').drop(1).takeWhile(_ != ')')
      val alts = args.split(",").map(_.trim.stripPrefix("\"").stripSuffix("\"")).filter(_.nonEmpty)
      val pat  = s"(${alts.sortBy(-_.length).map(java.util.regex.Pattern.quote).mkString("|")})"
      ExtractorInfo(s"oneOf(${alts.mkString(",")})", "String", pat, s"one of: ${alts.mkString(", ")}")
    // Handle optional("text") — mirrors DefaultTypedExtractor.optional's pattern exactly.
    else if name == "optional" then
      val text = fullText.dropWhile(_ != '"').drop(1).takeWhile(_ != '"')
      ExtractorInfo(
        s"""optional("$text")""",
        "Option[String]",
        s"(${java.util.regex.Pattern.quote(text)})?",
        s"""optional literal "$text""""
      )
    // Handle regex("pat") — the raw pattern is used as-is, same as DefaultTypedExtractor.regex.
    else if name == "regex" then
      val pat = fullText.dropWhile(_ != '"').drop(1).takeWhile(_ != '"')
      ExtractorInfo(s"""regex("$pat")""", "String", pat, s"raw regex: $pat")
    else ExtractorInfo.lookup(name)

  private def buildDisplayText(literals: List[String], extractors: List[ExtractorInfo]): String =
    val extPlaceholders = extractors.map(e => s"{${e.scalaType.split("\\[").head.toLowerCase}}")
    mergeAlternating(literals, extPlaceholders).mkString

  // Literal escaping mirrors `zio.bdd.core.step.StepExpression.toPattern`'s
  // `Pattern.quote` step exactly, so this can't drift from how core builds the
  // same regex for a real StepExpression.
  // Extractors with empty pattern (table[T], docString) capture data that lives on
  // separate lines below the step, not inline in the step text — they contribute
  // nothing to the inline regex, so we use "" rather than "(.+)".
  private def buildPattern(literals: List[String], extractors: List[ExtractorInfo]): String =
    val escapedLits = literals.map(java.util.regex.Pattern.quote)
    val patterns    = extractors.map(_.pattern)
    "^" + mergeAlternating(escapedLits, patterns).mkString + "$"

  private def mergeAlternating(as: List[String], bs: List[String]): List[String] =
    (as, bs) match
      case (Nil, Nil)         => Nil
      case (a :: at, Nil)     => a :: mergeAlternating(at, Nil)
      case (Nil, b :: bt)     => b :: mergeAlternating(Nil, bt)
      case (a :: at, b :: bt) => a :: b :: mergeAlternating(at, bt)

  private def normaliseKeyword(kw: String): String =
    kw.stripSuffix("S") match
      case k if Set("Given", "When", "Then", "And", "But")(k) => k
      case other                                              => other
