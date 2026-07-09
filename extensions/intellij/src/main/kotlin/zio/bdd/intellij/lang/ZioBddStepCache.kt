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

    // The discovered @mock(name) catalog — a workspace-global fact from the BSP
    // subprocess (there is no static-scan fallback: the catalog is a Scala map,
    // not a source pattern). Empty until the first BSP class-load completes.
    @Volatile private var mockSnapshot: List<KtMockSummary> = emptyList()

    // @Suite(featureDirs=...) declarations from the source scan. Used to resolve the
    // single owning suite for a feature file (so the run targets it, not "*").
    @Volatile private var suiteIndex: List<KtSuiteDecl> = emptyList()

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

    /** The discovered `@mock(name)` catalog entries (empty until a BSP class-load runs). */
    fun getMockCatalog(): List<KtMockSummary> {
        if (System.currentTimeMillis() - lastScan.get() > TTL_MS) scheduleRefresh()
        return mockSnapshot
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

    /**
     * Fast, blocking, static-only warm-up for latency-sensitive callers like code
     * completion. Populates step display texts from the source scan (no BSP
     * subprocess — that can take seconds), so the very first completion has data,
     * then schedules the full refresh to upgrade patterns in the background.
     */
    fun ensureStaticWarmed() {
        if (snapshot.isNotEmpty() && suiteIndex.isNotEmpty()) return
        val (fresh, suites) = staticScan()
        if (fresh.isNotEmpty()) snapshot = fresh
        if (suites.isNotEmpty()) suiteIndex = suites
        if (System.currentTimeMillis() - lastScan.get() > TTL_MS) scheduleRefresh()
    }

    fun invalidate() {
        snapshot     = emptyMap()
        mockSnapshot = emptyList()
        suiteIndex   = emptyList()
        lastScan.set(0L)
    }

    /** Returns the sbt test selector (e.g. `"*CalculatorSuite*"`) that owns
     *  [featureFile]. Prefers the suite whose `@Suite(featureDirs=...)` contains the
     *  feature — a single, correct target — and falls back to step-definition
     *  matching, then `"*"` only as a last resort. Warms the source scan synchronously
     *  first so a cold cache never causes the run to fan out across every suite (#41). */
    fun suiteNamesForFeature(featureFile: VirtualFile): String {
        ensureStaticWarmed()
        if (System.currentTimeMillis() - lastScan.get() > TTL_MS) scheduleRefresh()
        // Authoritative: the suite that declares this feature's directory owns it.
        KtSuiteExtractor.ownerFor(featureFile.path, project.basePath, suiteIndex)?.let { return "*$it*" }
        // Fallback for projects without discoverable @Suite annotations.
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

    // Source-only scan: collects step definitions (file + line for goto/hover/
    // completion) and @Suite declarations in a single pass. Fast (file read +
    // regex) — no subprocess.
    private fun staticScan(): Pair<MutableMap<String, List<KtStepDefinition>>, List<KtSuiteDecl>> {
        val fresh  = mutableMapOf<String, List<KtStepDefinition>>()
        val suites = mutableListOf<KtSuiteDecl>()
        try {
            scalaFiles().forEach { vf ->
                try {
                    val content = String(vf.contentsToByteArray(), vf.charset)
                    val defs    = KtStepExtractor.extractFromSource(content, vf.path)
                    if (defs.isNotEmpty()) fresh[vf.path] = defs
                    suites += KtSuiteExtractor.extractFromSource(content)
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
        return fresh to suites
    }

    private fun doRefresh() {
        // 1. Static scan: step definitions (goto/hover) and @Suite ownership.
        val (fresh, suites) = staticScan()
        if (suites.isNotEmpty() || suiteIndex.isEmpty()) suiteIndex = suites

        // 2. BSP class-loading: upgrade step patterns with runtime-accurate regexes
        //    and discover the @mock catalog. Works once the LSP server has extracted
        //    zio-bdd-lsp.jar. If unavailable, the static-scan patterns are used as-is.
        val bsp = BspStepLoader.loadAll(project)
        if (bsp != null && bsp.steps.isNotEmpty()) {
            val byKey = bsp.steps.associate { s ->
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
        // Replace the catalog only when this load actually found entries — like the
        // step snapshot above, a failed or transiently-empty load must not wipe a
        // good catalog (which would silently stop unknown-name annotations).
        if (bsp != null && bsp.mocks.isNotEmpty()) mockSnapshot = bsp.mocks

        // Atomic swap — no reader ever sees a partially-populated map. Don't clobber
        // a populated cache with a transient empty scan (e.g. a background refresh
        // that ran without read access); only replace when the scan found something
        // or the cache was empty anyway.
        if (fresh.isNotEmpty() || snapshot.isEmpty()) snapshot = fresh
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
