package zio.bdd.intellij.lang

import com.intellij.lang.documentation.DocumentationProvider
import com.intellij.openapi.project.DumbService
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import zio.bdd.intellij.lang.psi.ZioBddStep

class ZioBddDocumentationProvider : DocumentationProvider {

    private val typeParam = Regex("""\{([^}]+)\}""")

    override fun generateDoc(element: PsiElement, originalElement: PsiElement?): String? {
        val project = element.project
        if (DumbService.isDumb(project)) return null

        val step = findEnclosingStep(element) ?: return null
        val keyword  = step.getKeyword()
        val stepText = step.getStepText()
        if (stepText.isBlank()) return null

        val defs = ZioBddStepCache.getInstance(project).getStepDefinitions()
        val match = ZioBddStepMatcher.candidatesFor(keyword, defs)
            .firstOrNull { ZioBddStepMatcher.matchesStep(stepText, it) }
            ?: return null

        return buildDoc(match)
    }

    override fun getDocumentationElementForLookupItem(
        psiManager: PsiManager,
        obj: Any?,
        element: PsiElement?,
    ): PsiElement? = null

    override fun getUrlFor(element: PsiElement, originalElement: PsiElement?): List<String>? = null

    private fun buildDoc(def: KtStepDefinition): String {
        val sb = StringBuilder()
        sb.append("<b>${def.keyword}</b> ${def.displayText}")

        val params = typeParam.findAll(def.displayText).map { it.groupValues[1] }.toList()
        if (params.isNotEmpty()) {
            sb.append("<br/><br/><table>")
            sb.append("<tr><th align='left'>Parameter</th><th align='left'>Type</th></tr>")
            params.forEachIndexed { i, type ->
                sb.append("<tr><td>${i + 1}</td><td><code>$type</code></td></tr>")
            }
            sb.append("</table>")
        }

        if (def.file.isNotEmpty() && def.line >= 0) {
            val fileName = def.file.substringAfterLast('/')
            sb.append("<br/><i>$fileName:${def.line + 1}</i>")
        }

        return sb.toString()
    }

    private fun findEnclosingStep(e: PsiElement): ZioBddStep? {
        var cur: PsiElement? = e
        while (cur != null) { if (cur is ZioBddStep) return cur; cur = cur.parent }
        return null
    }

}
