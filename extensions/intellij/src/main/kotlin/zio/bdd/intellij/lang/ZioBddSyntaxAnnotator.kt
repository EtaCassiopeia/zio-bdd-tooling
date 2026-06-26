package zio.bdd.intellij.lang

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import zio.bdd.intellij.lang.psi.ZioBddFeatureHeader
import zio.bdd.intellij.lang.psi.ZioBddScenarioHeader
import zio.bdd.intellij.lang.psi.ZioBddStep

/**
 * Refines syntax highlighting within single lines after the lexer's whole-line tokens.
 *
 * Gives VS Code-like coloring:
 *   "Scenario: My name" → "Scenario:" in keyword color, "My name" in gold
 *   "  Given the user logs in" → "Given" in keyword color, " the user logs in" in default foreground
 *   "<placeholder>" within step text → template-variable color
 */
class ZioBddSyntaxAnnotator : Annotator {

    private val placeholderRe = Regex("<[^>]+>")

    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        when (element) {
            is ZioBddStep           -> highlightStep(element, holder)
            is ZioBddFeatureHeader  -> highlightKeywordLine(element, holder)
            is ZioBddScenarioHeader -> highlightKeywordLine(element, holder)
        }
    }

    private fun highlightStep(step: ZioBddStep, holder: AnnotationHolder) {
        val raw    = step.text
        val indent = raw.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
        val kw     = step.getKeyword()
        val base   = step.textRange.startOffset

        val kwStart = base + indent
        val kwEnd   = kwStart + kw.length

        // Keyword "Given"/"When"/"Then" etc. in keyword color
        annotate(holder, TextRange(kwStart, kwEnd), ZioBddSyntaxHighlighter.STEP_KEYWORD)

        val body = step.getStepText()
        if (body.isEmpty()) return
        val bodyStart = kwEnd + 1  // skip the space between keyword and text
        val bodyEnd   = base + raw.trimEnd().length
        if (bodyStart >= bodyEnd) return

        // Color placeholders distinctly; everything else in default foreground
        var pos = 0
        for (m in placeholderRe.findAll(body)) {
            if (m.range.first > pos)
                annotate(holder, TextRange(bodyStart + pos, bodyStart + m.range.first), ZioBddSyntaxHighlighter.STEP_TEXT)
            annotate(holder, TextRange(bodyStart + m.range.first, bodyStart + m.range.last + 1), ZioBddSyntaxHighlighter.PLACEHOLDER)
            pos = m.range.last + 1
        }
        if (pos < body.length)
            annotate(holder, TextRange(bodyStart + pos, bodyStart + body.length), ZioBddSyntaxHighlighter.STEP_TEXT)
    }

    private fun highlightKeywordLine(element: PsiElement, holder: AnnotationHolder) {
        val raw     = element.text
        val indent  = raw.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
        val trimmed = raw.trimStart()
        val colon   = trimmed.indexOf(':')
        if (colon < 0) return
        val base = element.textRange.startOffset

        val kwStart  = base + indent
        val kwEnd    = kwStart + colon + 1   // includes the ':'

        // "Feature:" / "Scenario:" etc. in keyword color
        annotate(holder, TextRange(kwStart, kwEnd), ZioBddSyntaxHighlighter.KEYWORD)

        // Title text (scenario/feature name) in gold
        val titleEnd = base + raw.trimEnd().length
        if (kwEnd < titleEnd)
            annotate(holder, TextRange(kwEnd, titleEnd), ZioBddSyntaxHighlighter.TITLE_TEXT)
    }

    private fun annotate(holder: AnnotationHolder, range: TextRange, attr: TextAttributesKey) {
        holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
            .range(range)
            .textAttributes(attr)
            .create()
    }
}
