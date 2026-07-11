package zio.bdd.intellij.hints

import com.intellij.codeInsight.hints.declarative.EndOfLinePosition
import com.intellij.codeInsight.hints.declarative.HintFormat
import com.intellij.codeInsight.hints.declarative.InlayHintsCollector
import com.intellij.codeInsight.hints.declarative.InlayHintsProvider
import com.intellij.codeInsight.hints.declarative.InlayTreeSink
import com.intellij.codeInsight.hints.declarative.SharedBypassCollector
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import zio.bdd.intellij.lang.KtStepDefinition
import zio.bdd.intellij.lang.ZioBddStepCache
import zio.bdd.intellij.lang.ZioBddStepMatcher
import zio.bdd.intellij.lang.psi.ZioBddStep

/**
 * Resolves the end-of-line data-flow label for a feature step (#61): match the step to
 * its definition, read that definition's body, and derive what it reads/sets. Pure and
 * platform-free so it can be unit-tested with fake definitions and a fake content reader.
 */
object ZioBddStepDataFlowLabel {

    /** The inlay label for [stepText] under [keyword], or null when no match or no data-flow.
     *  [readContent] resolves a definition file path to its current text (document-backed). */
    fun compute(
        keyword: String,
        stepText: String,
        defs: List<KtStepDefinition>,
        readContent: (String) -> String?,
        // Sibling helper `def`/`val` data-flow for a definition file, so a step that reads/sets via a
        // helper (e.g. `def lastResponse = Stage.get[LastResponse]`) still shows the hint (#57 partial).
        // Defaulted for tests; the collector passes a per-file-memoized resolver.
        readHelpers: (String) -> Map<String, StepDataFlow> = { path ->
            readContent(path)?.let { KtStepDataFlow.resolveHelpers(it) } ?: emptyMap()
        },
    ): String? {
        if (stepText.isBlank()) return null
        val match = ZioBddStepMatcher.candidatesFor(keyword, defs)
            .firstOrNull { ZioBddStepMatcher.matchesStep(stepText, it) }
            ?: return null
        val content = readContent(match.file) ?: return null
        val body = KtStepDataFlow.bodyAt(content, match.line) ?: return null
        return KtStepDataFlow.analyze(body, readHelpers(match.file)).render()
    }
}

/**
 * Declarative inlay provider that shows, at the end of each feature step, what the matched
 * step definition reads/sets from `ScenarioContext` (State) and `Stage` (#61). Registered via
 * `codeInsight.declarativeInlayProvider` with `isEnabledByDefault=false`, so users toggle it in
 * Settings → Editor → Inlay Hints → zio-bdd.
 */
class ZioBddDataFlowInlayProvider : InlayHintsProvider {

    override fun createCollector(file: PsiFile, editor: Editor): InlayHintsCollector? {
        if (DumbService.isDumb(file.project)) return null
        return Collector(file.project, editor.document)
    }

    private class Collector(
        private val project: Project,
        private val document: Document,
    ) : SharedBypassCollector {

        // Definition-file text and its resolved helpers are stable across a collection pass; memoize
        // per file so a feature with many steps in one definition file resolves helpers only once.
        private val contentCache = HashMap<String, String?>()
        private val helpersCache = HashMap<String, Map<String, StepDataFlow>>()

        private fun contentOf(path: String): String? = contentCache.getOrPut(path) { readDocument(path) }

        override fun collectFromElement(element: PsiElement, sink: InlayTreeSink) {
            val step = element as? ZioBddStep ?: return
            // A ZioBddStep may nest text tokens; only act once, on the step node itself.
            if (PsiTreeUtil.getParentOfType(element, ZioBddStep::class.java) != null) return

            val defs = ZioBddStepCache.getInstance(project).getStepDefinitions()
            val label = ZioBddStepDataFlowLabel.compute(
                step.getKeyword(),
                step.getStepText(),
                defs,
                ::contentOf,
            ) { path -> helpersCache.getOrPut(path) { contentOf(path)?.let { KtStepDataFlow.resolveHelpers(it) } ?: emptyMap() } }
                ?: return

            val offset = step.textRange.startOffset
            if (offset < 0 || offset > document.textLength) return
            val line = document.getLineNumber(offset)
            sink.addPresentation(EndOfLinePosition(line), emptyList(), null, HintFormat.default) {
                text(label, null)
            }
        }

        private fun readDocument(path: String): String? {
            val vf = LocalFileSystem.getInstance().findFileByPath(path) ?: return null
            return FileDocumentManager.getInstance().getDocument(vf)?.text
        }
    }
}
