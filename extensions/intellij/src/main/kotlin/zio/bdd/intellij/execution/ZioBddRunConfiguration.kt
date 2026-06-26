package zio.bdd.intellij.execution

import com.intellij.execution.Executor
import com.intellij.execution.configurations.*
import com.intellij.execution.process.ProcessHandlerFactory
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import org.jdom.Element
import java.io.File

class ZioBddRunConfiguration(
    project: Project,
    factory: ConfigurationFactory,
    name: String,
) : LocatableConfigurationBase<RunProfileState>(project, factory, name) {

    var scenarioName: String     = ""
    var featureFilePath: String  = ""
    var suiteName: String        = ""
    var workingDirectory: String = project.basePath ?: ""

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> =
        ZioBddRunConfigurationEditor(project)

    override fun checkConfiguration() {
        if (scenarioName.isBlank() && featureFilePath.isBlank() && suiteName.isBlank())
            throw RuntimeConfigurationWarning("Nothing to run: specify a scenario, feature file, or suite")
    }

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState =
        object : CommandLineState(environment) {
            override fun startProcess() = run {
                val cmdLine = buildCommandLine()
                val handler = ProcessHandlerFactory.getInstance().createColoredProcessHandler(cmdLine)
                ProcessTerminatedListener.attach(handler)
                handler
            }
        }

    private fun buildCommandLine(): GeneralCommandLine {
        val sbt      = resolveSbt()
        val selector = if (suiteName.isNotBlank()) suiteName else "*"
        val testCmd  = when {
            featureFilePath.isNotBlank() && scenarioName.isNotBlank() ->
                """testOnly $selector -- --feature-file "$featureFilePath" --scenario-name "$scenarioName" --focused"""
            featureFilePath.isNotBlank() ->
                """testOnly $selector -- --feature-file "$featureFilePath""""
            suiteName.isNotBlank() ->
                "testOnly $selector"
            else ->
                "test"
        }
        return GeneralCommandLine(sbt, testCmd).withWorkDirectory(workingDirectory)
    }

    private fun resolveSbt(): String {
        val wrapper = File(workingDirectory, "sbt")
        return if (wrapper.exists() && wrapper.canExecute()) wrapper.absolutePath else "sbt"
    }

    override fun readExternal(element: Element) {
        super.readExternal(element)
        scenarioName     = element.getAttributeValue("scenarioName")    ?: ""
        featureFilePath  = element.getAttributeValue("featureFilePath") ?: ""
        suiteName        = element.getAttributeValue("suiteName")       ?: ""
        workingDirectory = element.getAttributeValue("workingDir")      ?: (project.basePath ?: "")
    }

    override fun writeExternal(element: Element) {
        super.writeExternal(element)
        element.setAttribute("scenarioName",    scenarioName)
        element.setAttribute("featureFilePath", featureFilePath)
        element.setAttribute("suiteName",       suiteName)
        element.setAttribute("workingDir",      workingDirectory)
    }
}
