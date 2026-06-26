package zio.bdd.intellij.lang

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiElement
import com.intellij.ui.awt.RelativePoint
import java.awt.event.MouseEvent

/**
 * Adds an eye gutter icon to every step definition line in Scala files.
 * Clicking the icon scans all feature files and shows a popup listing every
 * step that is covered by the definition under the cursor.
 */
class ZioBddStepDefLineMarkerProvider : LineMarkerProvider {

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        // Only annotate leaf nodes to avoid duplicate markers per line
        if (element.firstChild != null) return null

        val vf = element.containingFile?.virtualFile ?: return null
        if (vf.extension != "scala") return null

        val project = element.project
        if (DumbService.isDumb(project)) return null

        val doc = FileDocumentManager.getInstance().getDocument(vf) ?: return null
        val line = doc.getLineNumber(element.textOffset)

        // Only mark the very first non-whitespace leaf on its line
        val lineStart = doc.getLineStartOffset(line)
        val lineText  = doc.getText(com.intellij.openapi.util.TextRange(lineStart, doc.getLineEndOffset(line)))
        val firstNonWs = lineStart + lineText.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
        if (element.textOffset != firstNonWs) return null

        val def = ZioBddStepCache.getInstance(project)
            .getStepDefinitions()
            .firstOrNull { it.file == vf.path && it.line == line }
            ?: return null

        return LineMarkerInfo(
            element,
            element.textRange,
            AllIcons.General.InspectionsEye,
            { "Find feature file usages of this step definition" },
            { mouseEvent, _ -> showUsagesPopup(def, project, mouseEvent) },
            GutterIconRenderer.Alignment.LEFT,
            { "Find feature file usages of this step definition" },
        )
    }

    private fun showUsagesPopup(def: KtStepDefinition, project: com.intellij.openapi.project.Project, mouseEvent: MouseEvent) {
        ApplicationManager.getApplication().executeOnPooledThread {
            data class StepUsage(val featurePath: String, val line: Int, val text: String)

            val cache  = ZioBddStepCache.getInstance(project)
            val usages = cache.featureFiles().flatMap { vf ->
                val content = try { String(vf.contentsToByteArray(), vf.charset) }
                              catch (_: Exception) { return@flatMap emptyList() }
                content.lines().mapIndexedNotNull { idx, raw ->
                    val t  = raw.trim()
                    val kw = listOf("Given", "When", "Then", "And", "But")
                        .firstOrNull { t.startsWith("$it ") } ?: return@mapIndexedNotNull null
                    val txt = t.removePrefix("$kw ").trim()
                    if (ZioBddStepMatcher.matchesStep(txt, def))
                        StepUsage(vf.path, idx + 1, "$kw $txt")
                    else null
                }
            }

            ApplicationManager.getApplication().invokeLater {
                if (usages.isEmpty()) {
                    com.intellij.openapi.ui.Messages.showInfoMessage(
                        project,
                        "No feature scenarios use this step definition.",
                        "Step Usages"
                    )
                    return@invokeLater
                }
                val labels = usages.map { "${it.featurePath.substringAfterLast('/')}:${it.line}  ${it.text}" }
                JBPopupFactory.getInstance()
                    .createPopupChooserBuilder(labels)
                    .setTitle("Usages of \"${def.displayText}\" (${usages.size})")
                    .setItemChosenCallback { label ->
                        val usage = usages[labels.indexOf(label)]
                        val target = LocalFileSystem.getInstance().findFileByPath(usage.featurePath)
                            ?: return@setItemChosenCallback
                        ApplicationManager.getApplication().invokeLater {
                            OpenFileDescriptor(project, target, usage.line - 1, 0).navigate(true)
                        }
                    }
                    .createPopup()
                    .show(RelativePoint(mouseEvent))
            }
        }
    }
}
