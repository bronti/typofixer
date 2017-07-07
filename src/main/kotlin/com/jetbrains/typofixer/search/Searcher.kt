package com.jetbrains.typofixer.search

import com.intellij.psi.PsiFile
import com.jetbrains.typofixer.search.distance.DistanceTo
import com.jetbrains.typofixer.search.index.Index

/**
 * @author bronti.
 */
interface Searcher {
    fun findClosestInFile(str: String, psiFile: PsiFile): String?
    fun simpleSearch(str: String): List<String>
    fun search(str: String): Map<Int, List<String>>
}

abstract class DLSearcherBase(val maxError: Int, val getDistanceTo: (String) -> DistanceTo, val index: Index) : Searcher {

    protected abstract fun getCandidates(str: String): Set<String>

    override fun findClosestInFile(str: String, psiFile: PsiFile): String? {
        index.feed(psiFile)
        val distance = getDistanceTo(str)
        val candidates = getCandidates(str)
        val result = candidates.minBy { distance.measure(it) }
        return if (result == null || distance.measure(result) > maxError) null else result
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
        // todo: signature?
        return index.signatureProvider.signatureRange(str, maxError)
                .map { index.get(it) }
                .reduce { acc: Set<String>, curr -> acc.union(curr) }
    }
}

class DLSearcher(maxError: Int, getDistanceTo: (String) -> DistanceTo, index: Index) : DLSearcherBase(maxError, getDistanceTo, index) {
    override fun getCandidates(str: String) = getRange(str, maxError)
}

class DLPreciseSearcher(maxError: Int, getDistanceTo: (String) -> DistanceTo, index: Index) : DLSearcherBase(maxError, getDistanceTo, index) {
    override fun getCandidates(str: String): Set<String> = getRange(str, 2 * maxError)
}