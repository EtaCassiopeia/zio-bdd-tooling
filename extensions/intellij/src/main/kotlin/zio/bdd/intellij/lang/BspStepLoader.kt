package zio.bdd.intellij.lang

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
 * The LSP fat jar (`zio-bdd-lsp.jar`) is extracted to the system temp directory
 * by `ZioBddLspServerDefinition` when the plugin starts.  Once issue #2 is
 * merged, that jar contains `StepLoader`, so the same jar serves double duty as
 * the loader.
 *
 * Returns `null` if the loader jar is not yet available (LSP server hasn't
 * started) or if the subprocess fails for any reason; the caller falls back to
 * static source scanning.
 */
object BspStepLoader {

    private const val LOADER_MAIN = "zio.bdd.lsp.bsp.StepLoader"
    private const val TIMEOUT_SEC = 30L

    fun loadSteps(project: Project): List<KtStepDefinition>? {
        val loaderJar = findLoaderJar() ?: return null
        val testCp    = getTestClasspath(project)
        if (testCp.isEmpty()) return null
        return runSubprocess(testCp, loaderJar)
    }

    private fun findLoaderJar(): String? {
        val jar = File(System.getProperty("java.io.tmpdir"), "zio-bdd-lsp.jar")
        return if (jar.exists()) jar.absolutePath else null
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

    private fun runSubprocess(classpath: List<String>, loaderJar: String): List<KtStepDefinition>? {
        val cp = (classpath + loaderJar).joinToString(File.pathSeparator)
        return try {
            val proc = ProcessBuilder(javaExecutable(), "-cp", cp, LOADER_MAIN)
                .redirectErrorStream(false)
                .start()
            val output   = proc.inputStream.bufferedReader(Charsets.UTF_8).readText()
            val finished = proc.waitFor(TIMEOUT_SEC, TimeUnit.SECONDS)
            if (!finished) { proc.destroyForcibly(); return null }
            if (proc.exitValue() != 0) return null
            parseJson(output.trim())
        } catch (_: Exception) {
            null
        }
    }

    // Parses the JSON array emitted by StepLoader:
    //   [{"keyword":"...","pattern":"...","displayText":"..."},...]
    // These definitions have no file/line (they come from the runtime, not the
    // source scanner); callers merge the accurate pattern into static-scan entries.
    private fun parseJson(json: String): List<KtStepDefinition> {
        if (json.isEmpty() || json == "[]") return emptyList()
        val objPat = Regex("""\{[^}]+\}""")
        val strPat = Regex(""""([^"\\]*(\\.[^"\\]*)*)"""")
        return objPat.findAll(json).mapNotNull { objMatch ->
            val fields = strPat.findAll(objMatch.value).map { it.groupValues[1] }.toList()
            if (fields.size >= 6)
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
        }.toList()
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
