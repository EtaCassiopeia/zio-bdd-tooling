package zio.bdd.intellij.lang

/** A zio-bdd `@Suite`-annotated step suite and the feature directories it owns. */
data class KtSuiteDecl(val name: String, val featureDirs: List<String>)

/**
 * Parses `@Suite(featureDirs = Array("..."))` declarations from Scala source and
 * resolves which suite owns a given `.feature` file by directory containment.
 *
 * This is the authoritative feature→suite mapping used to build the sbt
 * `testOnly` selector: a feature file belongs to exactly one suite (the one whose
 * declared `featureDirs` contains it), so the run targets that single suite
 * instead of fanning out across every suite via a `"*"` selector (#41).
 *
 * Pure (no IntelliJ platform types) so it is unit-testable without booting an IDE.
 */
object KtSuiteExtractor {

    private val SUITE_ANNOTATION = Regex("""@Suite\s*\(""")
    private val FEATURE_DIRS = Regex("""featureDirs\s*=\s*Array\s*\(([^)]*)\)""", RegexOption.DOT_MATCHES_ALL)
    private val STRING_LITERAL = Regex("\"([^\"]*)\"")
    // The declaration the annotation applies to — anchored (^) to immediately after
    // the @Suite(...), skipping only whitespace, comments, further annotations, and
    // modifiers. Searching the whole rest of the file could grab an object/class/trait
    // token out of an intervening comment or string (#55); @Suite binds to the next
    // declaration, per Scala.
    private val DECL_AFTER_ANNOTATION = Regex(
        "^\\s*(?:(?://[^\\n]*|/\\*.*?\\*/|@\\w+(?:\\([^)]*\\))?)\\s*)*" +
            "(?:(?:final|sealed|abstract|case|private|protected|open|implicit|lazy)\\s+)*" +
            "(?:object|trait|class)\\s+([A-Za-z_][A-Za-z0-9_]*)",
        RegexOption.DOT_MATCHES_ALL,
    )

    fun extractFromSource(content: String): List<KtSuiteDecl> {
        val out = mutableListOf<KtSuiteDecl>()
        for (m in SUITE_ANNOTATION.findAll(content)) {
            val open = m.range.last // index of the '(' after @Suite
            val close = matchingParen(content, open)
            if (close < 0) continue
            val args = content.substring(open + 1, close)
            val dirs = FEATURE_DIRS.find(args)?.groupValues?.get(1)
                ?.let { inner -> STRING_LITERAL.findAll(inner).map { it.groupValues[1] }.toList() }
                ?: emptyList()
            val name = DECL_AFTER_ANNOTATION.find(content.substring(close + 1))?.groupValues?.get(1) ?: continue
            out += KtSuiteDecl(name, dirs)
        }
        return out
    }

    /**
     * The name of the suite whose `featureDirs` contains [featureAbsPath], or null
     * if none does. Relative feature dirs are resolved against [projectBasePath].
     */
    fun ownerFor(featureAbsPath: String, projectBasePath: String?, suites: List<KtSuiteDecl>): String? {
        val feature = featureAbsPath.replace('\\', '/')
        val base = projectBasePath?.replace('\\', '/')?.trimEnd('/')
        return suites.firstOrNull { suite ->
            suite.featureDirs.any { dir ->
                val d = dir.replace('\\', '/')
                val abs = (if (d.startsWith("/") || base == null) d else "$base/${d.trimStart('/')}").trimEnd('/')
                feature == abs || feature.startsWith("$abs/")
            }
        }?.name
    }

    // Index of the ')' that closes the '(' at [openIdx], accounting for nested parens.
    private fun matchingParen(s: String, openIdx: Int): Int {
        var depth = 0
        var i = openIdx
        while (i < s.length) {
            when (s[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        return -1
    }
}
