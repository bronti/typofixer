package com.jetbrains.typofixer.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import org.jetbrains.annotations.Nls

class TypoFixerConfigurable(val project: Project) : Configurable {
    private var mySettingsPanel: TypoFixerSettingsPanel? = TypoFixerSettingsPanel(project)

    @Nls //todo: plugin name?
    override fun getDisplayName() = "Typo Fixer plugin settigns"

    override fun getHelpTopic() = null

    override fun createComponent() = mySettingsPanel!!.mainPanel

    override fun isModified() = mySettingsPanel!!.isModified()

    override fun apply() = mySettingsPanel!!.apply()

    override fun reset() = mySettingsPanel!!.refresh()

    override fun disposeUIResources() {
        mySettingsPanel = null
    }
}
