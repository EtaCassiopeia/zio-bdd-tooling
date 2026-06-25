package zio.bdd.intellij.lang

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import zio.bdd.intellij.lang.psi.ZioBddStep

class ZioBddGotoStepHandler : GotoDeclarationHandler {

    private val colPlaceholder = Regex("""\<(\w+)\>""")

    override fun getGotoDeclarationTargets(
        sourceElement: PsiElement?,
        offset: Int,
        editor: Editor?,
    ): Array<PsiElement>? {
        val element = sourceElement ?: return null
        if (DumbService.isDumb(element.project)) return null

        val step = findEnclosingStep(element) ?: return null
        val keyword  = step.getKeyword()
        val stepText = step.getStepText()
        if (stepText.isBlank()) return null

        val defs = ZioBddStepCache.getInstance(element.project).getStepDefinitions()
        val match = candidatesFor(keyword, defs).firstOrNull { matchesStep(stepText, it) }
            ?: return null

        val vf  = LocalFileSystem.getInstance().findFileByPath(match.file) ?: return null
        val psf = PsiManager.getInstance(element.project).findFile(vf) ?: return null
        val doc = FileDocumentManager.getInstance().getDocument(vf)
        if (doc == null || match.line < 0 || match.line >= doc.lineCount) return arrayOf(psf)
        val target = psf.findElementAt(doc.getLineStartOffset(match.line)) ?: psf
        return arrayOf(target)
    }

    private fun findEnclosingStep(e: PsiElement): ZioBddStep? {
        var cur: PsiElement? = e
        while (cur != null) { if (cur is ZioBddStep) return cur; cur = cur.parent }
        return null
    }

    private fun matchesStep(text: String, def: KtStepDefinition): Boolean =
        if (colPlaceholder.containsMatchIn(text)) structuralMatch(text, def)
        else try { Regex(def.pattern).matches(text) } catch (_: Exception) { false }

    private fun structuralMatch(template: String, def: KtStepDefinition): Boolean {
        val lits = mutableListOf<String>(); var pos = 0; var count = 0
        colPlaceholder.findAll(template).forEach { m ->
            if (m.range.first > pos) lits += template.substring(pos, m.range.first)
            count++; pos = m.range.last + 1
        }
        if (pos < template.length) lits += template.substring(pos)
        return lits == def.literals && count == def.extractorCount
    }

    private fun candidatesFor(keyword: String, defs: List<KtStepDefinition>): List<KtStepDefinition> =
        when (keyword) {
            "And", "But" -> defs
            else -> defs.filter { it.keyword == keyword || it.keyword == "And" || it.keyword == "But" }
        }
}
