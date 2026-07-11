package zio.bdd.intellij.hints

/** A value a step reads from or writes to `ScenarioContext` (State) or `Stage`. */
sealed interface DataRef {
    /** A `Stage[T]` value, keyed by its type. */
    data class StageType(val type: String) : DataRef

    /** A `ScenarioContext` state field, by name. */
    data class StateField(val field: String) : DataRef

    /** A `ScenarioLens` slice type. */
    data class LensSlice(val type: String) : DataRef

    fun render(): String = when (this) {
        is StageType -> "Stage[$type]"
        is StateField -> field
        is LensSlice -> "Lens[$type]"
    }
}

/** What a step reads and sets. Both are multi-valued and may mix State and Stage. */
data class StepDataFlow(val reads: Set<DataRef>, val sets: Set<DataRef>) {
    fun isEmpty(): Boolean = reads.isEmpty() && sets.isEmpty()

    /** The inlay label, e.g. `sets result, Stage[Foo] · reads Stage[Bar]`, or null when empty. */
    fun render(): String? {
        if (isEmpty()) return null
        fun show(refs: Set<DataRef>) = refs.map { it.render() }.sortedBy { it.lowercase() }.joinToString(", ")
        val parts = mutableListOf<String>()
        if (sets.isNotEmpty()) parts += "sets " + show(sets)
        if (reads.isNotEmpty()) parts += "reads " + show(reads)
        return parts.joinToString(" · ")
    }

    companion object {
        val EMPTY = StepDataFlow(emptySet(), emptySet())
    }
}

/**
 * Best-effort, inline-only static analysis of a step definition body to derive
 * what it reads/sets from `ScenarioContext` and `Stage` (#61). A single body can
 * contribute several refs across both sources.
 *
 * State field reads are detected off a `ScenarioContext.get` binder (#62), and calls to same-file
 * helper `def`/`val`s fold in the helper's own reads/sets (#57 partial — see [resolveHelpers]).
 * Limitation: helpers in other files/traits and deeper indirection are still unseen; #63 (runtime
 * observation) is the precise, call-depth-aware upgrade.
 */
object KtStepDataFlow {

    // Stage.put[T](…) or Stage.put(Type(...)) — the staged type.
    private val STAGE_PUT = Regex("""Stage\.put\s*(?:\[\s*([A-Za-z_][\w.]*)\s*])?\s*\(\s*([^)]*)""")

    // Stage.get[T] / getOrElse[T] / getOption[T] — the retrieved type (explicit type arg).
    private val STAGE_GET = Regex("""Stage\.(?:get|getOrElse|getOption)\s*\[\s*([A-Za-z_][\w.]*)\s*]""")

    // ScenarioLens.<method>[S, Slice] — method (1st group) decides read vs write, slice = 2nd type arg.
    private val LENS = Regex("""ScenarioLens\.(\w+)\s*\[\s*[A-Za-z_][\w.]*\s*,\s*([A-Za-z_][\w.]*)\s*]""")

    // The `(` opening a `.copy( … )` — assigned field names are read out with paren/string tracking.
    private val COPY_OPEN = Regex("""\.copy\s*\(""")

    // A leading type constructor in a Stage.put argument, e.g. `FooEvent(x)` / `NativeSpec.Rift(j)`.
    private val LEADING_TYPE = Regex("""^\s*([A-Z][\w]*(?:\.[A-Z][\w]*)*)""")

    // A ScenarioContext.get bound to a flatMap/map lambda param (`{ s =>`, `(s =>`, `{ (s: S) =>`).
    private val CTX_GET_LAMBDA = Regex("""ScenarioContext\.get\s*\.(?:flatMap|map)\s*[({]\s*(?:\(\s*)?([a-z]\w*)""")

    // A ScenarioContext.get bound in a for-comprehension (`s <- ScenarioContext.get`).
    private val CTX_GET_FOR = Regex("""([a-z]\w*)\s*<-\s*ScenarioContext\.get""")

    // A placeholder read: `ScenarioContext.get.map(_.field)` / `.flatMap(_.field)`.
    private val CTX_GET_PLACEHOLDER = Regex("""ScenarioContext\.get\s*\.(?:flatMap|map)\s*\(\s*_\.([a-z]\w*)""")

    // A top-level `def`/`val`/`var` member — a candidate helper whose body may itself read/set.
    private val MEMBER =
        Regex("""(?m)^[ \t]*(?:(?:protected|private|final|override|implicit|inline|lazy)\s+)*(?:def|val|var)\s+([a-z]\w*)""")

