package zio.bdd.intellij.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import zio.bdd.intellij.execution.ZioBddRunConfigurationProducer
import zio.bdd.intellij.lang.ZioBddStepCache
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.event.DocumentEvent
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeCellRenderer
import javax.swing.tree.DefaultTreeModel

// ── Data model ────────────────────────────────────────────────────────────────

data class SuiteNode(val name: String, val selector: String)
data class FeatureNode(val name: String, val file: VirtualFile, val suiteSelector: String, val tags: List<String> = emptyList())
data class ScenarioNode(
    val name: String,
    val line: Int,
    val file: VirtualFile,
    val suiteSelector: String,
    val tags: List<String> = emptyList(),
    val isIgnored: Boolean = false,
    val isOutline: Boolean = false,
)

private data class FeatureInfo(
    val name: String,
    val file: VirtualFile,
    val tags: List<String>,
    val scenarios: List<ScenarioInfo>,
)

private data class ScenarioInfo(
    val name: String,
    val line: Int,
    val tags: List<String>,
    val isIgnored: Boolean,
    val isOutline: Boolean,
)

// ── Factory ──────────────────────────────────────────────────────────────────

class ZioBddScenarioExplorerFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel   = ZioBddScenarioExplorerPanel(project)
        val content = toolWindow.contentManager.factory.createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)
    }
}

// ── Panel ─────────────────────────────────────────────────────────────────────

class ZioBddScenarioExplorerPanel(private val project: Project) : SimpleToolWindowPanel(true, true) {

    private val root        = DefaultMutableTreeNode("root")
    private val model       = DefaultTreeModel(root)
    private val tree        = Tree(model)
    private val searchField = SearchTextField(false)
    private val statsLabel  = JBLabel("")

    // Cached full set; rebuilt on refresh, filtered on every keystroke
    @Volatile private var allGroups: List<Pair<SuiteNode, List<FeatureInfo>>> = emptyList()
    @Volatile private var viewBySuite = true

