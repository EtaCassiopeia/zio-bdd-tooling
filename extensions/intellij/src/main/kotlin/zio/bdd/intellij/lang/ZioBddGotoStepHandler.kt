package zio.bdd.intellij.lang

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.impl.FakePsiElement
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

        val lineStart    = doc.getLineStartOffset(match.line)
        val lineEnd      = doc.getLineEndOffset(match.line)
        val lineText     = doc.getText(TextRange(lineStart, lineEnd))
        val col          = lineText.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
        val endCol       = stepCallEndCol(lineText, col)
        val targetOffset = lineStart + col
        val targetRange  = TextRange(lineStart + col, lineStart + endCol)

        // Synthetic navigatable element with the exact range we want highlighted.
        // Parenting to `psf` gives IntelliJ a valid file reference for all other
        // lookups; we override only range/offset/navigate so the highlight covers just
        // the step call text (keyword through closing ')'), not the whole line.
        // FakePsiElement supplies the rest of PsiElement (avoiding hand-delegating the
        // whole interface, which would pull in deprecated checkAdd/checkDelete members).
        val target = object : FakePsiElement() {
            override fun getParent(): PsiElement = psf
            override fun getTextRange()  = targetRange
            override fun getTextOffset() = targetOffset
            override fun navigate(requestFocus: Boolean) =
                OpenFileDescriptor(psf.project, vf, targetOffset).navigate(requestFocus)
            override fun canNavigate()         = true
            override fun canNavigateToSource() = true
        }
        return arrayOf(target)
    }

    /** Returns the column (exclusive) just after the closing `)` of the step method call. */
    private fun stepCallEndCol(lineText: String, startCol: Int): Int {
        var depth  = 0
        var inStr  = false
        var escape = false
        for (i in startCol until lineText.length) {
            val c = lineText[i]
            when {
                escape             -> escape = false
                c == '\\' && inStr -> escape = true
                c == '"'           -> inStr = !inStr
                !inStr && c == '(' -> depth++
                !inStr && c == ')' -> { depth--; if (depth == 0) return i + 1 }
            }
        }
        return lineText.trimEnd().length
    }

    private fun findEnclosingStep(e: PsiElement): ZioBddStep? {
        var cur: PsiElement? = e
        while (cur != null) { if (cur is ZioBddStep) return cur; cur = cur.parent }
        return null
    }
}