    /** The inline reads/sets written directly in [body] (no helper-call resolution). */
    fun analyze(body: String): StepDataFlow {
        val reads = mutableSetOf<DataRef>()
        val sets = mutableSetOf<DataRef>()

        for (m in STAGE_PUT.findAll(body)) {
            val explicit = m.groupValues[1].takeIf { it.isNotEmpty() }
            val fromArg = LEADING_TYPE.find(m.groupValues[2])?.groupValues?.get(1)
            sets += DataRef.StageType(explicit ?: fromArg ?: "?")
        }
        for (m in STAGE_GET.findAll(body)) reads += DataRef.StageType(m.groupValues[1])
        for (m in LENS.findAll(body)) {
            val slice = DataRef.LensSlice(m.groupValues[2])
            if (m.groupValues[1] == "get") reads += slice else sets += slice
        }
        for (m in COPY_OPEN.findAll(body)) {
            val open = body.indexOf('(', m.range.first)
            for (f in copyAssignedFields(body, open)) sets += DataRef.StateField(f)
        }
        for (f in stateFieldReads(body)) reads += DataRef.StateField(f)
        return StepDataFlow(reads, sets)
    }

    /** [body]'s inline reads/sets, plus the reads/sets of any same-file helper `def`/`val` it calls
     *  (from [helpers], built by [resolveHelpers]) — one common form of the #57 helper limitation
     *  (a step whose Stage/State access is wrapped in a sibling `def lastResponse = Stage.get[…]`). */
    fun analyze(body: String, helpers: Map<String, StepDataFlow>): StepDataFlow {
        val inline = analyze(body)
        if (helpers.isEmpty()) return inline
        val reads = inline.reads.toMutableSet()
        val sets = inline.sets.toMutableSet()
        for (name in referencedNames(body, helpers.keys)) {
            helpers.getValue(name).let { reads += it.reads; sets += it.sets }
        }
        return StepDataFlow(reads, sets)
    }

    /** Data-flow of each top-level helper `def`/`val` in [content], resolved transitively (a helper
     *  that calls another helper inherits its reads/sets), with a cycle guard. Pass the result to
     *  `analyze(body, helpers)` for each step body in the same file. */
    fun resolveHelpers(content: String): Map<String, StepDataFlow> {
        val members = MEMBER.findAll(content).toList()
        if (members.isEmpty()) return emptyMap()
        val bodies = LinkedHashMap<String, String>()
        for (m in members) {
            bodies[m.groupValues[1]] = content.substring(m.range.first, memberSpanEnd(content, m.range.first))
        }
        val names = bodies.keys
        val memo = HashMap<String, StepDataFlow>()
        fun resolve(name: String, stack: Set<String>): StepDataFlow {
            memo[name]?.let { return it }
            if (name in stack) return StepDataFlow(emptySet(), emptySet())
            val body = bodies[name] ?: ""
            val inline = analyze(body)
            val reads = inline.reads.toMutableSet()
            val sets = inline.sets.toMutableSet()
            for (ref in referencedNames(body, names)) {
                if (ref == name) continue
                resolve(ref, stack + name).let { reads += it.reads; sets += it.sets }
            }
            return StepDataFlow(reads, sets).also { memo[name] = it }
        }
        return names.associateWith { resolve(it, emptySet()) }
    }

    /** Names from [candidates] used as a standalone identifier in [body] — not as a field access
     *  (`x.name`) or part of a longer identifier — so helper calls match but `r.status` does not. */
    private fun referencedNames(body: String, candidates: Set<String>): Set<String> =
        candidates.filterTo(mutableSetOf()) { Regex("""(?<![.\w])""" + it + """(?![\w])""").containsMatchIn(body) }

    /** End index (exclusive) of the member starting at [start]: its signature line plus the following
     *  strictly-more-indented lines (its body). Bounds each helper to its own body so a `val` local to
     *  a step lambda can't sweep up unrelated steps' Stage/State calls. */
    private fun memberSpanEnd(content: String, start: Int): Int {
        var k = start
        while (k < content.length && (content[k] == ' ' || content[k] == '\t')) k++
        val indent = k - start
        var i = content.indexOf('\n', start)
        if (i < 0) return content.length
        i += 1
        while (i < content.length) {
            var j = i
            while (j < content.length && (content[j] == ' ' || content[j] == '\t')) j++
            val blank = j >= content.length || content[j] == '\n'
            if (!blank && (j - i) <= indent) return i
            val nl = content.indexOf('\n', i)
            if (nl < 0) return content.length
            i = nl + 1
        }
        return content.length
    }

