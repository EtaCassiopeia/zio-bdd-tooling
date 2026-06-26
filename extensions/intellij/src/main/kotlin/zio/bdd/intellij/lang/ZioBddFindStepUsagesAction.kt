package zio.bdd.intellij.lang

import com.intellij.codeInsight.hint.HintManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.LocalFileSystem
import zio.bdd.intellij.lang.psi.ZioBddStep

/**
 * "Find Step Usages" — shows all feature file steps that are covered by the
 * same step definition as the step under the cursor.
 *
 * Triggered from the right-click context menu in a .feature file.
 */
class ZioBddFindStepUsagesAction : AnAction("Find Step Usages") {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val step = stepAtCaret(e)
        e.presentation.isEnabledAndVisible = step != null && !DumbService.isDumb(e.project ?: return)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor  = e.getData(CommonDataKeys.EDITOR) ?: return
        val step    = stepAtCaret(e) ?: return

        val keyword  = step.getKeyword()
        val stepText = step.getStepText()
        if (stepText.isBlank()) return

        val defs  = ZioBddStepCache.getInstance(project).getStepDefinitions()
        val match = ZioBddStepMatcher.candidatesFor(keyword, defs)
            .firstOrNull { ZioBddStepMatcher.matchesStep(stepText, it) }

        if (match == null) {
            HintManager.getInstance().showErrorHint(editor, "No step definition found for: \"$stepText\"")
            return
        }

        // Background: scan all feature files for steps that match the same definition
        ApplicationManager.getApplication().executeOnPooledThread {
            val cache       = ZioBddStepCache.getInstance(project)
            val featureFiles = cache.featureFiles()
            data class StepUsage(val featurePath: String, val line: Int, val text: String)

            val usages = mutableListOf<StepUsage>()
            featureFiles.forEach { vf ->
                val content = try {
                    String(vf.contentsToByteArray(), vf.charset)
                } catch (_: Exception) { return@forEach }

                content.lines().forEachIndexed { idx, raw ->
                    val t  = raw.trim()
                    val kw = listOf("Given", "When", "Then", "And", "But")
                        .firstOrNull { t.startsWith("$it ") } ?: return@forEachIndexed
                    val txt = t.removePrefix("$kw ").trim()
                    if (ZioBddStepMatcher.matchesStep(txt, match)) {
                        usages += StepUsage(vf.path, idx + 1, "$kw $txt")
                    }
                }
            }

            ApplicationManager.getApplication().invokeLater {
                if (usages.isEmpty()) {
                    HintManager.getInstance().showInformationHint(
                        editor,
                        "No other feature steps use definition: \"${match.displayText}\""
                    )
                    return@invokeLater
                }

                val labels = usages.map { u ->
                    "${u.featurePath.substringAfterLast('/')}:${u.line}  ${u.text}"
                }
                val popup = JBPopupFactory.getInstance()
                    .createPopupChooserBuilder(labels)
                    .setTitle("Usages of \"${match.displayText}\" (${usages.size})")
                    .setItemChosenCallback { label ->
                        val idx = labels.indexOf(label)
                        if (idx < 0) return@setItemChosenCallback
                        val usage = usages[idx]
                        val vf = LocalFileSystem.getInstance()
                            .findFileByPath(usage.featurePath) ?: return@setItemChosenCallback
                        OpenFileDescriptor(project, vf, usage.line - 1, 0).navigate(true)
                    }
                    .createPopup()

                popup.showInBestPositionFor(editor)
            }
        }
    }

    private fun stepAtCaret(e: AnActionEvent): ZioBddStep? {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return null
        val psiFile = e.getData(CommonDataKeys.PSI_FILE) ?: return null
        val offset = editor.caretModel.offset
        var el = psiFile.findElementAt(offset)
        while (el != null) {
            if (el is ZioBddStep) return el
            el = el.parent
        }
        return null
    }
}
