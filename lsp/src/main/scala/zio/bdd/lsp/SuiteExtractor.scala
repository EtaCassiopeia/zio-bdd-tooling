package zio.bdd.lsp

import scala.util.matching.Regex

/**
 * A zio-bdd `@Suite`-annotated step suite and the feature directories it owns.
 */
final case class SuiteDecl(name: String, featureDirs: List[String])

/**
 * Parses `@Suite(featureDirs = Array("..."))` declarations from Scala source
 * and resolves which suite owns a given `.feature` file by directory
 * containment.
 *
 * A feature file belongs to exactly one suite — the one whose declared
 * `featureDirs` contains it — so the sbt `testOnly` selector can target that
 * single suite instead of fanning out across every suite via a `"*"` selector
 * (#49, the LSP/VS Code counterpart of #41).
 *
 * Source-text scanning (regex, no scalameta) to match `StepExtractor`'s
 * no-compile-step design.
 */
object SuiteExtractor:

  private val suiteAnnotation: Regex = """@Suite\s*\(""".r
  private val featureDirsRe: Regex   = """(?s)featureDirs\s*=\s*Array\s*\(([^)]*)\)""".r
  private val stringLit: Regex       = "\"([^\"]*)\"".r
  private val declName: Regex =
    """(?s)\b(?:object|trait|(?:final\s+|abstract\s+|sealed\s+|case\s+)*class)\s+([A-Za-z_][A-Za-z0-9_]*)""".r

  def extractFromSource(content: String): List[SuiteDecl] =
    suiteAnnotation
      .findAllMatchIn(content)
      .flatMap { m =>
        val open  = m.end - 1 // index of the '(' after @Suite
        val close = matchingParen(content, open)
        if close < 0 then None
        else
          val args = content.substring(open + 1, close)
          val dirs = featureDirsRe
            .findFirstMatchIn(args)
            .map(md => stringLit.findAllMatchIn(md.group(1)).map(_.group(1)).toList)
            .getOrElse(Nil)
          declName.findFirstMatchIn(content.substring(close + 1)).map(dm => SuiteDecl(dm.group(1), dirs))
      }
      .toList

  /**
   * The name of the suite whose `featureDirs` contains [featureAbsPath], or
   * None. Base-agnostic: a relative `featureDir` matches when the feature's
   * absolute path ends with `/<dir>` (an exact-file dir) or contains `/<dir>/`
   * (a directory).
   */
  def ownerFor(featureAbsPath: String, suites: List[SuiteDecl]): Option[String] =
    val feature = featureAbsPath.replace('\\', '/')
    suites.collectFirst { case SuiteDecl(name, dirs) if dirs.exists(covers(feature, _)) => name }

  private def covers(feature: String, dir: String): Boolean =
    val d = dir.replace('\\', '/').stripPrefix("./").stripSuffix("/")
    if d.isEmpty then false
    else if d.startsWith("/") then feature == d || feature.startsWith(d + "/")
    else feature.endsWith("/" + d) || feature.contains("/" + d + "/")

  // Index of the ')' closing the '(' at [openIdx], accounting for nested parens.
  private def matchingParen(s: String, openIdx: Int): Int =
    var depth  = 0
    var i      = openIdx
    var result = -1
    while i < s.length && result < 0 do
      s.charAt(i) match
        case '(' => depth += 1
        case ')' =>
          depth -= 1
          if depth == 0 then result = i
        case _ => ()
      i += 1
    result