    init {
        tree.isRootVisible    = false
        tree.showsRootHandles = true
        tree.cellRenderer     = ExplorerCellRenderer()

        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent)  { if (e.clickCount >= 2) onDoubleClick(e) }
            override fun mousePressed(e: MouseEvent)  { if (e.isPopupTrigger) showPopup(e) }
            override fun mouseReleased(e: MouseEvent) { if (e.isPopupTrigger) showPopup(e) }
        })

        searchField.textEditor.emptyText.text = "Filter by name, tag…"
        searchField.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) { applyFilter() }
        })

        statsLabel.foreground = JBColor.GRAY
        statsLabel.font       = statsLabel.font.deriveFont(Font.PLAIN, 10f)

        // ── Toolbar ───────────────────────────────────────────────────────────
        val group = DefaultActionGroup()

        group.add(object : AnAction("Refresh", "Reload suite groupings", AllIcons.Actions.Refresh) {
            override fun actionPerformed(e: AnActionEvent) { triggerRefresh() }
        })

        group.add(object : AnAction("Toggle View", "Switch between suite-grouped and flat view", AllIcons.Actions.GroupByModule) {
            override fun actionPerformed(e: AnActionEvent) {
                viewBySuite = !viewBySuite
                applyFilter()
            }
        })

        group.add(object : AnAction("Run All Tests", "Run sbt test", AllIcons.Actions.Execute) {
            override fun actionPerformed(e: AnActionEvent) {
                ZioBddRunConfigurationProducer().runAll(project)
            }
        })

        val toolbar = ActionManager.getInstance().createActionToolbar("ZioBddExplorer", group, true)
        toolbar.targetComponent = tree

        val topBar = JPanel(BorderLayout()).apply {
            add(toolbar.component, BorderLayout.WEST)
            add(searchField,       BorderLayout.CENTER)
        }
        val bottomBar = JPanel(BorderLayout()).apply {
            add(statsLabel, BorderLayout.WEST)
        }

        setToolbar(topBar)
        val content = JPanel(BorderLayout()).apply {
            add(JBScrollPane(tree), BorderLayout.CENTER)
            add(bottomBar,          BorderLayout.SOUTH)
        }
        setContent(content)

        triggerRefresh()
    }

    fun triggerRefresh() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val groups = buildGroups()
            ApplicationManager.getApplication().invokeLater {
                allGroups = groups
                applyFilter()
            }
        }
    }

    private fun applyFilter() {
        val text = searchField.text.trim().lowercase()
        root.removeAllChildren()

        var totalFeatures  = 0
        var totalScenarios = 0

        fun addFeatureNode(fi: FeatureInfo, suiteSelector: String): DefaultMutableTreeNode? {
            val tagHit      = fi.tags.any { it.lowercase().contains(text) }
            val nameHit     = fi.name.lowercase().contains(text)
            val matchedScen = fi.scenarios.filter { s ->
                s.name.lowercase().contains(text) || s.tags.any { it.lowercase().contains(text) }
            }
            val effectiveScenarios = when {
                text.isEmpty() -> fi.scenarios
                nameHit || tagHit -> fi.scenarios
                matchedScen.isNotEmpty() -> matchedScen
                else -> return null
            }
            totalFeatures++
            val fn = DefaultMutableTreeNode(FeatureNode(fi.name, fi.file, suiteSelector, fi.tags))
            effectiveScenarios.forEach { s ->
                totalScenarios++
                fn.add(DefaultMutableTreeNode(ScenarioNode(s.name, s.line, fi.file, suiteSelector, s.tags, s.isIgnored, s.isOutline)))
            }
            return fn
        }

        if (viewBySuite) {
            allGroups.forEach { (suiteNode, features) ->
                val stn = DefaultMutableTreeNode(suiteNode)
                features.forEach { fi ->
                    addFeatureNode(fi, suiteNode.selector)?.let { stn.add(it) }
                }
                if (stn.childCount > 0) root.add(stn)
            }
        } else {
            // Flat view: all features sorted by name, deduped
            val seen = mutableSetOf<String>()
            allGroups.flatMap { (_, fis) -> fis }.sortedBy { it.name }.forEach { fi ->
                if (seen.add(fi.file.path)) {
                    addFeatureNode(fi, "*")?.let { root.add(it) }
                }
            }
        }

        model.reload()
        for (i in 0 until tree.rowCount) tree.expandRow(i)
        statsLabel.text = "  $totalFeatures feature${if (totalFeatures != 1) "s" else ""} · $totalScenarios scenario${if (totalScenarios != 1) "s" else ""}"
    }

    private fun buildGroups(): List<Pair<SuiteNode, List<FeatureInfo>>> {
        val cache    = ZioBddStepCache.getInstance(project)
        val features = cache.featureFiles().mapNotNull(::parseFeature)

        val grouped   = mutableMapOf<String, MutableList<FeatureInfo>>()
        val ungrouped = mutableListOf<FeatureInfo>()

        features.forEach { fi ->
            val suites = cache.suiteNamesListForFeature(fi.file)
            if (suites.isEmpty()) ungrouped += fi
            else suites.forEach { s -> grouped.getOrPut(s) { mutableListOf() } += fi }
        }

        val result = mutableListOf<Pair<SuiteNode, List<FeatureInfo>>>()
        grouped.entries.sortedBy { it.key }.forEach { (name, fis) ->
            result += SuiteNode(name, "*$name*") to fis.sortedBy { it.name }
        }
        if (ungrouped.isNotEmpty()) {
            result += SuiteNode("(Unassigned)", "*") to ungrouped.sortedBy { it.name }
        }
        return result
    }

    private fun parseFeature(file: VirtualFile): FeatureInfo? {
        val lines = try {
            String(file.contentsToByteArray(), file.charset).lines()
        } catch (_: Exception) { return null }

        // Collect pending tags (lines starting with @)
        var pendingTags = mutableListOf<String>()
        var featureName: String? = null
        val featureTags = mutableListOf<String>()
        val scenarios   = mutableListOf<ScenarioInfo>()

        for (line in lines) {
            val t = line.trim()
            when {
                t.startsWith("@") -> {
                    t.split("\\s+".toRegex()).filter { it.startsWith("@") }.forEach { pendingTags += it.drop(1) }
                }
                t.startsWith("Feature:") -> {
                    featureName = t.removePrefix("Feature:").trim()
                    featureTags += pendingTags
                    pendingTags = mutableListOf()
                }
                t.startsWith("Scenario Outline:") || t.startsWith("Scenario Template:") -> {
                    val scTags   = pendingTags.toList()
                    val isIgnore = scTags.any { it.equals("ignore", ignoreCase = true) }
                    val rawName  = t.removePrefix("Scenario Template:").removePrefix("Scenario Outline:").trim()
                    scenarios += ScenarioInfo(rawName, lines.indexOf(line) + 1, scTags, isIgnore, isOutline = true)
                    pendingTags = mutableListOf()
                }
                t.startsWith("Scenario:") -> {
                    val scTags   = pendingTags.toList()
                    val isIgnore = scTags.any { it.equals("ignore", ignoreCase = true) }
                    val rawName  = t.removePrefix("Scenario:").trim()
                    scenarios += ScenarioInfo(rawName, lines.indexOf(line) + 1, scTags, isIgnore, isOutline = false)
                    pendingTags = mutableListOf()
                }
                t.isNotBlank() && !t.startsWith("#") && !t.startsWith("Background:") -> {
                    // Non-tag, non-keyword line resets pending tags only if we haven't seen Feature yet
                    if (featureName == null) pendingTags = mutableListOf()
                }
            }
        }

        return FeatureInfo(featureName ?: file.nameWithoutExtension, file, featureTags, scenarios)
    }

    private fun onDoubleClick(e: MouseEvent) {
        val node = selectedNode(e) ?: return
        when (val data = node.userObject) {
            is ScenarioNode -> OpenFileDescriptor(project, data.file, data.line - 1, 0).navigate(true)
            is FeatureNode  -> OpenFileDescriptor(project, data.file, 0, 0).navigate(true)
            else            -> {}
        }
    }

    private fun showPopup(e: MouseEvent) {
        val node     = selectedNode(e) ?: return
        val menu     = JPopupMenu()
        val producer = ZioBddRunConfigurationProducer()
        when (val data = node.userObject) {
            is SuiteNode -> {
                menu.add(JMenuItem("Run Suite: ${data.name}").apply {
                    addActionListener { producer.runSuite(project, data.selector, data.name) }
                })
            }
            is FeatureNode -> {
                menu.add(JMenuItem("Run Feature: ${data.name}").apply {
                    addActionListener { producer.runFeature(project, data.file, data.name, data.suiteSelector) }
                })
                menu.add(JMenuItem("Open in Editor").apply {
                    addActionListener { OpenFileDescriptor(project, data.file, 0, 0).navigate(true) }
                })
            }
            is ScenarioNode -> {
                menu.add(JMenuItem("Run Scenario: ${data.name}").apply {
                    addActionListener { producer.runScenario(project, data.name, data.file, data.suiteSelector) }
                })
                menu.add(JMenuItem("Go to Scenario").apply {
                    addActionListener { OpenFileDescriptor(project, data.file, data.line - 1, 0).navigate(true) }
                })
            }
        }
        if (menu.componentCount > 0) menu.show(tree, e.x, e.y)
    }

    private fun selectedNode(e: MouseEvent): DefaultMutableTreeNode? {
        val path = tree.getPathForLocation(e.x, e.y) ?: return null
        tree.selectionPath = path
        return path.lastPathComponent as? DefaultMutableTreeNode
    }
}

