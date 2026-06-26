package zio.bdd.intellij.execution

import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.LazyRunConfigurationProducer
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Ref
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import zio.bdd.intellij.lang.ZioBddStepCache
import zio.bdd.intellij.lang.psi.ZioBddFeatureHeader
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
        val featureFile = scenario.containingFile?.virtualFile
        configuration.scenarioName     = scenario.getScenarioName()
        configuration.featureFilePath  = featureFile?.path ?: ""
        configuration.suiteName        = if (featureFile != null)
            ZioBddStepCache.getInstance(context.project).suiteNamesForFeature(featureFile) else "*"
        configuration.workingDirectory = context.project.basePath ?: ""
        configuration.name             = "Run: ${scenario.getScenarioName()}"
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
        val featureFile = scenario.containingFile?.virtualFile
        val suite = if (featureFile != null)
            ZioBddStepCache.getInstance(project).suiteNamesForFeature(featureFile) else "*"
        runScenario(project, scenario.getScenarioName(), featureFile?.path ?: "", suite)
    }

    fun runScenario(project: Project, scenarioName: String, featureFile: VirtualFile, suiteSelector: String) {
        runScenario(project, scenarioName, featureFile.path, suiteSelector)
    }

    fun runFeature(project: Project, featureFile: VirtualFile, featureName: String) {
        val suite = ZioBddStepCache.getInstance(project).suiteNamesForFeature(featureFile)
        runFeatureInternal(project, featureFile.path, featureName, suite)
    }

    fun runFeature(project: Project, featureFile: VirtualFile, featureName: String, suiteSelector: String) {
        runFeatureInternal(project, featureFile.path, featureName, suiteSelector)
    }

    fun runAll(project: Project) {
        val settings = makeSettings(project, "Run All Tests")
        (settings.configuration as ZioBddRunConfiguration).apply {
            suiteName        = "*"
            workingDirectory = project.basePath ?: ""
        }
        launch(project, settings)
    }

    fun runSuite(project: Project, suiteSelector: String, displayName: String) {
        val settings = makeSettings(project, "Run Suite: $displayName")
        (settings.configuration as ZioBddRunConfiguration).apply {
            suiteName        = suiteSelector
            workingDirectory = project.basePath ?: ""
        }
        launch(project, settings)
    }

    fun findFeatureHeader(element: PsiElement?): ZioBddFeatureHeader? {
        if (element == null) return null
        if (element is ZioBddFeatureHeader) return element
        return PsiTreeUtil.getParentOfType(element, ZioBddFeatureHeader::class.java)
    }

    private fun runScenario(project: Project, scenarioName: String, featureFilePath: String, suiteSelector: String) {
        val settings = makeSettings(project, "Run: $scenarioName")
        (settings.configuration as ZioBddRunConfiguration).apply {
            this.scenarioName    = scenarioName
            this.featureFilePath = featureFilePath
            this.suiteName       = suiteSelector
            workingDirectory     = project.basePath ?: ""
        }
        launch(project, settings)
    }

    private fun runFeatureInternal(project: Project, featureFilePath: String, featureName: String, suiteSelector: String) {
        val settings = makeSettings(project, "Run: $featureName")
        (settings.configuration as ZioBddRunConfiguration).apply {
            this.featureFilePath = featureFilePath
            suiteName            = suiteSelector
            workingDirectory     = project.basePath ?: ""
        }
        launch(project, settings)
    }

    private fun makeSettings(project: Project, name: String) =
        RunManager.getInstance(project).createConfiguration(name, getConfigurationFactory())

    private fun launch(project: Project, settings: com.intellij.execution.RunnerAndConfigurationSettings) {
        val mgr = RunManager.getInstance(project)
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
