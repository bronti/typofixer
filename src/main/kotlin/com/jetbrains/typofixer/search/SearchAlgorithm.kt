package com.jetbrains.typofixer.search

import com.jetbrains.typofixer.search.distance.DamerauLevenshteinDistance
import com.jetbrains.typofixer.search.index.CombinedIndex
import com.jetbrains.typofixer.search.index.GlobalInnerIndexBase

/**
 * @author bronti.
 */

abstract class SearchAlgorithm(val maxError: Int, val distance: DamerauLevenshteinDistance, protected val index: CombinedIndex) {

    protected abstract fun getSignatures(str: String): List<Set<Int>>

    protected abstract fun findClosest(str: String, type: CombinedIndex.WordType, isTooLate: () -> Boolean, currentBestError: Int): SearchResults

    // order in wordTypes matters
    fun findClosest(str: String, types: Array<CombinedIndex.WordType>, isTooLate: () -> Boolean): SearchResults {
        return types.fold(getEmptyResult()) { acc, type ->
            if (isTooLate()) return getEmptyResult()
            acc.combinedWith(findClosest(str, type, isTooLate, acc.error))
        }
    }

    fun getEmptyResult() = SearchResults(maxError, maxError, emptySequence())
}

class ResolveAbortedException : RuntimeException()

abstract class DLSearchAlgorithmBase(maxError: Int, index: CombinedIndex)
    : SearchAlgorithm(maxError, DamerauLevenshteinDistance(maxError), index) {

    private fun getEmptyResultBuilder(str: String, maxError: Int, type: CombinedIndex.WordType)
            = SearchResultsBuilder(maxError, { distance.roughMeasure(str, it) }, type)

    // todo: candidates count
    override fun findClosest(str: String, type: CombinedIndex.WordType, isTooLate: () -> Boolean, currentBestError: Int): SearchResults {
        val signaturesByError = getSignatures(str)

        // todo: it -> if in {it: ...}
        val result = (0..currentBestError).fold(getEmptyResultBuilder(str, currentBestError, type)) { acc: SearchResultsBuilder, error ->
            if (isTooLate()) return acc.getResults()

            val signatures = signaturesByError[error]

            val candidates = try {
                index.getAll(type, signatures)
            } catch(e: GlobalInnerIndexBase.TriedToAccessIndexWhileItIsRefreshing) {
                throw ResolveAbortedException()
            }

            acc.combinedWith(error, candidates)
        }
        return result.getResults()
    }
}

class DLSearchAlgorithm(maxError: Int, index: CombinedIndex) : DLSearchAlgorithmBase(maxError, index) {
    override fun getSignatures(str: String) = index.signature.getRange(str, maxError)
}

class DLPreciseSearchAlgorithm(maxError: Int, index: CombinedIndex) : DLSearchAlgorithmBase(maxError, index) {
    // todo: optimize precise
    override fun getSignatures(str: String) = index.signature.getRange(str, 2 * maxError)
}