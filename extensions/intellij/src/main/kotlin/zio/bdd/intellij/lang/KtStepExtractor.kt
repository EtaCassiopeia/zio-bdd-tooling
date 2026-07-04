package zio.bdd.intellij.lang

data class KtStepDefinition(
    val keyword: String,
    val displayText: String,
    val pattern: String,
    val literals: List<String>,
    val extractorCount: Int,
    val file: String,
    val line: Int,
)

/** A `@mock(name)` catalog entry — mirrors zio-bdd's MockSummary(name, sourceKind). */
data class KtMockSummary(
    val name: String,
    val sourceKind: String,
)

/**
 * Kotlin port of StepExtractor.scala for use inside the IntelliJ plugin JVM.
 * Mirrors the same regex patterns and expression-parsing logic so that
 * IDE-native features (annotator, goto, completion) agree with the LSP server.
 */
object KtStepExtractor {

    private val STEP_CALL = Regex(
        """(?m)^[ \t]*(Given|When|Then|And|But|GivenS|WhenS|ThenS|AndS|ButS)[ \t]*\(""",
        setOf(RegexOption.MULTILINE)
    )

    private val BUILTIN_PATTERNS = mapOf(
        "int"     to Pair("(-?\\d+)",             "int"),
        "long"    to Pair("(-?\\d+)",             "long"),
        "double"  to Pair("(-?\\d+\\.?\\d*)",     "double"),
        "float"   to Pair("(-?\\d+\\.?\\d*)",     "float"),
        "string"  to Pair("""(".*?"|[^,\s]+)""",  "string"),
        "word"    to Pair("""(\w+)""",             "word"),
        "boolean" to Pair("(true|false)",          "boolean"),
        "bigint"  to Pair("(-?\\d+)",             "bigint"),
        "decimal" to Pair("(-?\\d+\\.?\\d*)",     "decimal"),
    )

    fun extractFromSource(content: String, filePath: String): List<KtStepDefinition> =
        STEP_CALL.findAll(content).mapNotNull { m ->
            val kw   = m.groupValues[1]
            val expr = extractExpr(content, m.range.last + 1)
            val line = content.substring(0, m.range.first).count { it == '\n' }
            parseExpr(kw, expr, filePath, line)
        }.toList()

    /**
     * Extracts the text inside the outer parentheses of a step call, starting
     * immediately after the opening `(`.  Tracks string literals so that `(` and `)`
     * inside e.g. `regex("(\\d+)")` or `Given("price is ($)")` do not corrupt the
     * paren depth counter.
     */
    private fun extractExpr(content: String, startPos: Int): String {
        var depth = 1
        var i = startPos
        var inStr = false
        var escape = false
        val sb = StringBuilder()
        while (i < content.length && depth > 0) {
            val c = content[i]
            when {
                escape             -> { sb.append(c); escape = false }
                c == '\\' && inStr -> { sb.append(c); escape = true }
                c == '"'           -> { sb.append(c); inStr = !inStr }
                inStr              -> sb.append(c)
                c == '('           -> { depth++; sb.append(c) }
                c == ')'           -> { depth--; if (depth > 0) sb.append(c) }
                else               -> sb.append(c)
            }
            i++
        }
        return sb.toString().trim()
    }

    private fun parseExpr(kw: String, expr: String, file: String, line: Int): KtStepDefinition? {
        val trimmed = expr.trim()
        val (lead, rest) = if (trimmed.startsWith('"')) {
            val end = findClosingQuote(trimmed, 1)
            Pair(trimmed.substring(1, end), trimmed.drop(end + 1).trim())
        } else {
            Pair(null, trimmed)
        }
        if (lead == null && !rest.startsWith('/')) return null

        val lits = mutableListOf<String>()
        val exts = mutableListOf<Pair<String, String>>()
        lead?.let { lits += it }

        splitOnSlash(rest).forEach { seg ->
            val s = seg.trim()
            if (s.startsWith('"')) {
                lits += s.substring(1, findClosingQuote(s, 1))
            } else {
                val name = s.takeWhile { c -> c.isLetterOrDigit() || c == '_' || c == '[' || c == ']' }
                if (name.isNotEmpty()) exts += resolveExtractor(name, s)
            }
        }
        if (lits.isEmpty() && exts.isEmpty()) return null

        val keyword     = normalizeKeyword(kw)
        val displayText = buildDisplayText(lits, exts)
        val pattern     = buildPattern(lits, exts)
        return KtStepDefinition(keyword, displayText, pattern, lits, exts.size, file, line)
    }

