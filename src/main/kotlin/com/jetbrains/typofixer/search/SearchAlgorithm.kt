package com.jetbrains.typofixer.search

import com.jetbrains.typofixer.search.distance.DistanceTo
import com.jetbrains.typofixer.search.index.Index

/**
 * @author bronti.
 */
interface SearchAlgorithm {
    fun findClosest(str: String): String?
    fun findClosestWithInfo(str: String): Pair<String?, Pair<Int, Int>>
    fun findAllClosest(str: String): List<String>
    fun simpleSearch(str: String): List<String>
    fun search(str: String): Map<Int, List<String>>
}

abstract class DLSearchAlgorithmBase(val maxError: Int, val getDistanceTo: (String) -> DistanceTo, val index: Index) : SearchAlgorithm {

    protected abstract fun getClassifiedCandidates(str: String): List<List<String>>

    // todo: make protected
    fun getCandidates(str: String) = getClassifiedCandidates(str).flatten()

    override fun findClosest(str: String): String? {
        val (result, _) = findClosestWithInfo(str)
        return result
    }

    override fun findClosestWithInfo(str: String): Pair<String?, Pair<Int, Int>> {
        val distance = getDistanceTo(str)
        val candidates = getClassifiedCandidates(str)
        val candidatesCount = candidates.map { it.size }.sum()
        var realCandidatesCount = 0
        // todo: refactor
        var result: String? = null
        for (error in 0..maxError) {
            if (result != null && distance.measure(result) <= error) {
                break
            }
            val newResult = candidates[error].minBy { distance.measure(it) }
            realCandidatesCount += candidates[error].size
            if (result == null || (newResult != null && distance.measure(newResult) < distance.measure(result))) {
                result = newResult
            }
        }
        if (result != null && distance.measure(result) > maxError) {
            result = null
        }
        return Pair(result, Pair(realCandidatesCount, candidatesCount))
    }

    override fun findAllClosest(str: String): List<String> {
        val results = search(str)
        for (error in 0..maxError) {
            if (results[error]?.isNotEmpty() ?: false) return results[error]!!
        }
        return listOf()
    }

    override fun simpleSearch(str: String): List<String> {
        val distance = getDistanceTo(str)
        return getCandidates(str).filter { distance.measure(it) <= maxError }
    }

    override fun search(str: String): Map<Int, List<String>> {
        val distance = getDistanceTo(str)
        return getCandidates(str).groupBy { it: String -> distance.measure(it) }.filter { it.key <= maxError }
    }

    protected fun getRange(str: String, maxError: Int): List<List<String>> {
        return index.signature.getRange(str, maxError)
                .map { signatures -> signatures.flatMap { index.get(it) } }
    }
}

class DLSearchAlgorithm(maxError: Int, getDistanceTo: (String) -> DistanceTo, index: Index) : DLSearchAlgorithmBase(maxError, getDistanceTo, index) {
    override fun getClassifiedCandidates(str: String) = getRange(str, maxError)
}

class DLPreciseSearchAlgorithm(maxError: Int, getDistanceTo: (String) -> DistanceTo, index: Index) : DLSearchAlgorithmBase(maxError, getDistanceTo, index) {
    // todo: optimize precise
    override fun getClassifiedCandidates(str: String) = getRange(str, 2 * maxError)
}