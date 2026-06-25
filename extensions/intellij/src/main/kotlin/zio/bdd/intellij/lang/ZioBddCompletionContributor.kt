package zio.bdd.intellij.lang

import com.intellij.codeInsight.completion.*
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.DumbService
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiElement
import com.intellij.util.ProcessingContext
import zio.bdd.intellij.lang.psi.ZioBddFile
import zio.bdd.intellij.lang.psi.ZioBddStep

class ZioBddCompletionContributor : CompletionContributor() {
    init {
        extend(
            CompletionType.BASIC,
            PlatformPatterns.psiElement().inFile(PlatformPatterns.psiFile(ZioBddFile::class.java)),
            StepCompletionProvider,
        )
    }

    private object StepCompletionProvider : CompletionProvider<CompletionParameters>() {
        override fun addCompletions(
            parameters: CompletionParameters,
            context: ProcessingContext,
            result: CompletionResultSet,
        ) {
            val project = parameters.position.project
            if (DumbService.isDumb(project)) return

            val step    = findEnclosingStep(parameters.position) ?: return
            val keyword = step.getKeyword()
            val defs    = ZioBddStepCache.getInstance(project).getStepDefinitions()
            val prefix  = result.prefixMatcher.prefix

            candidatesFor(keyword, defs)
                .filter { it.displayText.contains(prefix, ignoreCase = true) }
                .forEach { def ->
                    result.addElement(
                        LookupElementBuilder.create(def.displayText)
                            .withIcon(AllIcons.Nodes.Function)
                            .withTypeText(def.file.substringAfterLast('/'))
                    )
                }
        }

        private fun findEnclosingStep(e: PsiElement): ZioBddStep? {
            var cur: PsiElement? = e
            while (cur != null) { if (cur is ZioBddStep) return cur; cur = cur.parent }
            return null
        }

        private fun candidatesFor(keyword: String, defs: List<KtStepDefinition>): List<KtStepDefinition> =
            when (keyword) {
                "And", "But" -> defs
                else -> defs.filter { it.keyword == keyword || it.keyword == "And" || it.keyword == "But" }
            }
    }
}