    /** State field reads: fields accessed off a binder bound to `ScenarioContext.get` (a flatMap/map
     *  lambda param or a for-comprehension `<-`), plus `_.field` placeholder reads. Binder-scoped so
     *  unrelated `x.field` accesses don't leak; `.copy` is a write, not a read. */
    private fun stateFieldReads(body: String): List<String> {
        val fields = LinkedHashSet<String>()
        for (m in CTX_GET_PLACEHOLDER.findAll(body)) if (m.groupValues[1] != "copy") fields += m.groupValues[1]
        val binders =
            (CTX_GET_LAMBDA.findAll(body).map { it.groupValues[1] } + CTX_GET_FOR.findAll(body).map { it.groupValues[1] })
                .toSet()
        for (b in binders) {
            for (m in Regex("""\b""" + b + """\.([a-z]\w*)""").findAll(body)) {
                if (m.groupValues[1] != "copy") fields += m.groupValues[1]
            }
        }
        return fields.toList()
    }

    /** The `{ … }` body text of the step registration on 0-based [line] of [content],
     *  or null if not found. Skips the step-call `( … )` then captures the following
     *  brace block (string-aware), so a matched definition's body can be analyzed. */
    fun bodyAt(content: String, line: Int): String? {
        val lineStart = nthLineStart(content, line) ?: return null
        val open = content.indexOf('(', lineStart).takeIf { it >= 0 } ?: return null
        val afterExpr = skipBalanced(content, open, '(', ')') ?: return null
        val brace = content.indexOf('{', afterExpr).takeIf { it >= 0 } ?: return null
        val afterBody = skipBalanced(content, brace, '{', '}') ?: return null
        return content.substring(brace + 1, afterBody - 1)
    }

    /** Field names assigned at the top level of a `.copy( … )` whose `(` is at [open].
     *  Tracks paren depth and string literals so nested calls (`f(a)`) and named args
     *  (`make(w = 1)`) don't leak, and skips `==` so a boolean RHS isn't read as a field. */
    private fun copyAssignedFields(s: String, open: Int): List<String> {
        if (open < 0) return emptyList()
        val fields = mutableListOf<String>()
        var depth = 0
        var i = open
        var inStr = false
        var escape = false
        while (i < s.length) {
            val c = s[i]
            when {
                escape -> escape = false
                c == '\\' && inStr -> escape = true
                c == '"' -> inStr = !inStr
                inStr -> {}
                c == '(' -> depth++
                c == ')' -> {
                    depth--
                    if (depth == 0) return fields
                }
                depth == 1 && c == '=' &&
                    s.getOrNull(i + 1) != '=' && s.getOrNull(i + 1) != '>' && s.getOrNull(i - 1) != '=' &&
                    s.getOrNull(i - 1) != '!' && s.getOrNull(i - 1) != '<' && s.getOrNull(i - 1) != '>' ->
                    identBefore(s, i)?.let { fields += it }
            }
            i++
        }
        return fields
    }

    /** The identifier immediately left of the `=` at [eqIdx], skipping whitespace, or null. */
    private fun identBefore(s: String, eqIdx: Int): String? {
        var j = eqIdx - 1
        while (j >= 0 && s[j].isWhitespace()) j--
        val end = j + 1
        while (j >= 0 && (s[j].isLetterOrDigit() || s[j] == '_')) j--
        val start = j + 1
        return if (start < end && (s[start].isLetter() || s[start] == '_')) s.substring(start, end) else null
    }

    private fun nthLineStart(content: String, line: Int): Int? {
        if (line <= 0) return 0
        var seen = 0
        for (i in content.indices) if (content[i] == '\n' && ++seen == line) return i + 1
        return null
    }

    // Index just past the delimiter matching [open] at [openIdx], tracking string
    // literals so braces/parens inside "…" don't corrupt the depth counter.
    private fun skipBalanced(s: String, openIdx: Int, open: Char, close: Char): Int? {
        var depth = 0
        var i = openIdx
        var inStr = false
        var escape = false
        while (i < s.length) {
            val c = s[i]
            when {
                escape -> escape = false
                c == '\\' && inStr -> escape = true
                c == '"' -> inStr = !inStr
                inStr -> {}
                c == open -> depth++
                c == close -> {
                    depth--
                    if (depth == 0) return i + 1
                }
            }
            i++
        }
        return null
    }
}
