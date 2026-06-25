package zio.bdd.intellij.execution

import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.LazyRunConfigurationProducer
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import zio.bdd.intellij.lang.psi.ZioBddScenarioHeader

class ZioBddRunConfigurationProducer : LazyRunConfigurationProducer<ZioBddRunConfiguration>() {

    override fun getConfigurationFactory() =
        ZioBddRunConfigurationFactory(ZioBddRunConfigurationType())

    override fun setupConfigurationFromContext(
        configuration: ZioBddRunConfiguration,
        context: ConfigurationContext,
        sourceElement: Ref<PsiElement>,
    ): Boolean {
        val scenario = findScenario(context.psiLocation) ?: return false
        configuration.scenarioName    = scenario.getScenarioName()
        configuration.featureFilePath = scenario.containingFile?.virtualFile?.path ?: ""
        configuration.workingDirectory = context.project.basePath ?: ""
        configuration.name = "Run: ${scenario.getScenarioName()}"
        sourceElement.set(scenario)
        return true
    }

    override fun isConfigurationFromContext(
        configuration: ZioBddRunConfiguration,
        context: ConfigurationContext,
    ): Boolean {
        val scenario = findScenario(context.psiLocation) ?: return false
        return configuration.scenarioName == scenario.getScenarioName()
    }

    fun runScenario(project: Project, scenario: ZioBddScenarioHeader) {
        val factory  = ZioBddRunConfigurationFactory(ZioBddRunConfigurationType())
        val mgr      = RunManager.getInstance(project)
        val settings = mgr.createConfiguration("Run: ${scenario.getScenarioName()}", factory)
        (settings.configuration as ZioBddRunConfiguration).apply {
            scenarioName    = scenario.getScenarioName()
            featureFilePath = scenario.containingFile?.virtualFile?.path ?: ""
            workingDirectory = project.basePath ?: ""
        }
        mgr.addConfiguration(settings)
        mgr.selectedConfiguration = settings
        ProgramRunnerUtil.executeConfiguration(settings, DefaultRunExecutor.getRunExecutorInstance())
    }

    private fun findScenario(element: PsiElement?): ZioBddScenarioHeader? {
        if (element == null) return null
        if (element is ZioBddScenarioHeader) return element
        return PsiTreeUtil.getParentOfType(element, ZioBddScenarioHeader::class.java)
    }
}
