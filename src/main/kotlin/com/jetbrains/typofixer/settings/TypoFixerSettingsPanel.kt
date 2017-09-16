package com.jetbrains.typofixer.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import javax.swing.JFormattedTextField
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextField

class TypoFixerSettingsPanel {

    lateinit var mainPanel: JPanel
    // todo: hardcoded labels (ok? localization?)

    private lateinit var maxResolveDelayField: JFormattedTextField
    private lateinit var maxFreezeTimeField: JFormattedTextField

    private fun JTextField.getTextValue() = text.toLong()
    private fun JTextField.setTextValue(value: Long) {
        text = value.toString()
    }

    // delegates?
    private var maxResolveDelay
        get() = maxResolveDelayField.getTextValue()
        set(v) = maxResolveDelayField.setTextValue(v)

    private var maxFreezeTime
        get() = maxFreezeTimeField.getTextValue()
        set(v) = maxFreezeTimeField.setTextValue(v)

    private val settings get() = TypoFixerSettings.getInstance()

    // todo: resolve for JTextField rolls back
    private fun updateStatisticField(field: JTextField, stat: Int, internalOnly: Boolean = true) {
        if (internalOnly && !ApplicationManager.getApplication().isInternal) {
            field.isVisible = false
        } else {
            field.text = stat.toString()
        }
    }

    private fun updateTextVisibility(field: JLabel) {
        if (!ApplicationManager.getApplication().isInternal) {
            field.isVisible = false
        }
    }

    fun apply() {
        settings.maxMillisForResolve = maxResolveDelay
        settings.maxMillisForFind = maxFreezeTime
    }

    fun isModified() = maxResolveDelay != settings.maxMillisForResolve || maxFreezeTime != settings.maxMillisForFind

    private lateinit var timesResolverCreatedField: JTextField
    private lateinit var timesFindOutOfTimeField: JTextField
    private lateinit var timesResolveOutOfTimeField: JTextField
    private lateinit var successfulResolvesField: JTextField

    private lateinit var timesResolverCreatedText: JLabel
    private lateinit var timesFindOutOfTimeText: JLabel
    private lateinit var timesResolveOutOfTimeText: JLabel

    private val internalFields = mapOf(
            timesResolverCreatedField to TypoFixerStatistics::timesResolverCreated,
            timesFindOutOfTimeField to TypoFixerStatistics::timesFindAbortedBecauseOfTimeLimits,
            timesResolveOutOfTimeField to TypoFixerStatistics::timesResolveAbortedBecauseOfTimeLimits
    )

    private val internalTextFields = listOf(
            timesResolverCreatedText,
            timesFindOutOfTimeText,
            timesResolveOutOfTimeText
    )

    fun refresh() {
        maxResolveDelay = settings.maxMillisForResolve
        maxFreezeTime = settings.maxMillisForFind

        internalFields.forEach { updateStatisticField(it.key, it.value.get()) }
        internalTextFields.forEach { updateTextVisibility(it) }

        updateStatisticField(successfulResolvesField, TypoFixerStatistics.timesWordReplaced, false)
    }
}
