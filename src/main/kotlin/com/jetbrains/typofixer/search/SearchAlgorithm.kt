package com.jetbrains.typofixer.search

import com.jetbrains.typofixer.search.distance.DistanceTo
import com.jetbrains.typofixer.search.index.Index

/**
 * @author bronti.
 */
interface SearchAlgorithm {
    fun findClosest(str: String): String?
    fun findAllClosest(str: String): List<String>
    fun simpleSearch(str: String): List<String>
    fun search(str: String): Map<Int, List<String>>
}

abstract class DLSearchAlgorithmBase(val maxError: Int, val getDistanceTo: (String) -> DistanceTo, val index: Index) : SearchAlgorithm {

    // todo: make protected
    abstract fun getCandidates(str: String): Set<String>

    override fun findClosest(str: String): String? {
        val distance = getDistanceTo(str)
        // todo: stop after 0
        // todo: start from closest signature
        // todo: prioritize keywords
        val result = getCandidates(str).minBy { distance.measure(it) } ?: return null
        return if (distance.measure(result) > maxError) null else result
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

    protected fun getRange(str: String, maxError: Int): Set<String> {
        // todo: get?
        return index.signature.getRange(str, maxError)
                .map { index.get(it) }
                .reduce { acc: Set<String>, curr -> acc.union(curr) }
    }
}

class DLSearchAlgorithm(maxError: Int, getDistanceTo: (String) -> DistanceTo, index: Index) : DLSearchAlgorithmBase(maxError, getDistanceTo, index) {
    override fun getCandidates(str: String) = getRange(str, maxError)
}

class DLPreciseSearchAlgorithm(maxError: Int, getDistanceTo: (String) -> DistanceTo, index: Index) : DLSearchAlgorithmBase(maxError, getDistanceTo, index) {
    override fun getCandidates(str: String): Set<String> = getRange(str, 2 * maxError)
}