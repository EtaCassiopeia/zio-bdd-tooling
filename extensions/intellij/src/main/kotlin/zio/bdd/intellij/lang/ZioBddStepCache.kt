package zio.bdd.intellij.lang

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VirtualFile
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class ZioBddStepCache(private val project: Project) {

    // Single volatile reference — swapping it is atomic, so readers never see
    // a partially-populated map during a refresh.
    @Volatile private var snapshot: Map<String, List<KtStepDefinition>> = emptyMap()

    private val lastScan   = AtomicLong(0L)
    private val refreshing = AtomicBoolean(false)

    companion object {
        private const val TTL_MS = 30_000L
        fun getInstance(project: Project): ZioBddStepCache = project.service()
    }

    fun getStepDefinitions(): List<KtStepDefinition> {
        if (System.currentTimeMillis() - lastScan.get() > TTL_MS) scheduleRefresh()
        return snapshot.values.flatten()
    }

    /** Blocking warm-up for callers (e.g. tool window) that need the cache before proceeding. */
    fun ensureWarmed() {
        if (snapshot.isEmpty() || System.currentTimeMillis() - lastScan.get() > TTL_MS) {
            // Only one thread does the blocking refresh; others return with whatever
            // is already in the snapshot rather than stacking up blocking calls.
            if (refreshing.compareAndSet(false, true)) {
                try { doRefresh() } finally { refreshing.set(false) }
            }
        }
    }

    fun invalidate() {
        snapshot  = emptyMap()
        lastScan.set(0L)
    }

    /** Returns the sbt test selector (e.g. `"*CalculatorSuite*"`) for the suite(s)
     *  whose step definitions match at least one step in [featureFile].
     *  Falls back to `"*"` when the cache is empty or nothing matches. */
    fun suiteNamesForFeature(featureFile: VirtualFile): String {
        if (System.currentTimeMillis() - lastScan.get() > TTL_MS) scheduleRefresh()
        val current = snapshot
        if (current.isEmpty()) return "*"
        val steps = extractFeatureSteps(featureFile)
        if (steps.isEmpty()) return "*"
        val matched = current.entries
            .filter { (_, defs) ->
                defs.isNotEmpty() && steps.any { (kw, text) ->
                    ZioBddStepMatcher.candidatesFor(kw, defs)
                        .any { def -> ZioBddStepMatcher.matchesStep(text, def) }
                }
            }
            .map { (path, _) -> "*${java.io.File(path).nameWithoutExtension}*" }
        return if (matched.isEmpty()) "*" else matched.joinToString(" ")
    }

    /** Like [suiteNamesForFeature] but returns bare names (without wildcards) for display. */
    fun suiteNamesListForFeature(featureFile: VirtualFile): List<String> {
        val selector = suiteNamesForFeature(featureFile)
        if (selector == "*") return emptyList()
        return selector.split(" ").map { it.trim('*') }
    }

    /** Collects all `.feature` files reachable from the project's content source roots. */
    fun featureFiles(): List<VirtualFile> {
        val acc = mutableListOf<VirtualFile>()
        ProjectRootManager.getInstance(project).contentSourceRoots.forEach { collectByExt(it, "feature", acc) }
        return acc
    }

    private fun extractFeatureSteps(file: VirtualFile): List<Pair<String, String>> =
        try {
            String(file.contentsToByteArray(), file.charset).lines().mapNotNull { line ->
                val t  = line.trim()
                val kw = listOf("Given", "When", "Then", "And", "But").firstOrNull { t.startsWith("$it ") }
                if (kw != null) kw to t.removePrefix("$kw ").trim() else null
            }
        } catch (_: Exception) { emptyList() }

    private fun scheduleRefresh() {
        if (!refreshing.compareAndSet(false, true)) return
        ApplicationManager.getApplication().executeOnPooledThread {
            try { doRefresh() } finally { refreshing.set(false) }
        }
    }

    private fun doRefresh() {
        // 1. Static scan: collects file + line info for goto-definition and hover.
        val fresh = mutableMapOf<String, List<KtStepDefinition>>()
        try {
            scalaFiles().forEach { vf ->
                try {
                    val content = String(vf.contentsToByteArray(), vf.charset)
                    val defs    = KtStepExtractor.extractFromSource(content, vf.path)
                    if (defs.isNotEmpty()) fresh[vf.path] = defs
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}

        // 2. BSP class-loading: upgrade step patterns with runtime-accurate regexes.
        //    Works once the LSP server has extracted zio-bdd-lsp.jar. If unavailable,
        //    the static-scan patterns are used as-is.
        val bspSteps = BspStepLoader.loadSteps(project)
        if (!bspSteps.isNullOrEmpty()) {
            val byKey = bspSteps.associate { s ->
                (s.keyword.lowercase() to runtimeLiteralKey(s.displayText)) to s
            }
            fresh.replaceAll { _, defs ->
                defs.map { sd ->
                    val key = sd.keyword.lowercase() to staticLiteralKey(sd.displayText)
                    val rs  = byKey[key]
                    if (rs != null) sd.copy(pattern = rs.pattern) else sd
                }
            }
        }

        // Atomic swap — no reader ever sees a partially-populated map
        snapshot = fresh
        lastScan.set(System.currentTimeMillis())
    }

    // "the cart has {int} items" → "the cart has  items"
    private fun staticLiteralKey(displayText: String): String =
        displayText.replace(Regex("\\{[^}]+\\}"), "")

    // '"the cart has " / <IntExtractor> / " items"' → "the cart has  items"
    private fun runtimeLiteralKey(displayText: String): String =
        displayText.split(" / ")
            .filter { it.startsWith("\"") }
            .joinToString("") { it.removePrefix("\"").removeSuffix("\"") }

    private fun scalaFiles(): List<VirtualFile> {
        val acc = mutableListOf<VirtualFile>()
        ProjectRootManager.getInstance(project).contentSourceRoots.forEach { collectByExt(it, "scala", acc) }
        return acc
    }

    private fun collectByExt(dir: VirtualFile, ext: String, acc: MutableList<VirtualFile>) {
        if (!dir.isValid || !dir.isDirectory) return
        for (child in dir.children) {
            if (child.isDirectory) collectByExt(child, ext, acc)
            else if (child.extension == ext) acc += child
        }
    }
}
