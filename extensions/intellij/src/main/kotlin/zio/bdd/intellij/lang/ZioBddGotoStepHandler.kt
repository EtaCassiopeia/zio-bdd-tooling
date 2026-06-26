package zio.bdd.intellij.lang

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import zio.bdd.intellij.lang.psi.ZioBddStep

class ZioBddGotoStepHandler : GotoDeclarationHandler {

    override fun getGotoDeclarationTargets(
        sourceElement: PsiElement?,
        offset: Int,
        editor: Editor?,
    ): Array<PsiElement>? {
        val element = sourceElement ?: return null
        if (DumbService.isDumb(element.project)) return null

        val step     = findEnclosingStep(element) ?: return null
        val keyword  = step.getKeyword()
        val stepText = step.getStepText()
        if (stepText.isBlank()) return null

        val defs  = ZioBddStepCache.getInstance(element.project).getStepDefinitions()
        val match = ZioBddStepMatcher.candidatesFor(keyword, defs)
            .firstOrNull { ZioBddStepMatcher.matchesStep(stepText, it) }
            ?: return null

        val vf  = LocalFileSystem.getInstance().findFileByPath(match.file) ?: return null
        val psf = PsiManager.getInstance(element.project).findFile(vf) ?: return null
        val doc = FileDocumentManager.getInstance().getDocument(vf)
        if (doc == null || match.line < 0 || match.line >= doc.lineCount) return arrayOf(psf)

        // Navigate to the first non-whitespace character on the matched line so the
        // cursor lands on the step keyword (Given/When/Then) rather than leading spaces.
        val lineStart = doc.getLineStartOffset(match.line)
        val lineEnd   = doc.getLineEndOffset(match.line)
        val lineText  = doc.getText(TextRange(lineStart, lineEnd))
        val col       = lineText.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
        val target    = psf.findElementAt(lineStart + col) ?: psf
        return arrayOf(target)
    }

    private fun findEnclosingStep(e: PsiElement): ZioBddStep? {
        var cur: PsiElement? = e
        while (cur != null) { if (cur is ZioBddStep) return cur; cur = cur.parent }
        return null
    }
}
