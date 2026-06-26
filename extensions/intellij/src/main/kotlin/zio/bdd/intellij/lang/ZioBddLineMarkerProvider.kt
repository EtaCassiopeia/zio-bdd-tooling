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
        return LineMarkerInfo(
            anchor,
            anchor.textRange,
            AllIcons.Actions.Execute,
            { "Run scenario: $name" },
            { _, leaf ->
                val hdr = leaf.parent as? ZioBddScenarioHeader ?: return@LineMarkerInfo
                if (hdr.isValid) ZioBddRunConfigurationProducer().runScenario(hdr.project, hdr)
            },
            GutterIconRenderer.Alignment.LEFT,
            { "Run scenario: $name" },
        )
    }
}
