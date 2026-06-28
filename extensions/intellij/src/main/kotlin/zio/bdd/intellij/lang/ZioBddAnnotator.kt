package zio.bdd.intellij.lang

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import zio.bdd.intellij.lang.psi.ZioBddStep

class ZioBddAnnotator : Annotator {

    private val stepKeywords = listOf("Given", "When", "Then", "And", "But")

    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        if (DumbService.isDumb(element.project)) return

        // A mis-cased keyword (e.g. "then") is lexed as plain TEXT/STEP_TEXT, not a
        // step, so the runner silently ignores the line and the step never runs.
        val type = element.node?.elementType
        if (type == ZioBddTokenTypes.TEXT || type == ZioBddTokenTypes.STEP_TEXT) {
            annotateMiscasedKeyword(element, holder)
            return
        }

        if (element !is ZioBddStep) return

        val keyword  = element.getKeyword()
        val stepText = element.getStepText()
        if (stepText.isBlank()) return

        val defs = ZioBddStepCache.getInstance(element.project).getStepDefinitions()
        if (defs.isEmpty()) return  // cache not warmed yet — no false positives

        val candidates = ZioBddStepMatcher.candidatesFor(keyword, defs)
        if (candidates.any { ZioBddStepMatcher.matchesStep(stepText, it) }) return

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

    // Flag a line whose first word is a step keyword spelled with the wrong case
    // (e.g. "then" → "Then"). Only exact case-insensitive matches are flagged —
    // never fuzzy near-misses, which would false-positive on description prose.
    private fun annotateMiscasedKeyword(element: PsiElement, holder: AnnotationHolder) {
        val raw     = element.text
        val trimmed = raw.trimStart()
        val word    = trimmed.takeWhile { it.isLetter() }
        if (word.isEmpty()) return
        val kw = stepKeywords.firstOrNull { it.equals(word, ignoreCase = true) && it != word } ?: return
        val wordStart = element.textRange.startOffset + (raw.length - trimmed.length)
        holder.newAnnotation(
            HighlightSeverity.ERROR,
            "Step keyword \"$word\" must be capitalised as \"$kw\". " +
                "The runner ignores mis-cased keywords, so this step will not run.",
        )
            .range(TextRange(wordStart, wordStart + word.length))
            .create()
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
