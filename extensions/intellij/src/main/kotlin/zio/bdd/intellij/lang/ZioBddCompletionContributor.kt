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

    // IntelliJ only auto-opens the completion popup while typing identifier
    // characters; "@" is not one, so tag completion never appeared on typing.
    // Open it explicitly so "@" behaves like the LSP's "@" trigger character.
    override fun invokeAutoPopup(position: PsiElement, typeChar: Char): Boolean =
        typeChar == '@'

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
            val cache   = ZioBddStepCache.getInstance(project)
            // The cache scans Scala sources asynchronously and is empty on first
            // access, so the first completion would show nothing. Block on a fast
            // source-only warm (no BSP subprocess) so suggestions appear immediately.
            cache.ensureStaticWarmed()
            val defs    = cache.getStepDefinitions()

            // The lexer makes the whole step line a single token, so IntelliJ's
            // default prefix is just the word at the caret — the full step-text
            // lookups ("the cart has …") would be filtered out because they don't
            // start with that word. Match against the step text typed after the
            // keyword instead.
            val typed   = stepTextBeforeCaret(parameters, keyword)
            val matched = result.withPrefixMatcher(typed)

            candidatesFor(keyword, defs).forEach { def ->
                matched.addElement(
                    LookupElementBuilder.create(def.displayText)
                        .withIcon(AllIcons.Nodes.Function)
                        .withTypeText(def.file.substringAfterLast('/'))
                        .withInsertHandler { ctx, _ -> insertStepTemplate(ctx, def.displayText) }
                )
            }
        }

        // The lookup shows the step's display text with "{int}"/"{double}" markers,
        // but inserting those literally matches no step definition. Replace each
        // "{type}" with an example value as a live-template tab stop so the inserted
        // step actually matches (and the user can tab through the values).
        private fun insertStepTemplate(ctx: InsertionContext, displayText: String) {
            ctx.document.deleteString(ctx.startOffset, ctx.tailOffset)
            ctx.commitDocument()
            ctx.editor.caretModel.moveToOffset(ctx.startOffset)
            val manager  = TemplateManager.getInstance(ctx.project)
            val template = manager.createTemplate("", "")
            template.isToReformat = false
            val placeholder = Regex("""\{([^}]+)}""")
            var last = 0
            var n    = 0
            placeholder.findAll(displayText).forEach { m ->
                template.addTextSegment(displayText.substring(last, m.range.first))
                template.addVariable("p${n++}", TextExpression(exampleValue(m.groupValues[1])), true)
                last = m.range.last + 1
            }
            template.addTextSegment(displayText.substring(last))
            manager.startTemplate(ctx.editor, template)
        }

        private fun exampleValue(type: String): String = when (type.lowercase()) {
            "int", "long", "bigint"                    -> "42"
            "double", "float", "decimal", "bigdecimal" -> "9.99"
            "boolean"                                  -> "true"
            "string", "word"                           -> "value"
            else                                       -> type
        }

        private fun stepTextBeforeCaret(parameters: CompletionParameters, keyword: String): String {
            val doc        = parameters.editor.document
            val offset     = parameters.offset
            val lineStart  = doc.getLineStartOffset(doc.getLineNumber(offset))
            val linePrefix = doc.getText(TextRange(lineStart, offset)).trimStart()
            return linePrefix.removePrefix(keyword).trimStart()
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
            // Step keywords — offered on a blank/partial line so a step can be started
            // (e.g. typing "Th" offers "Then"). A trailing space is added so step
            // definition completion can follow immediately.
            listOf("Given", "When", "Then", "And", "But").filter { keep(it) }.forEach { kw ->
                result.addElement(
                    LookupElementBuilder.create(kw).bold().withInsertHandler { ctx, _ ->
                        val tail = ctx.tailOffset
                        if (tail >= ctx.document.textLength || ctx.document.charsSequence[tail] != ' ')
                            ctx.document.insertString(tail, " ")
                        ctx.commitDocument()
                        ctx.editor.caretModel.moveToOffset(tail + 1)
                    },
                )
            }
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
