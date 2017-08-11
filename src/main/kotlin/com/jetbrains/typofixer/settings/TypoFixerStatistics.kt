package com.jetbrains.typofixer.settings

class TypoFixerStatistics {
    var timesResolverCreated: Int = 0
        private set
    var timesWordReplaced: Int = 0
        private set
    var timesRolledBack: Int = 0
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
}