package zio.bdd.intellij.execution

import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.openapi.project.Project

class ZioBddRunConfigurationFactory(type: ZioBddRunConfigurationType) : ConfigurationFactory(type) {
    override fun getId(): String = ZioBddRunConfigurationType.ID
    override fun createTemplateConfiguration(project: Project): RunConfiguration =
        ZioBddRunConfiguration(project, this, "zio-bdd Scenario")
}
