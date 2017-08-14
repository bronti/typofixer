package com.jetbrains.typofixer.search

import com.jetbrains.typofixer.search.index.CombinedIndex


class WordFromResult(val word: String, val type: CombinedIndex.WordType)

private fun emptyIterator() = listOf<WordFromResult>().listIterator()

class SearchResults(private val maxError: Int, private val error: Double, iter: Iterator<WordFromResult>)
    : Iterator<WordFromResult> by (if (error <= maxError) iter else emptyIterator()) {

    constructor(maxError: Int) : this(maxError, maxError + 1.0, emptyIterator())

    constructor(maxError: Int, error: Double, words: List<WordFromResult>) : this(maxError, error, words.listIterator())

    operator fun plus(other: SearchResults): SearchResults {
        assert(maxError == other.maxError)
        if (error < other.error) return this
        if (error > other.error) return other
        return SearchResults(maxError, error, object : Iterator<WordFromResult> {
            override fun hasNext() = this@SearchResults.hasNext() || other.hasNext()
            override fun next() = if (this@SearchResults.hasNext()) this@SearchResults.next() else other.next()
        })
    }
}
