package com.jetbrains.typofixer.search

import com.jetbrains.typofixer.ResolveCancelledException
import com.jetbrains.typofixer.search.distance.DamerauLevenshteinDistance
import com.jetbrains.typofixer.search.index.CombinedIndex
import com.jetbrains.typofixer.search.index.GlobalInnerIndexBase
import org.jetbrains.annotations.TestOnly

/**
 * @author bronti.
 */

abstract class SearchAlgorithm(
        val maxRoundedError: Int,
        val distance: DamerauLevenshteinDistance,
        protected val index: CombinedIndex
) {

    protected abstract fun getSignatures(str: String): List<Set<Int>>

    protected abstract fun findClosest(str: String, currentBestError: Int, type: CombinedIndex.IndexType, checkTime: () -> Unit): SearchResults

    // order in wordTypes matters
    fun findClosest(str: String, types: Array<CombinedIndex.IndexType>, checkTime: () -> Unit): SearchResults {
        return types.fold(SearchResults.empty(maxRoundedError)) { acc, type ->
            checkTime()
            acc.combinedWith(findClosest(str, acc.error, type, checkTime))
        }
    }

    fun getEmptyResult() = SearchResults(maxRoundedError, maxRoundedError, emptySequence())

    @TestOnly
    abstract fun findAll(str: String): Sequence<String>
}


abstract class DLSearchAlgorithmBase(
        maxRoundedError: Int,
        index: CombinedIndex
) : SearchAlgorithm(maxRoundedError, DamerauLevenshteinDistance(maxRoundedError), index) {

    private fun getEmptyResultBuilder(str: String, maxRoundedError: Int, type: CombinedIndex.IndexType) =
            SearchResultsBuilder(maxRoundedError, { distance.roundedMeasure(str, it) }, type)

    // todo: candidates count
    override fun findClosest(str: String, currentBestError: Int, type: CombinedIndex.IndexType, checkTime: () -> Unit): SearchResults {
        val signaturesByError = getSignatures(str)

        // todo: it -> if in {it: ...}
        val result = (0..currentBestError).fold(getEmptyResultBuilder(str, currentBestError, type)) { acc: SearchResultsBuilder, error ->
            checkTime()
            val signatures = signaturesByError[error]

            val candidates = try {
                index.getAll(type, signatures)
            } catch(e: GlobalInnerIndexBase.TriedToAccessIndexWhileItIsRefreshing) {
                throw ResolveCancelledException()
            }

            acc.combinedWith(error, candidates)
        }
        return result.getResults()
    }

    @TestOnly
    override fun findAll(str: String): Sequence<String> {
        return index.getAltogether(getSignatures(str).flatten().toSet()).filter { distance.roundedMeasure(str, it) <= maxRoundedError }
    }
}

class DLSearchAlgorithm(maxRoundedError: Int, index: CombinedIndex) : DLSearchAlgorithmBase(maxRoundedError, index) {
    override fun getSignatures(str: String) = index.signature.getRange(str, maxRoundedError)
}

class DLPreciseSearchAlgorithm(maxRoundedError: Int, index: CombinedIndex) : DLSearchAlgorithmBase(maxRoundedError, index) {
    // todo: optimize precise
    override fun getSignatures(str: String) = index.signature.getRange(str, 2 * maxRoundedError + 1)
}