package com.jetbrains.typofixer.search

import com.intellij.openapi.components.AbstractProjectComponent
import com.intellij.openapi.project.Project
import com.jetbrains.typofixer.search.distance.DamerauLevenshteinDistanceTo
import com.jetbrains.typofixer.search.index.Index
import com.jetbrains.typofixer.search.signature.SimpleSignature

/**
 * @author bronti.
 */

// todo: merge with Searcher (?)
abstract class SearcherProvider(project: Project) : AbstractProjectComponent(project) {
    abstract fun getSearcher(): Searcher
}

class DLSearcherProvider(project: Project) : SearcherProvider(project) {

    private val maxError = 2
    private val signature = SimpleSignature()
    private val distanceTo = { it: String -> DamerauLevenshteinDistanceTo(it, maxError) }
    private val index = Index(signature)

    override fun getSearcher() = DLSearcher(maxError, distanceTo, index)

    // todo: in background
    override fun initComponent() {
        index.refreshGlobal(myProject)
    }
}