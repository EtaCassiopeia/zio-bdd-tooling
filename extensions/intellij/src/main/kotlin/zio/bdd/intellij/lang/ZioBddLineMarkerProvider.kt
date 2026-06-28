package zio.bdd.intellij.lang

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.psi.PsiElement
import com.intellij.ui.awt.RelativePoint
import zio.bdd.intellij.execution.ZioBddRunConfigurationProducer
import zio.bdd.intellij.lang.psi.ZioBddFeatureHeader
import zio.bdd.intellij.lang.psi.ZioBddScenarioHeader
import java.awt.event.MouseEvent

class ZioBddLineMarkerProvider : LineMarkerProvider {

    // IntelliJ requires markers to be anchored to leaf elements (no children).
    // We attach to the first child (KEYWORD token) of each header composite node.
    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        if (element.firstChild != null) return null       // not a leaf
        val parent = element.parent ?: return null
        if (element != parent.firstChild) return null     // only the first token per header
        return when {
            parent is ZioBddFeatureHeader                              -> createFeatureMarker(parent, element)
            parent is ZioBddScenarioHeader && !parent.isBackground()  -> createScenarioMarker(parent, element)
            else                                                       -> null
        }
    }

    private fun createFeatureMarker(feature: ZioBddFeatureHeader, anchor: PsiElement): LineMarkerInfo<PsiElement> {
        val name = feature.getFeatureName()
        return LineMarkerInfo(
            anchor,
            anchor.textRange,
            AllIcons.Actions.Execute,
            { "Run feature: $name" },
            { _, leaf ->
                val hdr = leaf.parent as? ZioBddFeatureHeader ?: return@LineMarkerInfo
                if (hdr.isValid) {
                    val vf = hdr.containingFile?.virtualFile ?: return@LineMarkerInfo
                    ZioBddRunConfigurationProducer().runFeature(hdr.project, vf, name)
                }
            },
            GutterIconRenderer.Alignment.LEFT,
            { "Run feature: $name" },
        )
    }

    private fun createScenarioMarker(scenario: ZioBddScenarioHeader, anchor: PsiElement): LineMarkerInfo<PsiElement> {
        val name = scenario.getScenarioName()
        // For a Scenario Outline with ≥2 example rows the gutter opens a chooser so a
        // single Example can be run; otherwise it runs the scenario directly.
        val examples =
            if (scenario.isOutline()) OutlineExamples.exampleNames(scenario.containingFile?.text ?: "", name)
            else emptyList()
        val tooltip =
            if (examples.size > 1) "Run outline: $name (${examples.size} examples)" else "Run scenario: $name"
        return LineMarkerInfo(
            anchor,
            anchor.textRange,
            AllIcons.Actions.Execute,
            { tooltip },
            { event, leaf ->
                val hdr = leaf.parent as? ZioBddScenarioHeader ?: return@LineMarkerInfo
                if (!hdr.isValid) return@LineMarkerInfo
                if (examples.size > 1) showRunChoices(hdr, name, examples, event)
                else ZioBddRunConfigurationProducer().runScenario(hdr.project, hdr)
            },
            GutterIconRenderer.Alignment.LEFT,
            { tooltip },
        )
    }

    // Chooser offering "Run outline (all rows)" plus one entry per Example. Each
    // Example runs with its exact `<outline> - Example N` name (no glob), so just
    // that row executes; "Run outline" keeps the all-rows glob.
    private fun showRunChoices(
        scenario: ZioBddScenarioHeader,
        outlineName: String,
        examples: List<String>,
        event: MouseEvent?,
    ) {
        val featureFile = scenario.containingFile?.virtualFile ?: return
        val suite = ZioBddStepCache.getInstance(scenario.project).suiteNamesForFeature(featureFile)
        val producer = ZioBddRunConfigurationProducer()
        val runAll = "▶ Run outline (${examples.size} examples)"
        val choices = listOf(runAll) + examples.map { "▶ Run ${it.substringAfterLast(" - ")}" }
        val popup = JBPopupFactory.getInstance()
            .createPopupChooserBuilder(choices)
            .setTitle("Run $outlineName")
            .setItemChosenCallback { choice ->
                val idx = choices.indexOf(choice)
                if (idx <= 0) producer.runScenario(scenario.project, scenario)
                else producer.runScenario(scenario.project, examples[idx - 1], featureFile, suite)
            }
            .createPopup()
        if (event != null) popup.show(RelativePoint(event)) else popup.showInFocusCenter()
    }
}
