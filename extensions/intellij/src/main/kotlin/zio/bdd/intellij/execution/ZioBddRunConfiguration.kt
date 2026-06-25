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

    var scenarioName: String   = ""
    var featureFilePath: String = ""
    var workingDirectory: String = project.basePath ?: ""

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> =
        ZioBddRunConfigurationEditor(project)

    override fun checkConfiguration() {
        if (scenarioName.isBlank()) throw RuntimeConfigurationWarning("Scenario name is empty")
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
        val sbt = resolveSbt()
        val testCmd = if (scenarioName.isNotBlank())
            """testOnly * -- -t "${scenarioName}""""
        else
            "test"
        return GeneralCommandLine(sbt, testCmd)
            .withWorkDirectory(workingDirectory)
    }

    private fun resolveSbt(): String {
        val wrapper = File(workingDirectory, "sbt")
        return if (wrapper.exists() && wrapper.canExecute()) wrapper.absolutePath else "sbt"
    }

    override fun readExternal(element: Element) {
        super.readExternal(element)
        scenarioName    = element.getAttributeValue("scenarioName")    ?: ""
        featureFilePath = element.getAttributeValue("featureFilePath") ?: ""
        workingDirectory = element.getAttributeValue("workingDir")     ?: (project.basePath ?: "")
    }

    override fun writeExternal(element: Element) {
        super.writeExternal(element)
        element.setAttribute("scenarioName",    scenarioName)
        element.setAttribute("featureFilePath", featureFilePath)
        element.setAttribute("workingDir",      workingDirectory)
    }
}
