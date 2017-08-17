package com.jetbrains.typofixer.search

import com.jetbrains.typofixer.ResolveAbortedException
import com.jetbrains.typofixer.search.distance.DamerauLevenshteinDistance
import com.jetbrains.typofixer.search.index.CombinedIndex
import com.jetbrains.typofixer.search.index.GlobalInnerIndexBase

/**
 * @author bronti.
 */

abstract class SearchAlgorithm(
        val maxError: Int,
        val distance: DamerauLevenshteinDistance,
        protected val index: CombinedIndex
) {

    protected abstract fun getSignatures(str: String): List<Set<Int>>

    protected abstract fun findClosest(str: String, currentBestError: Int, type: CombinedIndex.WordType, checkTime: () -> Unit): SearchResults

    // order in wordTypes matters
    fun findClosest(str: String, types: Array<CombinedIndex.WordType>, checkTime: () -> Unit): SearchResults {
        return types.fold(getEmptyResult()) { acc, type ->
            checkTime()
            acc.combinedWith(findClosest(str, acc.error, type, checkTime))
        }
    }

    fun getEmptyResult() = SearchResults(maxError, maxError, emptySequence())
}


abstract class DLSearchAlgorithmBase(
        maxError: Int,
        index: CombinedIndex
) : SearchAlgorithm(maxError, DamerauLevenshteinDistance(maxError), index) {

    private fun getEmptyResultBuilder(str: String, maxError: Int, type: CombinedIndex.WordType)
            = SearchResultsBuilder(maxError, { distance.roughMeasure(str, it) }, type)

    // todo: candidates count
    override fun findClosest(str: String, currentBestError: Int, type: CombinedIndex.WordType, checkTime: () -> Unit): SearchResults {
        val signaturesByError = getSignatures(str)

        // todo: it -> if in {it: ...}
        val result = (0..currentBestError).fold(getEmptyResultBuilder(str, currentBestError, type)) { acc: SearchResultsBuilder, error ->
            checkTime()
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