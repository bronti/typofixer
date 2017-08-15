package com.jetbrains.typofixer.search

import com.jetbrains.typofixer.search.distance.DamerauLevenshteinDistanceTo
import com.jetbrains.typofixer.search.distance.DistanceTo
import com.jetbrains.typofixer.search.index.CombinedIndex

/**
 * @author bronti.
 */

abstract class SearchAlgorithm(val maxError: Int, val getDistanceTo: (String) -> DistanceTo, val index: CombinedIndex) {

    protected abstract fun getSignatures(str: String): List<Set<Int>>

    protected abstract fun findClosest(str: String, type: CombinedIndex.WordType, isTooLate: () -> Boolean): SearchResultsBuilder

    // order in wordTypes matters
    fun findClosest(str: String, types: Array<CombinedIndex.WordType>, isTooLate: () -> Boolean): Sequence<String> {
        val result = types.fold(getEmptyResultBuilder(str, maxError)) { acc, type ->
            if (isTooLate()) return emptySequence()
            // todo: bound error by prev results
            acc.withAdded(findClosest(str, type, isTooLate))
        }
        assert(result.isActive)
        return result.result
    }

//    @TestOnly
//    fun getCandidates(str: String): List<String> {
//        return getSignatures(str).flatMap { index.getAltogether(it) }
//    }
//
//    @TestOnly
//    fun simpleSearch(str: String): List<String> {
//        val distance = getDistanceTo(str)
//        return getCandidates(str).filter { distance.measure(it) <= maxError }
//    }
//
//    @TestOnly
//    fun search(str: String): Map<Double, List<String>> {
//        val distance = getDistanceTo(str)
//        return getCandidates(str).groupBy { it: String -> distance.measure(it) }.filter { it.key <= maxError }
//    }

    protected fun getEmptyResultBuilder(str: String, maxError: Int)
            = SearchResultsBuilder(maxError, getDistanceTo(str)::measure)
}

abstract class DLSearchAlgorithmBase(maxError: Int, index: CombinedIndex)
    : SearchAlgorithm(maxError, { it: String -> DamerauLevenshteinDistanceTo(it, maxError) }, index) {

    // todo: candidates count
    override fun findClosest(str: String, type: CombinedIndex.WordType, isTooLate: () -> Boolean): SearchResultsBuilder {
        val signaturesByError = getSignatures(str)

        // todo: it -> if in {it: ...}
        val result = signaturesByError.foldIndexed(getEmptyResultBuilder(str, maxError)) { error, acc, signatures ->
            if (isTooLate()) return acc

            val candidates = index.getAll(type, signatures)
            acc.withAdded(error.toDouble(), candidates)
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