package zio.bdd.intellij.lang

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.project.DumbService
import com.intellij.psi.PsiElement
import zio.bdd.intellij.lang.psi.ZioBddStep

class ZioBddAnnotator : Annotator {

    private val colPlaceholder = Regex("""\<(\w+)\>""")

    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        if (element !is ZioBddStep) return
        if (DumbService.isDumb(element.project)) return

        val keyword  = element.getKeyword()
        val stepText = element.getStepText()
        if (stepText.isBlank()) return

        val defs = ZioBddStepCache.getInstance(element.project).getStepDefinitions()
        if (defs.isEmpty()) return  // cache not warmed yet — no false positives

        val candidates = candidatesFor(keyword, defs)
        if (candidates.any { matchesStep(stepText, it) }) return

        val hint    = closestMatch(stepText, defs)
        val message = if (hint != null)
            "No step definition found. Did you mean: \"${hint.displayText}\"?"
        else
            "No step definition found for: \"$stepText\""

        holder.newAnnotation(HighlightSeverity.WARNING, message)
            .range(element)
            .withFix(ZioBddGenerateStepFix(keyword, stepText))
            .create()
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

    private fun closestMatch(text: String, defs: List<KtStepDefinition>): KtStepDefinition? {
        if (defs.isEmpty()) return null
        val low  = text.lowercase()
        val best = defs.minByOrNull { levenshtein(low, it.displayText.lowercase()) } ?: return null
        return if (levenshtein(low, best.displayText.lowercase()) <= 15) best else null
    }

    private fun levenshtein(a: String, b: String): Int {
        val dp = Array(a.length + 1) { i -> IntArray(b.length + 1) { j -> if (i == 0) j else if (j == 0) i else 0 } }
        for (i in 1..a.length) for (j in 1..b.length) {
            dp[i][j] = if (a[i - 1] == b[j - 1]) dp[i - 1][j - 1]
            else 1 + minOf(dp[i - 1][j], dp[i][j - 1], dp[i - 1][j - 1])
        }
        return dp[a.length][b.length]
    }
}
