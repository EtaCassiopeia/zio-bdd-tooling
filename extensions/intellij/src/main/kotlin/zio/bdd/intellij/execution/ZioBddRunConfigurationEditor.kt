package zio.bdd.intellij.execution

import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import javax.swing.JComponent

class ZioBddRunConfigurationEditor(project: Project) : SettingsEditor<ZioBddRunConfiguration>() {

    private val scenarioNameField  = JBTextField()
    private val featureFileField   = JBTextField()
    private val suiteNameField     = JBTextField()
    private val workingDirField    = JBTextField(project.basePath ?: "")

    override fun resetEditorFrom(s: ZioBddRunConfiguration) {
        scenarioNameField.text = s.scenarioName
        featureFileField.text  = s.featureFilePath
        suiteNameField.text    = s.suiteName
        workingDirField.text   = s.workingDirectory
    }

    override fun applyEditorTo(s: ZioBddRunConfiguration) {
        s.scenarioName     = scenarioNameField.text
        s.featureFilePath  = featureFileField.text
        s.suiteName        = suiteNameField.text
        s.workingDirectory = workingDirField.text
    }

    override fun createEditor(): JComponent =
        FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel("Scenario name:"),    scenarioNameField)
            .addLabeledComponent(JBLabel("Feature file:"),     featureFileField)
            .addLabeledComponent(JBLabel("Suite selector:"),   suiteNameField)
            .addLabeledComponent(JBLabel("Working directory:"), workingDirField)
            .panel
}
