package zio.bdd.intellij.lang

/**
 * Reproduces zio-bdd-gherkin's Scenario Outline expansion naming so a single
 * Example can be targeted by `--scenario-name`. The parser names the data rows of
 * each Examples block `"$baseLabel - Example N"`, 1-based and **restarting per
 * block**, where `baseLabel` is the outline name plus the block name when the
 * Examples block is named (GherkinParser: `baseLabel = if (block.name.nonEmpty)
 * s"${outline} - ${block.name}" else outline`).
 *
 * Returns empty when the outline has no literal example rows — e.g. a `@property`
 * block with only a header — since those expand to a single bare-named scenario.
 */
object OutlineExamples {
    private val SCENARIO_KEYWORDS = listOf(
        "Scenario Outline:", "Scenario Template:", "Scenario:", "Example:", "Feature:", "Rule:", "Background:",
    )

    fun exampleNames(featureText: String, outlineName: String): List<String> {
        val lines = featureText.lines()
        val start = lines.indexOfFirst { line ->
            val t = line.trim()
            (t.startsWith("Scenario Outline:") || t.startsWith("Scenario Template:")) &&
                t.substringAfter(':').trim() == outlineName
        }
        if (start < 0) return emptyList()

        val names = mutableListOf<String>()
        var baseLabel = outlineName
        var inExamples = false
        var sawHeaderRow = false
        var n = 0
        for (i in (start + 1) until lines.size) {
            val t = lines[i].trim()
            if (SCENARIO_KEYWORDS.any { t.startsWith(it) }) break
            when {
                // "Examples" / "Scenarios" (alias) start a table; a *named* block
                // ("Examples: Happy cases") prefixes the row names. The first "|" row
                // is the header, every subsequent "|" row is a numbered data row.
                t.startsWith("Examples") || t.startsWith("Scenarios") -> {
                    val block = examplesBlockName(t)
                    baseLabel = if (block.isNotEmpty()) "$outlineName - $block" else outlineName
                    inExamples = true; sawHeaderRow = false; n = 0
                }
                inExamples && t.startsWith("|") ->
                    if (!sawHeaderRow) sawHeaderRow = true
                    else { n++; names += "$baseLabel - Example $n" }
            }
        }
        return names
    }

    // Text after the "Examples"/"Scenarios" keyword and its colon, e.g.
    // "Examples: Happy cases" -> "Happy cases"; "Examples:" -> "".
    private fun examplesBlockName(line: String): String {
        val rest = when {
            line.startsWith("Examples") -> line.removePrefix("Examples")
            line.startsWith("Scenarios") -> line.removePrefix("Scenarios")
            else -> return ""
        }
        return rest.trimStart().removePrefix(":").trim()
    }
}
