package zio.bdd.intellij.lang

/** Shared step-matching logic used by the annotator, goto handler, and completion contributor. */
object ZioBddStepMatcher {

    private val COL_PLACEHOLDER = Regex("""\<(\w+)\>""")

    /**
     * Returns true if [text] (a step line from a feature file) matches [def].
     *
     * Handles three cases the naive regex check misses:
     *  1. Scenario Outline column placeholders like `<email>` — substituted with
     *     a representative literal so the extractor regex fires correctly.
     *  2. DocString / DataTable steps — the step line ends right after the `:`
     *     but the definition pattern ends with `(.+)$`.  We relax to `(.*)$`.
     *  3. The two cases can combine (placeholder + DocString).
     */
    fun matchesStep(text: String, def: KtStepDefinition): Boolean {
        // Substitute Outline column placeholders with a representative value so
        // the extractor regex works the same as it does at runtime.
        val effective = COL_PLACEHOLDER.replace(text, "placeholder")
        return tryMatch(effective, def.pattern)
    }

    fun candidatesFor(keyword: String, defs: List<KtStepDefinition>): List<KtStepDefinition> =
        when (keyword) {
            "And", "But" -> defs
            else -> defs.filter { it.keyword == keyword || it.keyword == "And" || it.keyword == "But" }
        }

    private fun tryMatch(text: String, pattern: String): Boolean {
        try { if (Regex(pattern).matches(text)) return true } catch (_: Exception) { return false }
        // DocString / DataTable: the trailing argument isn't part of the step text line,
        // so the pattern ends with (.+)$ but the step text has nothing after the keyword.
        // Relax to (.*)$ to allow an empty match for that trailing extractor.
        if (pattern.endsWith("(.+)\$")) {
            val relaxed = pattern.dropLast("(.+)\$".length) + "(.*)\$"
            try { return Regex(relaxed).matches(text) } catch (_: Exception) {}
        }
        return false
    }
}
