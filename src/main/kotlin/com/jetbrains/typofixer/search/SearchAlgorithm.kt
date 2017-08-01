package com.jetbrains.typofixer.search

import com.jetbrains.typofixer.search.distance.DamerauLevenshteinDistanceTo
import com.jetbrains.typofixer.search.distance.DistanceTo
import com.jetbrains.typofixer.search.index.Index
import org.jetbrains.annotations.TestOnly

/**
 * @author bronti.
 */
abstract class SearchAlgorithm(val maxError: Int, val getDistanceTo: (String) -> DistanceTo, val index: Index) {

    protected abstract fun findClosestWithRealCandidatesCount(str: String): Pair<SearchAlgorithm.SearchResult, Int>
    protected abstract fun getSignatures(str: String): List<Set<Int>>

    fun findClosest(str: String): SearchResult = findClosestWithRealCandidatesCount(str).first

    inner class SearchResult(foundWord: String?, val error: Int, val type: Index.WordType) {
        val word = foundWord
            get() = if (isValid) field else null

        val isValid = foundWord != null && error <= maxError

        constructor(): this(null, maxError + 1, Index.WordType.GLOBAL)

        // don't compare results from different outer classes!!!
        fun betterThan(other: SearchResult): Boolean {
            if (!isValid) return false
            if (error != other.error) return error < other.error
            return type < other.type
        }
    }

    @TestOnly
    fun findClosestWithInfo(str: String): Pair<String?, Pair<Int, Int>> {
        val signaturesByError = getSignatures(str)

        val (result, realCandidatesCount) = findClosestWithRealCandidatesCount(str)

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

abstract class DLSearchAlgorithmBase(maxError: Int, index: Index)
    : SearchAlgorithm(maxError, { it: String -> DamerauLevenshteinDistanceTo(it, maxError) }, index) {

    override fun findClosestWithRealCandidatesCount(str: String): Pair<SearchAlgorithm.SearchResult, Int> {
        val distance = getDistanceTo(str)
        val signaturesByError = getSignatures(str)
        var realCandidatesCount = 0
        var result = this.SearchResult()

        // todo: refactor!!!!
        for (error in signaturesByError.indices) {
            val signatures = signaturesByError[error]
            
            fun getMinimumOfType(type: Index.WordType): SearchAlgorithm.SearchResult {
                val candidates = index.getAll(type, signatures)
                val best = candidates.minBy { distance.measure(it) }
                realCandidatesCount += candidates.size
                val bestError = if (best == null) maxError + 1 else distance.measure(best)
                assert(bestError >= error) // something wrong with index and/or signature
                return this.SearchResult(best, bestError, type)
            }

            fun searchForType(type: Index.WordType): Boolean {
                if (result.isValid && result.type == type && result.error == error) return true

                val newResult = getMinimumOfType(type)

                if (!newResult.isValid) return false

                if (newResult.betterThan(result)) {
                    result = newResult
                }
                return newResult.error == error
            }

            if (searchForType(Index.WordType.KEYWORD) || searchForType(Index.WordType.LOCAL) || searchForType(Index.WordType.GLOBAL)) {
                break
            }
        }

        return Pair(result, realCandidatesCount)
    }
}

class DLSearchAlgorithm(maxError: Int, index: Index) : DLSearchAlgorithmBase(maxError, index) {
    override fun getSignatures(str: String) = index.signature.getRange(str, maxError)
}

class DLPreciseSearchAlgorithm(maxError: Int, index: Index) : DLSearchAlgorithmBase(maxError, index) {
    // todo: optimize precise
    override fun getSignatures(str: String) = index.signature.getRange(str, 2 * maxError)
}