package com.jetbrains.typofixer.settings

import com.intellij.openapi.components.*
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializerUtil

@State(name = "TypoFixerSettings", storages = arrayOf(Storage(StoragePathMacros.WORKSPACE_FILE), Storage("typo_fixer_settings.xml")))
class TypoFixerSettings : PersistentStateComponent<TypoFixerSettings> {

    var maxMillisForFind = 200
    var maxMillisForResolve = 1000

    override fun getState(): TypoFixerSettings? = this

    override fun loadState(typingCorrectorSettings: TypoFixerSettings) {
        XmlSerializerUtil.copyBean(typingCorrectorSettings, this)
    }

    companion object {
        fun getInstance(project: Project): TypoFixerSettings {
            return ServiceManager.getService(project, TypoFixerSettings::class.java)
        }
    }
}
