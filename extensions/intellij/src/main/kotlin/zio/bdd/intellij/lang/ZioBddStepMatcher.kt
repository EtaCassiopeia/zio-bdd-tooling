package zio.bdd.intellij.lang

/** Shared step-matching logic used by the annotator, goto handler, and completion contributor. */
object ZioBddStepMatcher {

    private val COL_PLACEHOLDER = Regex("""\<(\w+)\>""")

    // Representative values tried as substitutes for <col> placeholders.
    // "0"    matches int, long, double, float, bigint, decimal, string ([^,\s]+), word (\w+), generic (.+)
    // "true" matches boolean, string, word, generic — covers the one type "0" does not
    private val OUTLINE_SUBS = listOf("0", "true")

    /**
     * Returns true if [text] (a step line from a feature file) matches [def].
     *
     * Handles all step forms:
     *  1. Concrete steps — direct regex match against [def.pattern].
     *  2. DocString / DataTable steps — pattern ends with `(.+)$`; relaxed to `(.*)$`.
     *  3. Scenario Outline steps — `<col>` placeholders replace extractor arguments.
     *     The placeholders can appear anywhere: all slots (`<a> and <b>`), some slots
     *     (`<a> and 0`), or surrounded by quotes (`"<email>"`).  We try each value in
     *     [OUTLINE_SUBS] as a substitute for every placeholder and run the regex; the
     *     first substitution that produces a full match wins.  Using multiple substitutes
     *     covers every built-in extractor type without needing to know which type is used:
     *       "0"     — int, long, double, float, bigint, decimal, string, word, generic
     *       "true"  — boolean, string, word, generic
     */
    fun matchesStep(text: String, def: KtStepDefinition): Boolean {
        if (!COL_PLACEHOLDER.containsMatchIn(text)) {
            return tryMatch(text, def.pattern)
        }
        return OUTLINE_SUBS.any { sub ->
            tryMatch(COL_PLACEHOLDER.replace(text, sub), def.pattern)
        }
    }

    fun candidatesFor(keyword: String, defs: List<KtStepDefinition>): List<KtStepDefinition> =
        when (keyword) {
            "And", "But" -> defs
            else -> defs.filter { it.keyword == keyword || it.keyword == "And" || it.keyword == "But" }
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
