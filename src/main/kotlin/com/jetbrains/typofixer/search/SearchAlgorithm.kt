package com.jetbrains.typofixer.search

import com.jetbrains.typofixer.search.distance.DamerauLevenshteinDistance
import com.jetbrains.typofixer.search.index.CombinedIndex

/**
 * @author bronti.
 */

abstract class SearchAlgorithm(val maxError: Int, val distance: DamerauLevenshteinDistance, protected val index: CombinedIndex) {

    protected abstract fun getSignatures(str: String): List<Set<Int>>

    protected abstract fun findClosest(str: String, type: CombinedIndex.WordType, isTooLate: () -> Boolean, currentBestError: Int): SearchResultsBuilder

    // order in wordTypes matters
    fun findClosest(str: String, types: Array<CombinedIndex.WordType>, isTooLate: () -> Boolean): Sequence<String> {
        val result = types.fold(getEmptyResultBuilder(str, maxError)) { acc, type ->
            if (isTooLate()) return emptySequence()
            acc.combinedWith(findClosest(str, type, isTooLate, acc.error))
        }
        assert(result.isActive)
        return result.result
    }

    protected fun getEmptyResultBuilder(str: String, maxError: Int)
            = SearchResultsBuilder(maxError, { distance.roughMeasure(str, it) })
}

abstract class DLSearchAlgorithmBase(maxError: Int, index: CombinedIndex)
    : SearchAlgorithm(maxError, DamerauLevenshteinDistance(maxError), index) {

    // todo: candidates count
    override fun findClosest(str: String, type: CombinedIndex.WordType, isTooLate: () -> Boolean, currentBestError: Int): SearchResultsBuilder {
        val signaturesByError = getSignatures(str)

        // todo: it -> if in {it: ...}
        val result = (0..currentBestError).fold(getEmptyResultBuilder(str, currentBestError)) { acc, error ->
            if (isTooLate()) return acc

            val signatures = signaturesByError[error]
            val candidates = index.getAll(type, signatures)
            acc.combinedWith(error, candidates)
        }
        return result
    }
}

class DLSearchAlgorithm(maxError: Int, index: CombinedIndex) : DLSearchAlgorithmBase(maxError, index) {
    override fun getSignatures(str: String) = index.signature.getRange(str, maxError)
}

class DLPreciseSearchAlgorithm(maxError: Int, index: CombinedIndex) : DLSearchAlgorithmBase(maxError, index) {
    // todo: optimize precise
    override fun getSignatures(str: String) = index.signature.getRange(str, 2 * maxError)
}