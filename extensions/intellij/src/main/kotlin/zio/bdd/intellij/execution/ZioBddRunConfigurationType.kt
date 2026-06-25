package zio.bdd.intellij.execution

import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationTypeBase
import com.intellij.icons.AllIcons

class ZioBddRunConfigurationType : ConfigurationTypeBase(
    ID, "zio-bdd Scenario", "Run a zio-bdd Gherkin scenario", AllIcons.Actions.Execute,
) {
    override fun getConfigurationFactories(): Array<ConfigurationFactory> =
        arrayOf(ZioBddRunConfigurationFactory(this))

    companion object {
        const val ID = "ZioBddRunConfiguration"
    }
}