// ── Cell renderer ─────────────────────────────────────────────────────────────

private class ExplorerCellRenderer : DefaultTreeCellRenderer() {
    override fun getTreeCellRendererComponent(
        tree: javax.swing.JTree,
        value: Any?,
        selected: Boolean,
        expanded: Boolean,
        leaf: Boolean,
        row: Int,
        hasFocus: Boolean,
    ): Component {
        super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus)
        val node = value as? DefaultMutableTreeNode ?: return this
        when (val data = node.userObject) {
            is SuiteNode -> {
                icon = AllIcons.Nodes.ModuleGroup
                text = data.name
            }
            is FeatureNode -> {
                icon = AllIcons.FileTypes.Text
                text = buildString {
                    append(data.name)
                    if (data.tags.isNotEmpty()) append("  [${data.tags.joinToString(", ") { "@$it" }}]")
                }
            }
            is ScenarioNode -> {
                icon = if (data.isIgnored) AllIcons.Actions.Suspend else AllIcons.Actions.Execute
                text = buildString {
                    append(data.name)
                    if (data.isOutline) append(" (outline)")
                    if (data.isIgnored) append(" [ignored]")
                    if (data.tags.isNotEmpty()) append("  ${data.tags.joinToString(" ") { "@$it" }}")
                }
                foreground = if (data.isIgnored) JBColor.GRAY else foreground
            }
        }
        return this
    }
}
