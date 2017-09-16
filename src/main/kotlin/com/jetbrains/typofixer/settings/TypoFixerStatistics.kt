package com.jetbrains.typofixer.settings

import com.intellij.openapi.components.*
import com.intellij.util.xmlb.XmlSerializerUtil

object TypoFixerStatistics {
    private val statisticsComponent get() = TypoFixerStatisticsComponent.getInstance()

    val timesResolverCreated get() = statisticsComponent.timesResolverCreated
    val timesWordReplaced get() = statisticsComponent.timesWordReplaced
    val timesFindAbortedBecauseOfTimeLimits get() = statisticsComponent.timesFindAbortedBecauseOfTimeLimits
    val timesResolveAbortedBecauseOfTimeLimits get() = statisticsComponent.timesResolveAbortedBecauseOfTimeLimits

    fun onTypoResolverCreated() {
        ++statisticsComponent.timesResolverCreated
    }

    fun onWordReplaced() {
        ++statisticsComponent.timesWordReplaced

    }

    fun onFindAbortedBecauseOfTimeLimits() {
        ++statisticsComponent.timesFindAbortedBecauseOfTimeLimits
    }

    fun onResolveAbortedBecauseOfTimeLimits() {
        ++statisticsComponent.timesResolveAbortedBecauseOfTimeLimits
    }
}

// not exact because of concurrency
@State(
        name = "TypoFixerStatistics",
        storages = arrayOf(Storage("other.xml"))
)
class TypoFixerStatisticsComponent : PersistentStateComponent<TypoFixerStatisticsComponent> {

    override fun getState() = this

    override fun loadState(typingCorrectorStatistics: TypoFixerStatisticsComponent) {
        XmlSerializerUtil.copyBean(typingCorrectorStatistics, this)
    }

    companion object {
        fun getInstance(): TypoFixerStatisticsComponent {
            return ServiceManager.getService(TypoFixerStatisticsComponent::class.java)
        }
    }

    var timesResolverCreated: Int = 0
    var timesWordReplaced: Int = 0
    var timesFindAbortedBecauseOfTimeLimits: Int = 0
    var timesResolveAbortedBecauseOfTimeLimits: Int = 0
}