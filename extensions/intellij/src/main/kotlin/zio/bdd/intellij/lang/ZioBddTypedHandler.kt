package zio.bdd.intellij.lang

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import zio.bdd.intellij.lang.psi.ZioBddFile

/**
 * IntelliJ only auto-opens the completion popup while typing identifier characters;
 * "@" is not one, so tag completion never appeared on typing. Schedule the popup
 * explicitly so "@" behaves like the LSP's "@" trigger character.
 *
 * Replaces the deprecated `CompletionContributor.invokeAutoPopup` override.
 */
class ZioBddTypedHandler : TypedHandlerDelegate() {
    override fun checkAutoPopup(charTyped: Char, project: Project, editor: Editor, file: PsiFile): Result {
        if (charTyped == '@' && file is ZioBddFile) {
            AutoPopupController.getInstance(project).scheduleAutoPopup(editor)
            return Result.STOP
        }
        return Result.CONTINUE
    }
}
