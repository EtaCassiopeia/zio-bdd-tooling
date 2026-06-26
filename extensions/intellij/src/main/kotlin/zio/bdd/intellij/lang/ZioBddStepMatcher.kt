package zio.bdd.intellij.lang

/** Shared step-matching logic used by the annotator, goto handler, and completion contributor. */
object ZioBddStepMatcher {

    private val COL_PLACEHOLDER = Regex("""\<(\w+)\>""")

    // Representative values covering all built-in extractor types:
    //   "0"    — int, long, double, float, bigint, decimal, string ([^,\s]+), word (\w+), generic (.+)
    //   "true" — boolean, string, word, generic
    private val OUTLINE_SUBS = listOf("0", "true")

    /**
     * Returns true if [text] (a step line from a feature file) matches [def].
     *
     * Handles all step forms:
     *  1. Concrete steps — direct regex match against [def.pattern].
     *  2. DocString / DataTable steps — pattern ends with `(.+)$`; relaxed to `(.*)$`.
     *  3. Scenario Outline steps — `<col>` placeholders can occupy any extractor slot,
     *     mixed with concrete values (e.g. `I add <a> and 0`) or inside quotes
     *     (e.g. `with "<email>" and "anypass"`).  We try all positional combinations of
     *     [OUTLINE_SUBS] so that mixed-type columns (boolean slot next to int slot) work
     *     correctly without knowing which extractor type is at each position.
     */
    fun matchesStep(text: String, def: KtStepDefinition): Boolean {
        if (!COL_PLACEHOLDER.containsMatchIn(text)) {
            return tryMatch(text, def.pattern)
        }
        val placeholders = COL_PLACEHOLDER.findAll(text).toList()
        val n = placeholders.size
        // Cap combinatorial explosion: >4 mixed-type placeholders is pathological in BDD
        if (n > 4) {
            return OUTLINE_SUBS.any { sub ->
                tryMatch(COL_PLACEHOLDER.replace(text, sub), def.pattern)
            }
        }
        return outlineCombinations(OUTLINE_SUBS, n).any { subs ->
            tryMatch(substitutePositional(text, placeholders, subs), def.pattern)
        }
    }

    fun candidatesFor(keyword: String, defs: List<KtStepDefinition>): List<KtStepDefinition> =
        when (keyword) {
            "And", "But" -> defs
            else -> defs.filter { it.keyword == keyword || it.keyword == "And" || it.keyword == "But" }
        }

    // ── Internals ──────────────────────────────────────────────────────────

    /** Substitutes each placeholder in [matches] with the corresponding value from [subs]. */
    private fun substitutePositional(text: String, matches: List<MatchResult>, subs: List<String>): String {
        val sb = StringBuilder()
        var prev = 0
        matches.forEachIndexed { i, m ->
            sb.append(text, prev, m.range.first)
            sb.append(subs[i])
            prev = m.range.last + 1
        }
        sb.append(text, prev, text.length)
        return sb.toString()
    }

    /** Returns all n-length lists drawn from [values] — values.size^n combinations. */
    private fun outlineCombinations(values: List<String>, n: Int): List<List<String>> {
        if (n == 0) return listOf(emptyList())
        return outlineCombinations(values, n - 1).flatMap { prefix ->
            values.map { v -> prefix + v }
        }
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
