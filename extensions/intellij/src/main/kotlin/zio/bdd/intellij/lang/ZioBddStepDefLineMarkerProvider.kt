package zio.bdd.intellij.lang

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiElement

/**
 * Gutter marker on Scala step definitions — click to see which feature
 * scenarios use this step (the IntelliJ equivalent of VS Code CodeLens).
 *
 * We detect step definition lines by checking whether the leaf element's
 * text is a BDD keyword (Given/When/Then/And/But) and that its parent
 * contains a string literal argument (the step pattern).
 */
class ZioBddStepDefLineMarkerProvider : LineMarkerProvider {

    private val BDD_KEYWORDS = setOf("Given", "When", "Then", "And", "But",
                                     "GivenS", "WhenS", "ThenS", "AndS", "ButS")

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        if (element.firstChild != null) return null  // leaf elements only
        if (DumbService.isDumb(element.project)) return null
        if (element.containingFile?.virtualFile?.extension != "scala") return null

        val text = element.text
        if (text !in BDD_KEYWORDS) return null

        // Verify this leaf is the first token on its source line (not inside a string etc.)
        val offset = element.textRange.startOffset
        val doc = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance()
            .getDocument(element.containingFile.virtualFile) ?: return null
        val lineStart = doc.getLineStartOffset(doc.getLineNumber(offset))
        val lineText  = doc.getText(com.intellij.openapi.util.TextRange(lineStart, offset))
        if (lineText.any { !it.isWhitespace() }) return null  // keyword not at line start

        return LineMarkerInfo(
            element,
            element.textRange,
            AllIcons.General.InspectionsEye,
            { _ -> "Find feature scenarios using this step" },
            { mouseEvent, leaf ->
                val project = leaf.project
                val cache   = ZioBddStepCache.getInstance(project)
                val defs    = cache.getStepDefinitions()

                // Find the KtStepDefinition for this exact leaf/line
                val leafLine = doc.getLineNumber(leaf.textRange.startOffset)
                val filePath = leaf.containingFile.virtualFile.path
                val def = defs.firstOrNull { it.file == filePath && it.line == leafLine }
                    ?: return@LineMarkerInfo

                // Scan feature files for matching steps
                data class ScenarioUsage(val featurePath: String, val line: Int, val text: String)
                val usages = mutableListOf<ScenarioUsage>()

                cache.featureFiles().forEach { vf ->
                    val content = try {
                        String(vf.contentsToByteArray(), vf.charset)
                    } catch (_: Exception) { return@forEach }

                    content.lines().forEachIndexed { idx, raw ->
                        val t  = raw.trim()
                        val kw = listOf("Given", "When", "Then", "And", "But")
                            .firstOrNull { t.startsWith("$it ") } ?: return@forEachIndexed
                        val stepText = t.removePrefix("$kw ").trim()
                        if (ZioBddStepMatcher.matchesStep(stepText, def)) {
                            usages += ScenarioUsage(vf.path, idx + 1, "$kw $stepText")
                        }
                    }
                }

                if (usages.isEmpty()) {
                    com.intellij.openapi.ui.Messages.showInfoMessage(
                        project, "No feature scenarios use this step definition.", "Step Usages"
                    )
                    return@LineMarkerInfo
                }

                val labels = usages.map { u ->
                    "${u.featurePath.substringAfterLast('/')}:${u.line}  ${u.text}"
                }
                val editor = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project)
                    .selectedTextEditor
                val popup = JBPopupFactory.getInstance()
                    .createPopupChooserBuilder(labels)
                    .setTitle("Usages of \"${def.displayText}\" (${usages.size})")
                    .setItemChosenCallback { label ->
                        val idx   = labels.indexOf(label)
                        val usage = usages[idx]
                        val vf    = LocalFileSystem.getInstance().findFileByPath(usage.featurePath)
                            ?: return@setItemChosenCallback
                        OpenFileDescriptor(project, vf, usage.line - 1, 0).navigate(true)
                    }
                    .createPopup()

                if (editor != null) popup.showInBestPositionFor(editor)
                else popup.showInFocusCenter()
            },
            GutterIconRenderer.Alignment.LEFT,
            { "zio-bdd step usages" },
        )
    }
}
