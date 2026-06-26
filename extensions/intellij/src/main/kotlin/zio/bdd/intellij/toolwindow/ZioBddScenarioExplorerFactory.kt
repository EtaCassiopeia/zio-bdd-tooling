package zio.bdd.intellij.toolwindow

import com.google.gson.Gson
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import zio.bdd.intellij.execution.ZioBddRunConfigurationProducer
import zio.bdd.intellij.lang.ZioBddStepCache
import java.awt.BorderLayout
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants

class ZioBddScenarioExplorerFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel   = ZioBddScenarioExplorerPanel(project)
        val content = toolWindow.contentManager.factory.createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)
    }
}

// ── Data model serialised to JSON and sent to the browser ─────────────────

private data class ScenarioItem(val name: String, val line: Int, val tags: List<String>, val isIgnored: Boolean, val isOutline: Boolean)
private data class FeatureItem(val uri: String, val fsPath: String, val name: String, val tags: List<String>, val scenarios: List<ScenarioItem>)
private data class SuiteGroup(val suiteName: String, val scalaFile: String, val featurePaths: List<String>)
private data class UpdatePayload(val type: String = "update", val features: List<FeatureItem>, val suiteGroups: List<SuiteGroup>)

// ── Panel ─────────────────────────────────────────────────────────────────

class ZioBddScenarioExplorerPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private val browser: JBCefBrowser?
    private val query:   JBCefJSQuery?
    private val gson = Gson()

    init {
        if (!JBCefApp.isSupported()) {
            add(
                JLabel(
                    "<html><center>JCEF is not available.<br>Use a JetBrains Runtime (JBR) to enable the Scenario Explorer.</center></html>",
                    SwingConstants.CENTER,
                ),
            )
            browser = null
            query   = null
        } else {
            val b = JBCefBrowser()
            browser = b
            val q = JBCefJSQuery.create(b as JBCefBrowserBase)
            query = q

            q.addHandler { msgJson ->
                try { handleMessage(msgJson) } catch (_: Exception) {}
                null
            }

            b.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
                override fun onLoadEnd(cb: CefBrowser, frame: CefFrame, status: Int) {
                    if (frame.isMain) loadData()
                }
            }, b.cefBrowser)

            add(b.component, BorderLayout.CENTER)
            loadHtml()
        }
    }

    private fun loadHtml() {
        val template = javaClass.getResource("/sidebar/index.html")?.readText() ?: return
        val html     = template.replace("__BRIDGE__", query!!.inject("msgJson"))
        browser!!.loadHTML(html)
    }

    private fun loadData() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val cache = ZioBddStepCache.getInstance(project)
            cache.ensureWarmed()

            val features    = cache.featureFiles().mapNotNull(::parseFeature)
            val suiteGroups = buildSuiteGroups(cache, features)
            val payload     = gson.toJson(UpdatePayload(features = features, suiteGroups = suiteGroups))

            dispatchJs("window.dispatchEvent(new MessageEvent('message',{data:$payload}));")
        }
    }

    private fun handleMessage(msgJson: String) {
        @Suppress("UNCHECKED_CAST")
        val msg = gson.fromJson(msgJson, Map::class.java) as Map<String, Any>
        when (msg["type"] as? String) {
            "refresh"     -> loadData()
            "runAll"      -> runOnEdt { ZioBddRunConfigurationProducer().runAll(project) }
            "runSuite"    -> runOnEdt {
                val name = msg["suiteName"] as? String ?: return@runOnEdt
                ZioBddRunConfigurationProducer().runSuite(project, "*$name*", name)
            }
            "runFeature"  -> runOnEdt {
                val path  = (msg["fsPath"] as? String) ?: (msg["featureUri"] as? String) ?: return@runOnEdt
                val suite = msg["suiteName"] as? String
                val vf    = LocalFileSystem.getInstance().findFileByPath(path) ?: return@runOnEdt
                ZioBddRunConfigurationProducer().runFeature(project, vf, vf.nameWithoutExtension, suite ?: "*")
            }
            "runScenario" -> runOnEdt {
                val name  = msg["scenarioName"] as? String ?: return@runOnEdt
                val path  = (msg["featureUri"] as? String) ?: return@runOnEdt
                val suite = msg["suiteName"] as? String
                val vf    = LocalFileSystem.getInstance().findFileByPath(path) ?: return@runOnEdt
                ZioBddRunConfigurationProducer().runScenario(project, name, vf, suite ?: "*")
            }
            "openFile"    -> runOnEdt {
                val path = msg["uri"] as? String ?: return@runOnEdt
                val line = (msg["line"] as? Double)?.toInt() ?: 0
                val vf   = LocalFileSystem.getInstance().findFileByPath(path) ?: return@runOnEdt
                OpenFileDescriptor(project, vf, (line - 1).coerceAtLeast(0), 0).navigate(true)
            }
        }
    }

    private fun buildSuiteGroups(cache: ZioBddStepCache, features: List<FeatureItem>): List<SuiteGroup> {
        val byName = mutableMapOf<String, MutableList<String>>()
        features.forEach { fi ->
            val vf = LocalFileSystem.getInstance().findFileByPath(fi.fsPath) ?: return@forEach
            cache.suiteNamesListForFeature(vf).forEach { suiteName ->
                byName.getOrPut(suiteName) { mutableListOf() } += fi.fsPath
            }
        }
        return byName.entries.sortedBy { it.key }.map { (name, paths) ->
            SuiteGroup(suiteName = name, scalaFile = "", featurePaths = paths)
        }
    }

    private fun parseFeature(vf: VirtualFile): FeatureItem? {
        val lines = try {
            String(vf.contentsToByteArray(), vf.charset).lines()
        } catch (_: Exception) { return null }

        var featureName = ""
        val featureTags = mutableListOf<String>()
        var pending     = mutableListOf<String>()
        val scenarios   = mutableListOf<ScenarioItem>()

        for ((idx, raw) in lines.withIndex()) {
            val line = raw.trim()
            when {
                line.startsWith("@") -> {
                    line.split("\\s+".toRegex()).filter { it.startsWith("@") }
                        .forEach { pending += it.drop(1) }
                }
                line.startsWith("Feature:") -> {
                    featureName = line.removePrefix("Feature:").trim()
                    featureTags += pending; pending = mutableListOf()
                }
                line.startsWith("Scenario Outline:") || line.startsWith("Scenario Template:") -> {
                    val tags = pending.toList(); pending = mutableListOf()
                    val name = line.removePrefix("Scenario Template:").removePrefix("Scenario Outline:").trim()
                    scenarios += ScenarioItem(name, idx + 1, tags, tags.any { it.equals("ignore", true) }, isOutline = true)
                }
                line.startsWith("Scenario:") || line.startsWith("Example:") -> {
                    val tags = pending.toList(); pending = mutableListOf()
                    val name = line.removePrefix("Scenario:").removePrefix("Example:").trim()
                    scenarios += ScenarioItem(name, idx + 1, tags, tags.any { it.equals("ignore", true) }, isOutline = false)
                }
                line.isNotBlank() && !line.startsWith("#") && !line.startsWith("|") && featureName.isEmpty() -> {
                    pending = mutableListOf()
                }
            }
        }
        if (featureName.isEmpty()) return null
        return FeatureItem(uri = vf.path, fsPath = vf.path, name = featureName, tags = featureTags, scenarios = scenarios)
    }

    private fun dispatchJs(js: String) {
        val b = browser ?: return
        b.cefBrowser.executeJavaScript(js, b.cefBrowser.url ?: "about:blank", 0)
    }

    private fun runOnEdt(block: () -> Unit) = ApplicationManager.getApplication().invokeLater(block)

    override fun dispose() { browser?.dispose() }
}
