package zio.bdd.intellij.lang

/**
 * Feature-file view of the `@mock(name, ...)` tag — the IntelliJ counterpart of
 * the LSP `MockTag`. Locates catalog-name references and their offsets so the
 * completion contributor and annotator can offer names and flag unknown ones.
 *
 * Mirrors zio-bdd's `MockTag`: comma-separated names, trimmed, empties dropped.
 */
object KtMockTag {

    /** One catalog-name reference on a line: the name and its [start, end) offsets. */
    data class Ref(val name: String, val start: Int, val end: Int)

    private val call = Regex("""@mock\(([^)]*)\)""")

    /** Every catalog-name reference across all `@mock(...)` tags on [line]. */
    fun refs(line: String): List<Ref> =
        call.findAll(line).flatMap { m ->
            namesWithOffsets(m.groupValues[1], m.groups[1]!!.range.first)
        }.toList()

    /** True when [prefix] (a line up to the caret) ends inside an unclosed `@mock(`. */
    fun isInsideMockCall(prefix: String): Boolean {
        val idx = prefix.lastIndexOf("@mock(")
        return idx >= 0 && !prefix.substring(idx + "@mock(".length).contains(")")
    }

    /** The partial name being typed at the caret inside a `@mock(...)` — text after the last `(` or `,`. */
    fun partialAtCaret(prefix: String): String {
        val open  = prefix.lastIndexOf("@mock(")
        if (open < 0) return ""
        val after = prefix.substring(open + "@mock(".length)
        return after.substringAfterLast(',').trimStart()
    }

    private fun namesWithOffsets(group: String, base: Int): List<Ref> {
        val out = mutableListOf<Ref>()
        var offset = 0
        for (part in group.split(",")) {
            val trimmed = part.trim()
            if (trimmed.isNotEmpty()) {
                val start = base + offset + part.indexOf(trimmed)
                out += Ref(trimmed, start, start + trimmed.length)
            }
            offset += part.length + 1 // advance past the segment and its comma
        }
        return out
    }
}
