package com.jetbrains.typofixer.settings

import com.intellij.openapi.components.*
import com.intellij.util.xmlb.XmlSerializerUtil


@State(
        name = "TypoFixerSettings",
        storages = arrayOf(Storage("other.xml"))
)
class TypoFixerSettings : PersistentStateComponent<TypoFixerSettings> {

    override fun getState() = this

    override fun loadState(typingCorrectorSettings: TypoFixerSettings) {
        XmlSerializerUtil.copyBean(typingCorrectorSettings, this)
    }

    companion object {
        fun getInstance(): TypoFixerSettings {
            return ServiceManager.getService(TypoFixerSettings::class.java)
        }
    }

    var maxMillisForFind = 50L
    var maxMillisForResolve = 400L
}
