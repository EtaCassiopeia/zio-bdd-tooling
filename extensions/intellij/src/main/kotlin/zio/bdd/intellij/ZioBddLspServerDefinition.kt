package zio.bdd.intellij

import com.intellij.openapi.project.Project
import com.redhat.devtools.lsp4ij.LanguageServerFactory
import com.redhat.devtools.lsp4ij.client.LanguageClientImpl
import com.redhat.devtools.lsp4ij.server.ProcessStreamConnectionProvider
import com.redhat.devtools.lsp4ij.server.StreamConnectionProvider
import org.eclipse.lsp4j.services.LanguageServer

import java.io.File

/**
 * Starts the bundled zio-bdd LSP server (`bin/zio-bdd-lsp.jar`) for `.feature` files.
 *
 * Discovery order:
 *   1. JVM property `zio.bdd.lsp.binary` — absolute path override (dev only)
 *   2. The fat jar embedded at `/bin/zio-bdd-lsp.jar` — extracted to `java.io.tmpdir`
 *      on first connection and launched via the IDE's bundled JRE.
 *
 * The IDE-bundled JRE is preferred because it is guaranteed compatible with the
 * platform we built against and avoids pulling in the user's local Java install.
 */
class ZioBddLspServerDefinition : LanguageServerFactory {

    override fun createConnectionProvider(project: Project): StreamConnectionProvider =
        object : ProcessStreamConnectionProvider() {
            init {
                commands = resolveCommand()
                workingDirectory = project.basePath
            }
        }

    override fun createLanguageClient(project: Project): LanguageClientImpl =
        LanguageClientImpl(project)

    override fun getServerInterface(): Class<out LanguageServer> = LanguageServer::class.java

    private fun resolveCommand(): List<String> {
        System.getProperty("zio.bdd.lsp.binary")?.let { p ->
            if (File(p).exists()) return listOf(p)
        }
        val jar = extractEmbeddedJar()
            ?: error(
                "zio-bdd: bundled LSP jar (/bin/zio-bdd-lsp.jar) not found in plugin classpath. " +
                "This usually means the plugin was packaged before `sbt lsp/assembly` ran. " +
                "Override with -Dzio.bdd.lsp.binary=/path/to/zio-bdd-lsp.jar."
            )
        return listOf(javaExecutable(), "-jar", jar.absolutePath)
    }

    /** Extract the embedded fat jar to a stable path in the IDE's temp dir. */
    private fun extractEmbeddedJar(): File? {
        val stream = ZioBddLspServerDefinition::class.java
            .getResourceAsStream("/bin/zio-bdd-lsp.jar") ?: return null
        val dest = File(System.getProperty("java.io.tmpdir"), "zio-bdd-lsp.jar")
        stream.use { input -> dest.outputStream().use { input.copyTo(it) } }
        return dest
    }

    private fun javaExecutable(): String {
        val javaHome = System.getProperty("java.home") ?: return "java"
        val name     = if (isWindows()) "java.exe" else "java"
        val binary   = File(javaHome, "bin/$name")
        return if (binary.exists()) binary.absolutePath else "java"
    }

    private fun isWindows(): Boolean =
        System.getProperty("os.name", "").lowercase().contains("win")
}
