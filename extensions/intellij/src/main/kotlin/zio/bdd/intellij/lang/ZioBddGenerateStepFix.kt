package zio.bdd.intellij.lang

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.PsiFile
import java.awt.Component
import java.io.File
import javax.swing.JList

/**
 * Intention action attached to the "No step definition found" annotator warning.
 *
 * On invoke: locates all Scala files that already contain step definitions
 * (from the cache), lets the user pick one if there are several, then appends
 * a minimal stub before the final closing brace of that file.
 *
 * The stub uses `???` (Scala's not-implemented expression) so it compiles but
 * throws at runtime until the user fills it in.
 */
class ZioBddGenerateStepFix(
    private val keyword: String,
    private val stepText: String,
) : IntentionAction {

    override fun getText(): String       = "Create step definition"
    override fun getFamilyName(): String = "Create step definition"
    override fun startInWriteAction(): Boolean = false

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean = true

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        val files = ZioBddStepCache.getInstance(project)
            .getStepDefinitions()
            .map { it.file }
            .filter { it.isNotEmpty() }
            .distinct()

        when {
            files.isEmpty() -> Messages.showInfoMessage(
                project,
                "No existing ZIOSteps files found in the project.\nCreate a ZIOSteps subclass first.",
                "Create Step Definition",
            )
            files.size == 1 -> insertStub(project, files.first())
            else            -> pickFileAndInsert(project, files)
        }
    }

    private fun pickFileAndInsert(project: Project, files: List<String>) {
        val names = files.map { File(it).name }
        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(names)
            .setTitle("Choose target Scala file")
            .setRenderer(object : javax.swing.DefaultListCellRenderer() {
                override fun getListCellRendererComponent(
                    list: JList<*>, value: Any?, index: Int,
                    isSelected: Boolean, cellHasFocus: Boolean,
                ): Component {
                    super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                    if (index >= 0) toolTipText = files[index]
                    return this
                }
            })
            .setItemChosenCallback { chosenName ->
                val chosenFile = files[names.indexOf(chosenName)]
                insertStub(project, chosenFile)
            }
            .createPopup()
            .showInFocusCenter()
    }

    private fun insertStub(project: Project, filePath: String) {
        val vf   = LocalFileSystem.getInstance().findFileByPath(filePath) ?: return
        val stub = buildStub()

        ApplicationManager.getApplication().runWriteAction {
            val current   = VfsUtil.loadText(vf)
            val insertPos = lastTopLevelBrace(current)
            VfsUtil.saveText(vf, current.substring(0, insertPos) + stub + current.substring(insertPos))
        }

        // Navigate to the new stub after the write completes
        ApplicationManager.getApplication().invokeLater {
            val refreshed = LocalFileSystem.getInstance().refreshAndFindFileByPath(filePath) ?: return@invokeLater
            val content   = VfsUtil.loadText(refreshed)
            val stubLine  = content.substring(0, content.indexOf(stub) + 1).count { it == '\n' }
            FileEditorManager.getInstance(project)
                .openTextEditor(OpenFileDescriptor(project, refreshed, stubLine, 2), true)
        }
    }

    private fun buildStub(): String =
        "\n  $keyword(\"$stepText\") {\n    ???\n  }\n"

    // Returns the index of the last `}` that appears alone on its own line
    // (the closing brace of the outermost class body).
    private fun lastTopLevelBrace(content: String): Int {
        val idx = content.lastIndexOf('\n' + "}")
        return if (idx >= 0) idx + 1 else content.length
    }
}
