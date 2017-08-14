package com.jetbrains.typofixer.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.jetbrains.typofixer.statistics
import javax.swing.JFormattedTextField
import javax.swing.JLabel
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

    private val settings
        get() = TypoFixerSettings.getInstance(project)
    private val statistics
        get() = project.statistics

    // todo: resolve for JTextField rolls back
    private fun updateStaticticField(field: JTextField?, stat: Int, internalOnly: Boolean = true) {
        if (internalOnly && !ApplicationManager.getApplication().isInternal) {
            field!!.isVisible = false
        } else {
            field!!.text = stat.toString()
        }
    }

    private fun updateTextVisibility(field: JLabel?) {
        if (!ApplicationManager.getApplication().isInternal) {
            field!!.isVisible = false
        }
    }

    fun apply() {
        settings.maxMillisForResolve = maxResolveDelay
        settings.maxMillisForFind = maxFreezeTime
    }

    fun isModified() = maxResolveDelay != settings.maxMillisForResolve || maxFreezeTime != settings.maxMillisForFind

    private var timesResolverCreatedField: JTextField? = null
    private var timesWordReplacedField: JTextField? = null
    private var timesRolledBackField: JTextField? = null
    private var timesFindOutOfTimeField: JTextField? = null
    private var timesResolveOutOfTimeField: JTextField? = null
    private var successfulResolvesField: JTextField? = null

    private var timesResolverCreatedText: JLabel? = null
    private var timesWordReplacedText: JLabel? = null
    private var timesRolledBackText: JLabel? = null
    private var timesFindOutOfTimeText: JLabel? = null
    private var timesResolveOutOfTimeText: JLabel? = null

    fun refresh() {
        maxResolveDelay = settings.maxMillisForResolve
        maxFreezeTime = settings.maxMillisForFind

        updateStaticticField(timesResolverCreatedField, statistics.timesResolverCreated)
        updateStaticticField(timesWordReplacedField, statistics.timesWordReplaced)
        updateStaticticField(timesRolledBackField, statistics.timesRolledBack)
        updateStaticticField(timesFindOutOfTimeField, statistics.timesFindAbortedBecauseOfTimeLimits)
        updateStaticticField(timesResolveOutOfTimeField, statistics.timesResolveAbortedBecauseOfTimeLimits)
        updateStaticticField(successfulResolvesField, statistics.timesWordReplaced - statistics.timesRolledBack, false)

        updateTextVisibility(timesResolverCreatedText)
        updateTextVisibility(timesWordReplacedText)
        updateTextVisibility(timesRolledBackText)
        updateTextVisibility(timesFindOutOfTimeText)
        updateTextVisibility(timesResolveOutOfTimeText)
    }
}
