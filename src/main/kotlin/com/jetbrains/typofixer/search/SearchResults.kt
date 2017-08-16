package com.jetbrains.typofixer.search

import com.jetbrains.typofixer.search.index.CombinedIndex

class SearchResultsBuilder private constructor(
        private val maxError: Int,
        private val minErrorPossible: Int,
        val error: Int,
        private val result: Sequence<String>,
        // todo: Dtring -> String rolls back
        private val measure: (String) -> Int,
        private val type: CombinedIndex.WordType) {

    val isActive = minErrorPossible == error

    private fun searchResultsBuilderWith(newMinErrorPossible: Int = minErrorPossible,
                                         newError: Int = error,
                                         newResult: Sequence<String> = result)
            = SearchResultsBuilder(maxError, newMinErrorPossible, newError, newResult, measure, type)

    constructor(maxError: Int, measure: (String) -> Int, type: CombinedIndex.WordType)
            : this(maxError, 0, maxError, emptySequence(), measure, type)

    private fun withMinErrorPossible(newMinErrorPossible: Int): SearchResultsBuilder {
        assert(newMinErrorPossible in minErrorPossible..error)
        return searchResultsBuilderWith(newMinErrorPossible = newMinErrorPossible)
    }

    private fun withAddedIfMinPossibleEquals(candidates: Sequence<String>): SearchResultsBuilder {
        return if (!isActive) {
            val measured = mutableListOf<Pair<String, Int>>()
            candidates.forEach { measured.add(it to measure(it)) }

            // hack in case str which is going to be replaced is inside index
            // (in this case error is still 1 so strings with error == 1 can also be in result)
            val newMinError = Math.max(measured.map { it.second }.min() ?: maxError + 1, 1)
            if (newMinError > error) return this
            assert(minErrorPossible <= newMinError)
            val additionalResult = measured.asSequence().filter { it.second == newMinError }.map { it.first }
            if (newMinError < error) return searchResultsBuilderWith(newError = newMinError, newResult = additionalResult)
            searchResultsBuilderWith(newResult = result + additionalResult)
        } else {
            // laziness here
            searchResultsBuilderWith(newResult = result + candidates.filter { measure(it) == error })
        }
    }

    // invalidates this
    fun combinedWith(newMinErrorPossible: Int, newCandidates: Sequence<String>): SearchResultsBuilder {
        assert(newMinErrorPossible in minErrorPossible..maxError)
        if (error < newMinErrorPossible) return this
        return withMinErrorPossible(newMinErrorPossible).withAddedIfMinPossibleEquals(newCandidates)
    }

    fun getResults(): SearchResults {
        assert(isActive)
        return SearchResults(maxError, error, result.map { FoundWord(it, type) })
    }
}

class SearchResults(private val maxError: Int, val error: Int, private val result: Sequence<FoundWord>) : Sequence<FoundWord> by result {
    // invalidates this
    fun combinedWith(other: SearchResults): SearchResults {
        assert(maxError >= other.maxError)
        if (error == other.error) return SearchResults(maxError, error, result + other.result)
        if (error < other.error) return this
        else return other
    }
}

class FoundWord(val word: String, val type: CombinedIndex.WordType)
