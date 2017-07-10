package com.jetbrains.typofixer.search

import com.jetbrains.typofixer.search.distance.DistanceTo
import com.jetbrains.typofixer.search.index.Index

/**
 * @author bronti.
 */
interface SearcherAlgorithm {
    fun findClosest(str: String): String?
    fun simpleSearch(str: String): List<String>
    fun search(str: String): Map<Int, List<String>>
}

abstract class DLSearcherAlgorithmBase(val maxError: Int, val getDistanceTo: (String) -> DistanceTo, val index: Index) : SearcherAlgorithm {

    protected abstract fun getCandidates(str: String): Set<String>

    override fun findClosest(str: String): String? {
        val distance = getDistanceTo(str)
        val result = getCandidates(str).minBy { distance.measure(it) } ?: return null
        return if (distance.measure(result) > maxError) null else result
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

class DLSearcherAlgorithm(maxError: Int, getDistanceTo: (String) -> DistanceTo, index: Index) : DLSearcherAlgorithmBase(maxError, getDistanceTo, index) {
    override fun getCandidates(str: String) = getRange(str, maxError)
}

class DLPreciseSearcherAlgorithm(maxError: Int, getDistanceTo: (String) -> DistanceTo, index: Index) : DLSearcherAlgorithmBase(maxError, getDistanceTo, index) {
    override fun getCandidates(str: String): Set<String> = getRange(str, 2 * maxError)
}