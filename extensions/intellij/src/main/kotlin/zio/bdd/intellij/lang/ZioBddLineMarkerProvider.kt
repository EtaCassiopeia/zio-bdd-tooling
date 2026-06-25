package zio.bdd.intellij.lang

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.psi.PsiElement
import zio.bdd.intellij.execution.ZioBddRunConfigurationProducer
import zio.bdd.intellij.lang.psi.ZioBddScenarioHeader

class ZioBddLineMarkerProvider : LineMarkerProvider {
    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        if (element !is ZioBddScenarioHeader) return null
        if (element.isBackground()) return null
        return createMarker(element)
    }

    private fun createMarker(scenario: ZioBddScenarioHeader): LineMarkerInfo<ZioBddScenarioHeader> {
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
