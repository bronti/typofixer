package com.jetbrains.typofixer.search

import com.jetbrains.typofixer.search.distance.DamerauLevenshteinDistanceTo
import com.jetbrains.typofixer.search.distance.DistanceTo
import com.jetbrains.typofixer.search.index.CombinedIndex
import com.jetbrains.typofixer.search.index.CombinedIndex.WordType
import com.jetbrains.typofixer.search.index.GlobalInnerIndex
import org.jetbrains.annotations.TestOnly

/**
 * @author bronti.
 */
abstract class SearchAlgorithm(val maxError: Int, val getDistanceTo: (String) -> DistanceTo, val index: CombinedIndex) {

    protected abstract fun findClosestWithRealCandidatesCount(
            str: String,
            wordTypes: Array<CombinedIndex.WordType>,
            isTooLate: () -> Boolean = { false }): Pair<SearchResult, Int>
    protected abstract fun getSignatures(str: String): List<Set<Int>>

    // order in wordTypes matters
    fun findClosest(str: String, isTooLate: () -> Boolean, wordTypes: Array<CombinedIndex.WordType> = WordType.values()): SearchResult
            = findClosestWithRealCandidatesCount(str, wordTypes, isTooLate).first

    inner class SearchResult(foundWord: String?, val error: Int, val type: WordType) {
        val word = foundWord
            get() = if (isValid) field else null

        val isValid = foundWord != null && error <= maxError

        constructor() : this(null, maxError + 1, WordType.GLOBAL)

        // don't compare results from different outer classes!!!
        infix fun betterThan(other: SearchResult): Boolean {
//      todo:      assert(this@SearchAlgorithm == other???)
            if (!isValid) return false
            if (error != other.error) return error < other.error
            return type < other.type
        }
    }

    @TestOnly
    fun findClosestWithInfo(str: String): Pair<String?, Pair<Int, Int>> {
        val signaturesByError = getSignatures(str)

        val (result, realCandidatesCount) = findClosestWithRealCandidatesCount(str, WordType.values())

        val candidatesCount = signaturesByError.map { index.getAltogether(it).size }.sum()

        return Pair(if (result.isValid) result.word else null, Pair(realCandidatesCount, candidatesCount))
    }

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
    fun search(str: String): Map<Int, List<String>> {
        val distance = getDistanceTo(str)
        return getCandidates(str).groupBy { it: String -> distance.measure(it) }.filter { it.key <= maxError }
    }
}

abstract class DLSearchAlgorithmBase(maxError: Int, index: CombinedIndex)
    : SearchAlgorithm(maxError, { it: String -> DamerauLevenshteinDistanceTo(it, maxError) }, index) {

    val EMPTY_RESULT = SearchResult()

    override fun findClosestWithRealCandidatesCount(
            str: String,
            wordTypes: Array<CombinedIndex.WordType>,
            isTooLate: () -> Boolean): Pair<SearchAlgorithm.SearchResult, Int> {

        val distance = getDistanceTo(str)
        val signaturesByError = getSignatures(str)
        var realCandidatesCount = 0
        var result = this.SearchResult()

        for (error in signaturesByError.indices) {
            val signatures = signaturesByError[error]

            fun getMinimumOfType(type: CombinedIndex.WordType): SearchAlgorithm.SearchResult {
                val candidates = index.getAll(type, signatures)
                // todo: isTooLate (?)
                val best = candidates.filter { it != str }.minBy { distance.measure(it) }
                realCandidatesCount += candidates.size
                val bestError = if (best == null) maxError + 1 else distance.measure(best)
                assert(bestError >= error) // something wrong with index and/or signature
                return this.SearchResult(best, bestError, type)
            }

            fun searchForType(type: CombinedIndex.WordType): Boolean {
                if (result.isValid && result.type == type && result.error == error) return true

                val newResult = getMinimumOfType(type)

                if (!newResult.isValid) return false

                if (newResult betterThan result) {
                    result = newResult
                }
                return newResult.error == error
            }

            try {
                for (type in wordTypes) {
                    if (searchForType(type) || isTooLate()) {
                        return result to realCandidatesCount
                    }
                }
            } catch (e: GlobalInnerIndex.TriedToAccessIndexWhileItIsRefreshing) {
                return SearchResult() to realCandidatesCount
            }
        }

        return result to realCandidatesCount
    }
}

class DLSearchAlgorithm(maxError: Int, index: CombinedIndex) : DLSearchAlgorithmBase(maxError, index) {
    override fun getSignatures(str: String) = index.signature.getRange(str, maxError)
}

class DLPreciseSearchAlgorithm(maxError: Int, index: CombinedIndex) : DLSearchAlgorithmBase(maxError, index) {
    // todo: optimize precise
    override fun getSignatures(str: String) = index.signature.getRange(str, 2 * maxError)
}