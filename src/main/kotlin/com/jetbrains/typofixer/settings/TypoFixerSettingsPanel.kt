package com.jetbrains.typofixer.settings

import com.intellij.openapi.project.Project
import com.jetbrains.typofixer.statistics
import javax.swing.JFormattedTextField
import javax.swing.JPanel
import javax.swing.JTextField

class TypoFixerSettingsPanel(val project: Project) {
    var mainPanel: JPanel? = null
    // todo: hardcoded labels (ok? localization?)

    private var maxResolveDelayField: JFormattedTextField? = null
    private var maxFreezeTimeField: JFormattedTextField? = null

    private fun JTextField.getTextValue() = text.toInt()
    private fun JTextField.setTextValue(value: Int) {
        text = value.toString()
    }

    // delegates?
    private var maxResolveDelay
        get() = maxResolveDelayField!!.getTextValue()
        set(v) = maxResolveDelayField!!.setTextValue(v)

    private var maxFreezeTime
        get() = maxFreezeTimeField!!.getTextValue()
        set(v) = maxFreezeTimeField!!.setTextValue(v)

    private var timesResolverCreatedField: JTextField? = null
    private var timesWordReplacedField: JTextField? = null
    private var timesRolledBackField: JTextField? = null

    private val settings
        get() = TypoFixerSettings.getInstance(project)
    private val statistics
        get() = project.statistics

    fun apply() {
        settings.maxMillisForResolve = maxResolveDelay
        settings.maxMillisForFind = maxFreezeTime
    }

    fun refresh() {
        maxResolveDelay = settings.maxMillisForResolve
        maxFreezeTime = settings.maxMillisForFind

        timesResolverCreatedField!!.text = statistics.timesResolverCreated.toString()
        timesWordReplacedField!!.text = statistics.timesWordReplaced.toString()
        timesRolledBackField!!.text = statistics.timesRolledBack.toString()
    }

    fun isModified() = maxResolveDelay != settings.maxMillisForResolve || maxFreezeTime != settings.maxMillisForFind
}
