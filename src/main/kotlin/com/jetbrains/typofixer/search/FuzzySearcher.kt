package com.jetbrains.typofixer.search

import com.intellij.openapi.components.ProjectComponent
import com.jetbrains.typofixer.search.distance.DamerauLevenshteinDistanceTo
import com.jetbrains.typofixer.search.distance.DistanceTo
import com.jetbrains.typofixer.search.signature.SimpleSignature

/**
 * @author bronti.
 */

interface FuzzySearcher : ProjectComponent {
    val maxError: Int
    val index: Index
    fun findClosest(str: String): String?
}

abstract class DLSearcherBase : FuzzySearcher {

    override val maxError: Int = 2
    override val index = Index(SimpleSignature())

    protected abstract fun getCandidates(str: String): Set<String>

    private val distanceTo: (String) -> DistanceTo = { it: String -> DamerauLevenshteinDistanceTo(it, maxError) }

    override fun findClosest(str: String): String? {
        val distance = distanceTo(str)
        val candidates = getCandidates(str)
        val result = candidates.minBy { distance.measure(it) }
        return if (result == null || distance.measure(result) > maxError) null else result
    }

    protected fun getRange(str: String, maxError: Int): Set<String> {
        // todo: signature?
        return index.signatureProvider.signatureRange(str, maxError)
                .map { index.get(it) }
                .reduce { acc: Set<String>, curr -> acc.union(curr) }
    }
}

class DLSearcher : DLSearcherBase() {
    override fun getCandidates(str: String): Set<String> = getRange(str, maxError)
}