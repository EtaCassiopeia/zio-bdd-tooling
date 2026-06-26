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
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import zio.bdd.intellij.execution.ZioBddRunConfigurationProducer
import zio.bdd.intellij.lang.ZioBddStepCache
import java.awt.BorderLayout
import java.awt.Component
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.event.DocumentEvent
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeCellRenderer
import javax.swing.tree.DefaultTreeModel

// ── Node types ───────────────────────────────────────────────────────────────

data class SuiteNode(val name: String, val selector: String)
data class FeatureNode(val name: String, val file: VirtualFile, val suiteSelector: String)
data class ScenarioNode(val name: String, val line: Int, val file: VirtualFile, val suiteSelector: String)

private data class FeatureInfo(
    val name: String,
    val file: VirtualFile,
    val scenarios: List<Pair<String, Int>>, // name → 1-based line number
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

    // Cached full set of groups — rebuilt on refresh, filtered on every keystroke
    @Volatile private var allGroups: List<Pair<SuiteNode, List<FeatureInfo>>> = emptyList()

    init {
        tree.isRootVisible    = false
        tree.showsRootHandles = true
        tree.cellRenderer     = ExplorerCellRenderer()

        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent)  { if (e.clickCount >= 2) onDoubleClick(e) }
            override fun mousePressed(e: MouseEvent)  { if (e.isPopupTrigger) showPopup(e) }
            override fun mouseReleased(e: MouseEvent) { if (e.isPopupTrigger) showPopup(e) }
        })

        searchField.textEditor.emptyText.text = "Filter features & scenarios…"
        searchField.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) { applyFilter() }
        })

        val group = DefaultActionGroup()
        group.add(object : AnAction("Refresh", "Reload suite groupings", AllIcons.Actions.Refresh) {
            override fun actionPerformed(e: AnActionEvent) { triggerRefresh() }
        })
        val toolbar = ActionManager.getInstance().createActionToolbar("ZioBddExplorer", group, true)
        toolbar.targetComponent = tree

        val topBar = JPanel(BorderLayout()).apply {
            add(toolbar.component, BorderLayout.WEST)
            add(searchField,       BorderLayout.CENTER)
        }
        setToolbar(topBar)
        setContent(JBScrollPane(tree))

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
        allGroups.forEach { (suiteNode, features) ->
            val filtered = features.mapNotNull { fi ->
                val nameHit = fi.name.lowercase().contains(text)
                val matchedScenarios = fi.scenarios.filter { (s, _) -> s.lowercase().contains(text) }
                when {
                    text.isEmpty()                  -> fi
                    nameHit                         -> fi
                    matchedScenarios.isNotEmpty()   -> fi.copy(scenarios = matchedScenarios)
                    else                            -> null
                }
            }
            if (filtered.isNotEmpty()) {
                val stn = DefaultMutableTreeNode(suiteNode)
                filtered.forEach { fi ->
                    val fn = DefaultMutableTreeNode(FeatureNode(fi.name, fi.file, suiteNode.selector))
                    fi.scenarios.forEach { (sName, sLine) ->
                        fn.add(DefaultMutableTreeNode(ScenarioNode(sName, sLine, fi.file, suiteNode.selector)))
                    }
                    stn.add(fn)
                }
                root.add(stn)
            }
        }
        model.reload()
        for (i in 0 until tree.rowCount) tree.expandRow(i)
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

        val name = lines.firstOrNull { it.trim().startsWith("Feature:") }
            ?.let { it.trim().removePrefix("Feature:").trim() }
            ?: file.nameWithoutExtension

        val scenarios = mutableListOf<Pair<String, Int>>()
        lines.forEachIndexed { idx, line ->
            val t = line.trim()
            if (t.startsWith("Scenario:") || t.startsWith("Scenario Outline:") || t.startsWith("Scenario Template:")) {
                val sName = t.removePrefix("Scenario Outline:")
                    .removePrefix("Scenario Template:")
                    .removePrefix("Scenario:")
                    .trim()
                scenarios += sName to (idx + 1)
            }
        }
        return FeatureInfo(name, file, scenarios)
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
            is SuiteNode    -> { icon = AllIcons.Nodes.ModuleGroup; text = data.name }
            is FeatureNode  -> { icon = AllIcons.FileTypes.Text;    text = data.name }
            is ScenarioNode -> { icon = AllIcons.Actions.Execute;   text = data.name }
        }
        return this
    }
}
