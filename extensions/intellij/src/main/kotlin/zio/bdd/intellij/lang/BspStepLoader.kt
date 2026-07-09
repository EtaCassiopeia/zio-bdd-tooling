package zio.bdd.intellij.lang

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.OrderEnumerator
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
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
        // Guard the classpath computation: ModuleManager/OrderEnumerator can throw
        // (e.g. accessed without a read action, or a transiently-invalid module), and
        // an escaping throw here used to abort the caller's whole refresh (#42).
        // ProcessCanceledException must propagate — swallowing it breaks platform
        // cancellation — so rethrow it ahead of the fallback.
        val testCp = try {
            getTestClasspath(project)
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: Exception) {
            LOG.warn("BspStepLoader: could not compute the test classpath; skipping runtime load", e)
            return null
        }
        if (testCp.isEmpty()) return null
        return runSubprocess(testCp, loaderJar)
    }

    private fun findLoaderJar(): String? {
        val resource = BspStepLoader::class.java.getResource("/bin/zio-bdd-lsp.jar") ?: return null
        val jar = File(System.getProperty("java.io.tmpdir"), "zio-bdd-lsp.jar")
        val bundledSize = try {
            resource.openConnection().contentLengthLong
        } catch (e: Exception) {
            // Can't size the bundled resource — reuse whatever is extracted rather than
            // re-copying a multi-MB jar every call, but leave a trace: this is the one
            // path that could keep a stale jar after an update.
            LOG.warn("BspStepLoader: could not read the bundled loader jar size; an existing copy will be reused", e)
            -1L
        }
        // Reuse the extracted jar only when it matches the bundled resource. The old
        // code returned any existing tmpdir jar forever, so a plugin update (which
        // ships a new LSP jar) kept running the stale one — degrading step/mock
        // intelligence with no signal (#43).
        if (shouldReuseExtractedJar(jar.exists(), jar.length(), bundledSize)) return jar.absolutePath
        // Extract to a temp file, then move into place, so a concurrent or
        // interrupted extraction can never leave a torn jar at the target path.
        val tmp = File.createTempFile("zio-bdd-lsp", ".jar", jar.parentFile)
        return try {
            resource.openStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
            Files.move(tmp.toPath(), jar.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            jar.absolutePath.takeIf { jar.exists() }
        } catch (e: Exception) {
            LOG.warn("BspStepLoader: failed to extract the bundled loader jar", e)
            null
        } finally {
            // No-op after a successful move; cleans up the multi-MB temp file if the
            // copy/move failed, so a recurring failure can't accumulate junk in tmp.
            Files.deleteIfExists(tmp.toPath())
        }
    }

    /** Whether the already-extracted loader jar may be reused: it must exist and
     *  match the bundled resource's size (or the bundled size be indeterminate, to
     *  avoid re-extracting the multi-MB jar on every call). A size mismatch means a
     *  plugin update shipped a new jar and the stale copy must be replaced (#43). */
    internal fun shouldReuseExtractedJar(jarExists: Boolean, jarSize: Long, bundledSize: Long): Boolean =
        jarExists && (bundledSize < 0L || jarSize == bundledSize)

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
            // Drain both stdout and stderr on their own threads: StepLoader logs a
            // line per unloadable suite, and reading only stdout while stderr fills
            // the OS pipe buffer deadlocks both sides (#40). Draining stdout on a
            // thread too means waitFor (not a blocking read) bounds the call, so a
            // child that stalls either stream still hits the timeout.
            val out = StringBuilder()
            val errTail = StringBuilder()
            val outThread = drainer(proc.inputStream, out, bounded = false)
            val errThread = drainer(proc.errorStream, errTail, bounded = true)
            outThread.start()
            errThread.start()
            val finished = proc.waitFor(TIMEOUT_SEC, TimeUnit.SECONDS)
            // On timeout, kill the child first so both streams reach EOF and the drain
            // threads terminate before we read their buffers.
            if (!finished) proc.destroyForcibly()
            outThread.join(2000)
            errThread.join(1000)
            val tail = if (errTail.isBlank()) "" else "; stderr: ${errTail.trim()}"
            if (!finished) {
                LOG.warn("BspStepLoader: StepLoader subprocess timed out after ${TIMEOUT_SEC}s; falling back to static scan$tail")
                return null
            }
            if (proc.exitValue() != 0) {
                LOG.warn("BspStepLoader: StepLoader subprocess exited ${proc.exitValue()}; falling back to static scan$tail")
                return null
            }
            val json = out.toString().trim()
            val dropped = unrecognizedObjectCount(json)
            if (dropped > 0) {
                LOG.warn("BspStepLoader: dropped $dropped unrecognized JSON object(s) from the StepLoader output")
            }
            LoadResult(parseSteps(json), parseMocks(json))
        } catch (e: Exception) {
            // Fall back to static-scan step definitions (and an unchanged mock catalog),
            // but leave a trace — otherwise "why is @mock intelligence empty?" is undebuggable.
            LOG.warn("BspStepLoader: StepLoader subprocess failed; falling back to static scan", e)
            null
        }
    }

    // Fully consume a stream on a daemon thread so the child never blocks writing
    // to it. `bounded` caps retained text for the diagnostic stderr tail; stdout is
    // read whole. A read failure is recorded in the buffer, not swallowed, so it
    // surfaces in the eventual warning instead of silently killing the drain.
    private fun drainer(stream: java.io.InputStream, buf: StringBuilder, bounded: Boolean): Thread =
        Thread {
            try {
                stream.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                    if (!bounded || buf.length < 8192) buf.append(line).append('\n')
                }
            } catch (e: java.io.IOException) {
                buf.append("[stream drain aborted: ${e.message}]")
            }
        }.apply { isDaemon = true }

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

    /** How many scanned JSON objects were recognized as neither a step nor a mock —
     *  a silently-dropped entry (e.g. a truncated object). Envelope-marker objects
     *  (first key `steps`/`mocks`, matched when both arrays are empty) are excluded.
     *  The caller logs when this is non-zero so a silent drop is diagnosable (#47). */
    fun unrecognizedObjectCount(json: String): Int {
        if (json.isEmpty()) return 0
        val objects = objectFields(json).filterNot { it.firstOrNull() == "steps" || it.firstOrNull() == "mocks" }
        return maxOf(0, objects.size - parseSteps(json).size - parseMocks(json).size)
    }

    // Single-pass unescaper. A chain of String.replace calls cannot decode this
    // correctly: a real backslash is escaped to `\\` while a brace is escaped to a
    // single-backslash `{` (#40), so `\\u007b` (an escaped backslash then the
    // text u007b) is indistinguishable from the brace token under substring
    // replacement. Scanning once, consuming each escape whole, removes the
    // ambiguity and handles any `\uXXXX` uniformly.
    private fun unescape(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    '"' -> { sb.append('"'); i += 2 }
                    'n' -> { sb.append('\n'); i += 2 }
                    'r' -> { sb.append('\r'); i += 2 }
                    't' -> { sb.append('\t'); i += 2 }
                    '\\' -> { sb.append('\\'); i += 2 }
                    'u' -> {
                        val hex = if (i + 6 <= s.length) s.substring(i + 2, i + 6).toIntOrNull(16) else null
                        if (hex != null) { sb.append(hex.toChar()); i += 6 } else { sb.append(c); i += 1 }
                    }
                    else -> { sb.append(c); i += 1 }
                }
            } else {
                sb.append(c); i += 1
            }
        }
        return sb.toString()
    }

    private fun javaExecutable(): String {
        val home = System.getProperty("java.home") ?: return "java"
        val name = if (System.getProperty("os.name", "").lowercase().contains("win")) "java.exe" else "java"
        val bin  = File(home, "bin/$name")
        return if (bin.exists()) bin.absolutePath else "java"
    }
}
