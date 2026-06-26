package zio.bdd.intellij.lang

import com.intellij.codeInsight.codeVision.CodeVisionAnchorKind
import com.intellij.codeInsight.codeVision.CodeVisionEntry
import com.intellij.codeInsight.codeVision.CodeVisionPlaceholderCollector
import com.intellij.codeInsight.codeVision.CodeVisionProvider
import com.intellij.codeInsight.codeVision.CodeVisionRelativeOrdering
import com.intellij.codeInsight.codeVision.CodeVisionState
import com.intellij.codeInsight.codeVision.ui.model.ClickableTextCodeVisionEntry
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiFile
import com.intellij.ui.awt.RelativePoint
import java.awt.event.MouseEvent

/**
 * Shows "N usages in feature files" above each step definition in Scala files —
 * the IntelliJ equivalent of VS Code's CodeLens.  Clicking the label opens a
 * chooser popup listing every feature scenario that uses the step.
 */
class ZioBddStepCodeVisionProvider : CodeVisionProvider<Unit> {

    companion object {
        const val ID = "zio.bdd.stepUsages"
    }

    override val id                  = ID
    override val name                = "ZIO BDD Step Usages"
    override val defaultAnchor       = CodeVisionAnchorKind.Top
    override val relativeOrderings   = emptyList<CodeVisionRelativeOrdering>()
    override val groupId             = "zio.bdd"

    override fun precomputeOnUiThread(editor: Editor) = Unit

    override fun computeCodeVision(editor: Editor, uiData: Unit): CodeVisionState {
        val project = editor.project ?: return CodeVisionState.Ready(emptyList())
        if (DumbService.isDumb(project)) return CodeVisionState.NotReady

        val vf = editor.virtualFile ?: return CodeVisionState.Ready(emptyList())
        if (vf.extension != "scala") return CodeVisionState.Ready(emptyList())

        val cache = ZioBddStepCache.getInstance(project)
        val defs  = cache.getStepDefinitions().filter { it.file == vf.path }
        if (defs.isEmpty()) return CodeVisionState.Ready(emptyList())

        val document = editor.document
        val entries  = mutableListOf<Pair<TextRange, CodeVisionEntry>>()

        for (def in defs) {
            if (def.line < 0 || def.line >= document.lineCount) continue
            val usages = findUsages(def, cache)
            val label  = when (usages.size) {
                0    -> "No usages in feature files"
                1    -> "1 usage in feature files"
                else -> "${usages.size} usages in feature files"
            }
            val lineStart = document.getLineStartOffset(def.line)
            val entry = ClickableTextCodeVisionEntry(
                label,
                id,
                { event: MouseEvent?, sourceEditor: Editor ->
                    showPopup(usages, def, project, event, sourceEditor)
                }
            )
            entries.add(Pair(TextRange(lineStart, document.getLineEndOffset(def.line)), entry))
        }

        return CodeVisionState.Ready(entries)
    }

    override fun getPlaceholderCollector(editor: Editor, psiFile: PsiFile?): CodeVisionPlaceholderCollector? = null

    // ── Internals ──────────────────────────────────────────────────────────

    private data class StepUsage(val featurePath: String, val line: Int, val text: String)

    private fun findUsages(def: KtStepDefinition, cache: ZioBddStepCache): List<StepUsage> =
        cache.featureFiles().flatMap { vf ->
            val content = try { String(vf.contentsToByteArray(), vf.charset) } catch (_: Exception) { return@flatMap emptyList() }
            content.lines().mapIndexedNotNull { idx, raw ->
                val t  = raw.trim()
                val kw = listOf("Given", "When", "Then", "And", "But")
                    .firstOrNull { t.startsWith("$it ") } ?: return@mapIndexedNotNull null
                val stepText = t.removePrefix("$kw ").trim()
                if (ZioBddStepMatcher.matchesStep(stepText, def))
                    StepUsage(vf.path, idx + 1, "$kw $stepText")
                else null
            }
        }

    private fun showPopup(
        usages: List<StepUsage>,
        def: KtStepDefinition,
        project: Project,
        event: MouseEvent?,
        editor: Editor,
    ) {
        if (usages.isEmpty()) {
            Messages.showInfoMessage(project, "No feature scenarios use this step definition.", "Step Usages")
            return
        }
        val labels = usages.map { "${it.featurePath.substringAfterLast('/')}:${it.line}  ${it.text}" }
        val popup  = JBPopupFactory.getInstance()
            .createPopupChooserBuilder(labels)
            .setTitle("Usages of \"${def.displayText}\" (${usages.size})")
            .setItemChosenCallback { label ->
                val usage = usages[labels.indexOf(label)]
                val vf    = LocalFileSystem.getInstance().findFileByPath(usage.featurePath) ?: return@setItemChosenCallback
                ApplicationManager.getApplication().invokeLater {
                    OpenFileDescriptor(project, vf, usage.line - 1, 0).navigate(true)
                }
            }
            .createPopup()
        if (event != null) popup.show(RelativePoint(event)) else popup.showInFocusCenter()
    }
}
