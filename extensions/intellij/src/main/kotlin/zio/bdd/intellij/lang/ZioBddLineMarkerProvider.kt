package zio.bdd.intellij.lang

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.psi.PsiElement
import zio.bdd.intellij.execution.ZioBddRunConfigurationProducer
import zio.bdd.intellij.lang.psi.ZioBddFeatureHeader
import zio.bdd.intellij.lang.psi.ZioBddScenarioHeader

class ZioBddLineMarkerProvider : LineMarkerProvider {
    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? = when {
        element is ZioBddFeatureHeader                         -> createFeatureMarker(element)
        element is ZioBddScenarioHeader && !element.isBackground() -> createScenarioMarker(element)
        else                                                   -> null
    }

    private fun createFeatureMarker(feature: ZioBddFeatureHeader): LineMarkerInfo<ZioBddFeatureHeader> {
        val name = feature.getFeatureName()
        return LineMarkerInfo(
            feature,
            feature.textRange,
            AllIcons.Actions.Execute,
            { "Run feature: $name" },
            { _, el ->
                if (el.isValid) {
                    val vf = el.containingFile?.virtualFile ?: return@LineMarkerInfo
                    ZioBddRunConfigurationProducer().runFeature(el.project, vf, name)
                }
            },
            GutterIconRenderer.Alignment.LEFT,
            { "Run feature: $name" },
        )
    }

    private fun createScenarioMarker(scenario: ZioBddScenarioHeader): LineMarkerInfo<ZioBddScenarioHeader> {
        val name = scenario.getScenarioName()
        return LineMarkerInfo(
            scenario,
            scenario.textRange,
            AllIcons.Actions.Execute,
            { "Run scenario: $name" },
            { _, el -> if (el.isValid) ZioBddRunConfigurationProducer().runScenario(el.project, el) },
            GutterIconRenderer.Alignment.LEFT,
            { "Run scenario: $name" },
        )
    }
}
