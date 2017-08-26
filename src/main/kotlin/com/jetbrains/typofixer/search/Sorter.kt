package com.jetbrains.typofixer.search

import com.jetbrains.typofixer.search.distance.Distance


class Sorter(private val distanceProvider: Distance) {

    private fun comparator(base: String) = Comparator<FoundWord> { left: FoundWord, right: FoundWord ->
        val leftMeasure = distanceProvider.measure(base, left.word)
        val rightMeasure = distanceProvider.measure(base, right.word)
        if (leftMeasure != rightMeasure) leftMeasure.compareTo(rightMeasure)
        else left.type.compareTo(right.type)
    }

    fun sort(values: Sequence<FoundWord>, base: String) = values.sortedWith(comparator(base))

}