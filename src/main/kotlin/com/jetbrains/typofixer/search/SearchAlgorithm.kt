package com.jetbrains.typofixer.search

import com.jetbrains.typofixer.search.distance.DamerauLevenshteinDistanceTo
import com.jetbrains.typofixer.search.distance.DistanceTo
import com.jetbrains.typofixer.search.index.CombinedIndex
import com.jetbrains.typofixer.search.index.GlobalInnerIndexBase
import org.jetbrains.annotations.TestOnly

/**
 * @author bronti.
 */

abstract class SearchAlgorithm(val maxError: Int, val getDistanceTo: (String) -> DistanceTo, val index: CombinedIndex) {

    protected abstract fun getSignatures(str: String): List<Set<Int>>

    // order in wordTypes matters
    abstract fun findClosest(str: String, wordTypes: Array<CombinedIndex.WordType>, isTooLate: () -> Boolean): SearchResults

    @TestOnly
    fun getCandidates(str: String): List<String> {
        return getSignatures(str).flatMap { index.getAltogether(it) }
    }

    @TestOnly
    fun simpleSearch(str: String): List<String> {
        val distance = getDistanceTo(str)
        return getCandidates(str).filter { distance.measure(it) <= maxError }
    }

    @TestOnly
    fun search(str: String): Map<Double, List<String>> {
        val distance = getDistanceTo(str)
        return getCandidates(str).groupBy { it: String -> distance.measure(it) }.filter { it.key <= maxError }
    }
}

abstract class DLSearchAlgorithmBase(maxError: Int, index: CombinedIndex)
    : SearchAlgorithm(maxError, { it: String -> DamerauLevenshteinDistanceTo(it, maxError) }, index) {

    val EMPTY_RESULT = SearchResults(maxError)

    private fun List<String>.findBestBy(measure: (String) -> Double): Pair<List<String>, Double> {
        var minError: Double? = null
        val result = this.map {
            val currentMeasure = measure(it)
            if (minError == null || minError!! > currentMeasure) {
                minError = currentMeasure
            }
            it to currentMeasure
        }.filter { it.second == minError }.map { it.first }
        val error = minError ?: maxError + 1.0
        // todo: fix to -> do when error in listOf()  (?)
        if (minError == null || minError!! > error || result.isEmpty()) return listOf<String>() to maxError + 1.0
        return result to minError!!
    }

    // todo: candidates count
    override fun findClosest(
            str: String,
            wordTypes: Array<CombinedIndex.WordType>,
            isTooLate: () -> Boolean): SearchResults {

        val distance = getDistanceTo(str)
        val signaturesByError = getSignatures(str)
        var realCandidatesCount = 0

        val resultsByType = wordTypes.map { it to SearchResults(maxError) }.associateBy({ it.first }, { it.second }).toMutableMap()

        for (error in signaturesByError.indices) {
            val signatures = signaturesByError[error]

            fun getResultOfType(type: CombinedIndex.WordType): SearchResults {
                val candidates = index.getAll(type, signatures)
                // todo: isTooLate (?)
                val (bestWords, bestError) = candidates.filter { it != str }.findBestBy { distance.measure(it) }
                realCandidatesCount += candidates.size
                assert(bestError >= error) // otherwise something is wrong with index and/or signature
                return SearchResults(maxError, bestError, bestWords.map { WordFromResult(it, type) })
            }

            try {
                for (type in wordTypes) {
                    resultsByType[type] = resultsByType[type]!! + getResultOfType(type)
                    if (isTooLate()) {
                        break
                    }
                }
            } catch (e: GlobalInnerIndexBase.TriedToAccessIndexWhileItIsRefreshing) {
                return EMPTY_RESULT
            }
        }

        return wordTypes.fold(EMPTY_RESULT) { acc, tp -> acc + resultsByType[tp]!! }
    }
}

class DLSearchAlgorithm(maxError: Int, index: CombinedIndex) : DLSearchAlgorithmBase(maxError, index) {
    override fun getSignatures(str: String) = index.signature.getRange(str, maxError)
}

class DLPreciseSearchAlgorithm(maxError: Int, index: CombinedIndex) : DLSearchAlgorithmBase(maxError, index) {
    // todo: optimize precise
    override fun getSignatures(str: String) = index.signature.getRange(str, 2 * maxError)
}