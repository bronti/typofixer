package com.jetbrains.typofixer.settings

// not exact because of concurrency
class TypoFixerStatistics {
    var timesResolverCreated: Int = 0
        private set
    var timesWordReplaced: Int = 0
        private set
    var timesRolledBack: Int = 0
        private set
    var timesFindAbortedBecauseOfTimeLimits: Int = 0
        private set
    var timesResolveAbortedBecauseOfTimeLimits: Int = 0
        private set


    fun onTypoResolverCreated() {
        ++timesResolverCreated
    }

    fun onWordReplaced() {
        ++timesWordReplaced
    }

    fun onReplacementRolledBack() {
        ++timesRolledBack
    }

    fun onFindAbortedBecauseOfTimeLimits() {
        ++timesFindAbortedBecauseOfTimeLimits
    }

    fun onResolveAbortedBecauseOfTimeLimits() {
        ++timesResolveAbortedBecauseOfTimeLimits
    }
}