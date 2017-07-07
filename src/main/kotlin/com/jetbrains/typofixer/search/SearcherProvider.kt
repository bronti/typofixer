package com.jetbrains.typofixer.search

import com.intellij.openapi.components.ProjectComponent
import com.jetbrains.typofixer.search.distance.DamerauLevenshteinDistanceTo
import com.jetbrains.typofixer.search.index.Index
import com.jetbrains.typofixer.search.signature.SimpleSignature

/**
 * @author bronti.
 */

interface SearcherProvider : ProjectComponent {
    fun getSearcher(): Searcher
}

class DLSearcherProvider : SearcherProvider {

    private val maxError = 2
    private val signature = SimpleSignature()
    private val distanceTo = { it: String -> DamerauLevenshteinDistanceTo(it, maxError) }
    private val index = Index(signature)

    override fun getSearcher() = DLSearcher(maxError, distanceTo, index)

    override fun initComponent() {
    }
}