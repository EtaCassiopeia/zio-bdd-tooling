package zio.bdd.lsp

/** Pure helpers for parsing a single line of a .feature file. */
object GherkinLine:

  private val stepKeywords = List("Given", "When", "Then", "And", "But", "*")

  /**
   * Extract the (keyword, body) of a step line. Returns None if the line is not
   * a step (e.g. blank, comment, structural).
   */
  def extractStep(line: String): Option[(String, String)] =
    val stripped = line.trim
    stepKeywords.collectFirst {
      case kw if stripped.startsWith(s"$kw ") =>
        (kw, stripped.drop(kw.length + 1).trim)
    }
