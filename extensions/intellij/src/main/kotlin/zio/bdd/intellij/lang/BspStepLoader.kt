package zio.bdd.intellij.lang

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.OrderEnumerator
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Launches the bundled `zio.bdd.lsp.bsp.StepLoader` subprocess on the project's
 * JVM test classpath, collects the JSON output, and returns runtime-accurate step
 * definitions.
 *
 * The `zio-bdd-lsp.jar` ships inside the plugin as a resource at `/bin/zio-bdd-lsp.jar`.
 * `findLoaderJar()` extracts it to the system temp directory on first use so that
 * the subprocess can be launched via `java -cp`.
 *
 * Returns `null` if the subprocess fails for any reason; the caller falls back to
 * static source scanning.
 */
object BspStepLoader {

    private const val LOADER_MAIN = "zio.bdd.lsp.bsp.StepLoader"
    private const val TIMEOUT_SEC = 30L
    private val LOG = Logger.getInstance(BspStepLoader::class.java)

    /** The runtime step definitions and @mock catalog from one StepLoader run. */
    data class LoadResult(val steps: List<KtStepDefinition>, val mocks: List<KtMockSummary>)

    fun loadAll(project: Project): LoadResult? {
        val loaderJar = findLoaderJar() ?: return null
        val testCp    = getTestClasspath(project)
        if (testCp.isEmpty()) return null
        return runSubprocess(testCp, loaderJar)
    }

    private fun findLoaderJar(): String? {
        val jar = File(System.getProperty("java.io.tmpdir"), "zio-bdd-lsp.jar")
        if (jar.exists()) return jar.absolutePath
        // Extract from plugin resources on first use (no longer relies on
        // ZioBddLspServerDefinition being present to pre-populate tmpdir).
        val stream = BspStepLoader::class.java.getResourceAsStream("/bin/zio-bdd-lsp.jar")
            ?: return null
        return try {
            stream.use { it.copyTo(jar.outputStream()) }
            jar.absolutePath.takeIf { jar.exists() }
        } catch (_: Exception) { null }
    }

    private fun getTestClasspath(project: Project): List<String> =
        ModuleManager.getInstance(project).modules.flatMap<_, String> { module ->
            OrderEnumerator.orderEntries(module)
                .recursively()
                .withoutSdk()
                .classes()
                .roots
                .map { vf -> vf.path }
        }.distinct()

    private fun runSubprocess(classpath: List<String>, loaderJar: String): LoadResult? {
        val cp = (classpath + loaderJar).joinToString(File.pathSeparator)
        return try {
            val proc = ProcessBuilder(javaExecutable(), "-cp", cp, LOADER_MAIN)
                .redirectErrorStream(false)
                .start()
            val output   = proc.inputStream.bufferedReader(Charsets.UTF_8).readText()
            val finished = proc.waitFor(TIMEOUT_SEC, TimeUnit.SECONDS)
            if (!finished) { proc.destroyForcibly(); return null }
            if (proc.exitValue() != 0) return null
            val json = output.trim()
            LoadResult(parseSteps(json), parseMocks(json))
        } catch (e: Exception) {
            // Fall back to static-scan step definitions (and an unchanged mock catalog),
            // but leave a trace — otherwise "why is @mock intelligence empty?" is undebuggable.
            LOG.warn("BspStepLoader: StepLoader subprocess failed; falling back to static scan", e)
            null
        }
    }

    // StepLoader emits `{"steps":[…],"mocks":[…]}` with brace-free inner objects.
    // `\{[^{}]+\}` matches only the innermost objects (the envelope's outer brace
    // is skipped); we then dispatch by first key. Definitions have no file/line
    // (they come from the runtime, not the source scanner); callers merge the
    // accurate pattern into static-scan entries.
    private val objPat = Regex("""\{[^{}]+\}""")
    private val strPat = Regex(""""([^"\\]*(\\.[^"\\]*)*)"""")

    private fun objectFields(json: String): List<List<String>> =
        objPat.findAll(json).map { m -> strPat.findAll(m.value).map { it.groupValues[1] }.toList() }.toList()

    fun parseSteps(json: String): List<KtStepDefinition> {
        if (json.isEmpty()) return emptyList()
        return objectFields(json).mapNotNull { fields ->
            if (fields.size >= 6 && fields[0] == "keyword")
                KtStepDefinition(
                    keyword      = unescape(fields[1]),
                    displayText  = unescape(fields[5]),
                    pattern      = unescape(fields[3]),
                    literals     = emptyList(),
                    extractorCount = 0,
                    file         = "",
                    line         = -1,
                )
            else null
        }
    }

    fun parseMocks(json: String): List<KtMockSummary> {
        if (json.isEmpty()) return emptyList()
        return objectFields(json).mapNotNull { fields ->
            if (fields.size >= 4 && fields[0] == "name")
                KtMockSummary(name = unescape(fields[1]), sourceKind = unescape(fields[3]))
            else null
        }
    }

    private fun unescape(s: String): String =
        s.replace("\\\"", "\"")
            .replace("\\n", "\n")
            .replace("\\r", "\r")
            .replace("\\t", "\t")
            .replace("\\\\", "\\")

    private fun javaExecutable(): String {
        val home = System.getProperty("java.home") ?: return "java"
        val name = if (System.getProperty("os.name", "").lowercase().contains("win")) "java.exe" else "java"
        val bin  = File(home, "bin/$name")
        return if (bin.exists()) bin.absolutePath else "java"
    }
}
