package zio.bdd.lsp

import zio.bdd.core.step.DefaultTypedExtractor

/** A step definition extracted from a Scala source file via static scanning. */
case class StepDefinition(
  /** Normalised keyword: Given | When | Then | And | But */
  keyword: String,
  /** Literal text segments in the step expression, in order */
  literals: List[String],
  /** Extractor info for each typed parameter, in order */
  extractors: List[ExtractorInfo],
  /**
   * Human-readable display text with {type} placeholders. e.g. "the user adds
   * {int} units of {string} at price {double}"
   */
  displayText: String,
  /**
   * Full assembled regex pattern for step matching. e.g. "^the user adds
   * (-?\\d+) units of (\".*\"|.*) at price ([-+]?[0-9]*\\.?[0-9]+)$"
   */
  pattern: String,
  /** Absolute path to the Scala source file */
  file: String,
  /** 0-based line number */
  line: Int,
  /** True when registered via GivenS / WhenS / ThenS / AndS / ButS */
  isStateInjecting: Boolean,
  /**
   * Reads/sets derived from the step body (State fields, Stage types, lens
   * slices); #58.
   */
  dataFlow: DataFlow = DataFlow.empty
)

/** Metadata about a single typed extractor in a step expression. */
case class ExtractorInfo(
  /**
   * Extractor name as it appears in source: "int", "string", "oneOf(...)", etc.
   */
  name: String,
  /** Scala return type: "Int", "String", "List[T]", "Option[String]" */
  scalaType: String,
  /** Regex fragment contributed by this extractor */
  pattern: String,
  /** Short human-readable description for hover / completion docs */
  description: String
)

object ExtractorInfo:

  /**
   * Human-facing metadata (display type + doc string) for the built-in
   * extractors. Deliberately does NOT include the regex pattern — that comes
   * from `DefaultTypedExtractor.byName` below, so this table can never drift
   * out of sync with the patterns zio-bdd actually matches against at runtime
   * (a real bug found during review: a hand-copied `double` pattern here didn't
   * match the real one in core).
   */
  private case class Meta(scalaType: String, description: String)

  private val builtinMeta: Map[String, Meta] = Map(
    "string"     -> Meta("String", "quoted or unquoted text"),
    "word"       -> Meta("String", "single token, no whitespace"),
    "rest"       -> Meta("String", "greedy remainder of step text"),
    "int"        -> Meta("Int", "signed integer, e.g. -7, 0, 42"),
    "long"       -> Meta("Long", "signed long integer"),
    "double"     -> Meta("Double", "decimal number, e.g. 9.99"),
    "bigDecimal" -> Meta("BigDecimal", "exact-precision decimal (use for financial values)"),
    "boolean"    -> Meta("Boolean", "true or false"),
    "uuid"       -> Meta("UUID", "UUID string"),
    "docString"  -> Meta("String", "triple-quoted doc string block")
  )

  /**
   * Built-in extractors, with patterns sourced from core's real extractor
   * instances.
   */
  val builtins: Map[String, ExtractorInfo] =
    builtinMeta.map { case (name, meta) =>
      val pattern = DefaultTypedExtractor.byName.get(name).map(_.pattern).getOrElse("(.*)")
      name -> ExtractorInfo(name, meta.scalaType, pattern, meta.description)
    }

  val allNames: List[String] =
    builtins.keys.toList.sorted ++ List("table[T]", "oneOf(...)", "optional(\"...\")", "regex(\"...\")")

  def lookup(name: String): ExtractorInfo =
    builtins.getOrElse(name, ExtractorInfo(name, "String", """(.*)""", s"custom extractor: $name"))