    /** Returns the index of the closing unescaped `"` starting from [from]. */
    private fun findClosingQuote(s: String, from: Int): Int {
        var i = from
        while (i < s.length) {
            when {
                s[i] == '\\' -> i += 2  // skip escaped char
                s[i] == '"'  -> return i
                else         -> i++
            }
        }
        return i
    }

    /**
     * Splits [s] on top-level `/` characters (i.e. outside `()`, `[]`, and string
     * literals).  A `/` inside a regex or quoted string must not be treated as a
     * separator — e.g. `When("rate is " / string / " req/s")`.
     */
    private fun splitOnSlash(s: String): List<String> {
        val parts = mutableListOf<String>()
        val cur   = StringBuilder()
        var depth  = 0
        var inStr  = false
        var escape = false
        s.forEach { c ->
            when {
                escape             -> { cur.append(c); escape = false }
                c == '\\' && inStr -> { cur.append(c); escape = true }
                c == '"'           -> { cur.append(c); inStr = !inStr }
                inStr              -> cur.append(c)
                c == '(' || c == '[' -> { depth++; cur.append(c) }
                c == ')' || c == ']' -> { depth--; cur.append(c) }
                c == '/' && depth == 0 -> {
                    cur.toString().trim().takeIf { it.isNotEmpty() }?.let { parts += it }
                    cur.clear()
                }
                else -> cur.append(c)
            }
        }
        cur.toString().trim().takeIf { it.isNotEmpty() }?.let { parts += it }
        return parts
    }

    private fun resolveExtractor(name: String, fullText: String): Pair<String, String> = when {
        name.startsWith("table") -> {
            val tpe = fullText.dropWhile { it != '[' }.drop(1).takeWhile { it != ']' }
            Pair(if (tpe.isNotEmpty()) "table[$tpe]" else "table[T]", "(.+)")
        }
        name == "oneOf" -> {
            val inner = fullText.dropWhile { it != '(' }.drop(1).takeWhile { it != ')' }
            val alts  = inner.split(",").map { it.trim().removeSurrounding("\"") }.filter { it.isNotEmpty() }
            val pat   = "(${alts.sortedByDescending { it.length }.joinToString("|") { java.util.regex.Pattern.quote(it) }})"
            Pair("oneOf(${alts.joinToString(",")})", pat)
        }
        name == "optional" -> {
            // Use findClosingQuote to handle escaped quotes inside the literal
            val qStart = fullText.indexOf('"')
            val text   = if (qStart < 0) "" else fullText.substring(qStart + 1, findClosingQuote(fullText, qStart + 1))
            Pair("optional(\"$text\")", "(${java.util.regex.Pattern.quote(text)})?")
        }
        name == "regex" -> {
            val qStart = fullText.indexOf('"')
            val pat    = if (qStart < 0) ".+" else fullText.substring(qStart + 1, findClosingQuote(fullText, qStart + 1))
            Pair("regex(\"$pat\")", pat)
        }
        else -> {
            val (pat, display) = BUILTIN_PATTERNS[name.lowercase()] ?: Pair("(.+)", name)
            Pair("{$display}", pat)
        }
    }

    private fun buildDisplayText(lits: List<String>, exts: List<Pair<String, String>>): String =
        mergeAlternating(lits, exts.map { it.first }).joinToString("")

    private fun buildPattern(lits: List<String>, exts: List<Pair<String, String>>): String {
        val escaped  = lits.map { java.util.regex.Pattern.quote(it) }
        val patterns = exts.map { it.second.ifEmpty { "(.+)" } }
        return "^" + mergeAlternating(escaped, patterns).joinToString("") + "$"
    }

    private fun mergeAlternating(as_: List<String>, bs: List<String>): List<String> {
        val out = mutableListOf<String>()
        val len = maxOf(as_.size, bs.size)
        for (i in 0 until len) {
            if (i < as_.size) out += as_[i]
            if (i < bs.size)  out += bs[i]
        }
        return out
    }

    private fun normalizeKeyword(kw: String): String =
        if (kw.endsWith('S')) kw.dropLast(1) else kw
}
