package zio.bdd.intellij.lang

import com.intellij.codeInsight.completion.*
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.codeInsight.template.Template
import com.intellij.codeInsight.template.TemplateManager
import com.intellij.codeInsight.template.impl.TextExpression
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiElement
import com.intellij.util.ProcessingContext
import zio.bdd.intellij.lang.psi.ZioBddFile
import zio.bdd.intellij.lang.psi.ZioBddStep

class ZioBddCompletionContributor : CompletionContributor() {
    init {
        val inFeatureFile =
            PlatformPatterns.psiElement().inFile(PlatformPatterns.psiFile(ZioBddFile::class.java))
        extend(CompletionType.BASIC, inFeatureFile, StepCompletionProvider)
        extend(CompletionType.BASIC, inFeatureFile, FeatureStructureCompletionProvider)
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
            ZioBddStepMatcher.candidatesFor(keyword, defs)
    }

    // Tags, structural keywords, and snippet templates. Unlike step completion
    // these fire outside a step element — on tag lines and on blank/structural
    // lines — so the provider inspects the raw line prefix rather than the PSI.
    private object FeatureStructureCompletionProvider : CompletionProvider<CompletionParameters>() {
        private val stepKeywords = listOf("Given", "When", "Then", "And", "But", "*")
        private val builtinTags  = listOf("@ignore", "@flags(key=value)")
        private val tagToken     = Regex("@(\\w+)")

        // (label, insertText) for plain structural keywords.
        private val structuralKeywords = listOf(
            "Feature:"          to "Feature: ",
            "Background:"       to "Background:\n  ",
            "Scenario:"         to "Scenario: ",
            "Scenario Outline:" to "Scenario Outline: ",
            "Rule:"             to "Rule: ",
            "Examples:"         to "Examples:\n  | | |\n  | | |",
        )

        override fun addCompletions(
            parameters: CompletionParameters,
            context: ProcessingContext,
            result: CompletionResultSet,
        ) {
            val document   = parameters.editor.document
            val offset     = parameters.offset
            val lineStart  = document.getLineStartOffset(document.getLineNumber(offset))
            val linePrefix = document.getText(TextRange(lineStart, offset))
            val trimmed    = linePrefix.trim()

            if (trimmed.startsWith("@")) {
                // The "@" the user already typed is part of the prefix, so the
                // lookup ("@auth") replaces it instead of doubling to "@@auth".
                val atIndex   = linePrefix.lastIndexOf('@')
                val tagPrefix = if (atIndex >= 0) linePrefix.substring(atIndex) else "@"
                addTags(parameters.position.project, result.withPrefixMatcher(tagPrefix))
                return
            }
            // Step lines are handled by StepCompletionProvider.
            if (stepKeywords.any { trimmed == it || trimmed.startsWith("$it ") }) return
            addStructural(trimmed, result)
        }

        private fun addTags(project: Project, result: CompletionResultSet) {
            val tags = sortedSetOf<String>()
            tags.addAll(builtinTags)
            if (!DumbService.isDumb(project)) {
                ZioBddStepCache.getInstance(project).featureFiles().forEach { vf ->
                    try {
                        String(vf.contentsToByteArray(), vf.charset).lines().forEach { line ->
                            if (line.trim().startsWith("@"))
                                tagToken.findAll(line).forEach { tags.add("@" + it.groupValues[1]) }
                        }
                    } catch (_: Exception) {}
                }
            }
            tags.forEach { result.addElement(LookupElementBuilder.create(it)) }
        }

        private fun addStructural(trimmed: String, result: CompletionResultSet) {
            val keep = { label: String -> trimmed.isEmpty() || label.startsWith(trimmed, ignoreCase = true) }
            structuralKeywords.filter { keep(it.first) }.forEach { (label, insert) ->
                result.addElement(
                    LookupElementBuilder.create(label)
                        .withInsertHandler { ctx, _ ->
                            ctx.document.replaceString(ctx.startOffset, ctx.tailOffset, insert)
                            ctx.editor.caretModel.moveToOffset(ctx.startOffset + insert.length)
                            ctx.commitDocument()
                        }
                )
            }
            structuralTemplates.filter { keep(it.label) }.forEach { spec ->
                result.addElement(
                    LookupElementBuilder.create(spec.label)
                        .withInsertHandler { ctx, _ ->
                            ctx.document.deleteString(ctx.startOffset, ctx.tailOffset)
                            ctx.commitDocument()
                            ctx.editor.caretModel.moveToOffset(ctx.startOffset)
                            val manager  = TemplateManager.getInstance(ctx.project)
                            val template = manager.createTemplate("", "")
                            template.isToReformat = false
                            spec.build(template)
                            manager.startTemplate(ctx.editor, template)
                        }
                )
            }
        }
    }

    // ── Snippet templates ──────────────────────────────────────────────────────

    private sealed interface Seg
    private data class Txt(val text: String) : Seg
    private data class Var(val name: String, val default: String = name) : Seg

    private class TemplateSpec(val label: String, private val segments: List<Seg>) {
        // A variable name appearing more than once becomes a linked tab stop:
        // first occurrence defines it, later ones mirror it.
        fun build(template: Template) {
            val defined = mutableSetOf<String>()
            segments.forEach { seg ->
                when (seg) {
                    is Txt -> template.addTextSegment(seg.text)
                    is Var ->
                        if (defined.add(seg.name)) template.addVariable(seg.name, TextExpression(seg.default), true)
                        else template.addVariableSegment(seg.name)
                }
            }
        }
    }

    companion object {
        private val structuralTemplates = listOf(
            TemplateSpec(
                "Scenario (template)",
                listOf(
                    Txt("Scenario: "), Var("title"),
                    Txt("\n  Given "), Var("precondition"),
                    Txt("\n  When "), Var("action"),
                    Txt("\n  Then "), Var("outcome"),
                ),
            ),
            TemplateSpec(
                "Scenario Outline (template)",
                listOf(
                    Txt("Scenario Outline: "), Var("title"),
                    Txt("\n  Given step with <"), Var("param"), Txt(">"),
                    Txt("\n  Examples:\n    | "), Var("param"), Txt(" |\n    | "),
                    Var("value"), Txt(" |"),
                ),
            ),
            TemplateSpec(
                "Background (template)",
                listOf(Txt("Background:\n  Given "), Var("precondition", "shared precondition")),
            ),
            TemplateSpec(
                "Feature (template)",
                listOf(
                    Txt("Feature: "), Var("name"),
                    Txt("\n\n  Scenario: "), Var("scenario", "first scenario"),
                    Txt("\n    Given "), Var("precondition"),
                    Txt("\n    When "), Var("action"),
                    Txt("\n    Then "), Var("outcome"),
                ),
            ),
        )
    }
}
