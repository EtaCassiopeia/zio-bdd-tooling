package zio.bdd.intellij.lang

/** Shared step-matching logic used by the annotator, goto handler, and completion contributor. */
object ZioBddStepMatcher {

    private val COL_PLACEHOLDER = Regex("""\<(\w+)\>""")

    /**
     * Returns true if [text] (a step line from a feature file) matches [def].
     *
     * Handles three cases:
     *  1. Normal steps — direct regex match against [def.pattern].
     *  2. DocString / DataTable steps — pattern ends with `(.+)$`; relaxed to `(.*)$`.
     *  3. Scenario Outline column placeholders like `<email>`, `<amount>` — comparing
     *     the literal segments between placeholders against [def.literals] directly.
     *     We cannot substitute a single representative value because the extractor type
     *     (int, string, boolean, …) is unknown at this point; literal-segment comparison
     *     is type-agnostic and always correct.
     */
    fun matchesStep(text: String, def: KtStepDefinition): Boolean {
        if (!COL_PLACEHOLDER.containsMatchIn(text)) {
            // No placeholders: ordinary regex match (includes docstring relaxation)
            return tryMatch(text, def.pattern)
        }
        // Scenario Outline step: compare literal segments instead of running the regex
        return matchesOutline(text, def)
    }

    fun candidatesFor(keyword: String, defs: List<KtStepDefinition>): List<KtStepDefinition> =
        when (keyword) {
            "And", "But" -> defs
            else -> defs.filter { it.keyword == keyword || it.keyword == "And" || it.keyword == "But" }
        }

    /**
     * For Scenario Outline steps the placeholder values are unknown at parse time.
     * Instead of guessing a representative value we compare the literal text segments
     * that sit *between* the `<col>` tokens against the literal segments from the step
     * definition.  The match is type-agnostic: `<a>` matches any extractor (int, string,
     * boolean, …) as long as the surrounding literal text aligns.
     *
     * Example: `"I add <a> and <b>"` → segments `["I add ", " and "]`
     *          def literals for `When("I add " / int / " and " / int)` → `["I add ", " and "]`
     *          → match ✓
     */
    private fun matchesOutline(text: String, def: KtStepDefinition): Boolean {
        val placeholderCount = COL_PLACEHOLDER.findAll(text).count()
        if (placeholderCount != def.extractorCount) return false

        val rawParts = COL_PLACEHOLDER.split(text)
        // Drop boundary empty strings that appear when text starts/ends with a placeholder
        val segments = rawParts
            .let { if (it.first().isEmpty()) it.drop(1) else it }
            .let { if (it.last().isEmpty())  it.dropLast(1) else it }

        return segments == def.literals
    }

    private fun tryMatch(text: String, pattern: String): Boolean {
        try { if (Regex(pattern).matches(text)) return true } catch (_: Exception) { return false }
        // DocString / DataTable: the trailing argument isn't on the step text line,
        // so the pattern ends with (.+)$ but the text has nothing after the colon.
        // Relax to (.*)$ to allow an empty trailing match.
        if (pattern.endsWith("(.+)\$")) {
            val relaxed = pattern.dropLast("(.+)\$".length) + "(.*)\$"
            try { return Regex(relaxed).matches(text) } catch (_: Exception) {}
        }
        return false
    }
}
