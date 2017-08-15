package com.jetbrains.typofixer.search

import com.jetbrains.typofixer.search.index.CombinedIndex

class WordFromResult(val word: String, val type: CombinedIndex.WordType)

private fun emptyIterator() = listOf<WordFromResult>().listIterator()

class SearchResultsBuilder private constructor(
        private val maxError: Int,
        private val minErrorPossible: Double,
        val error: Double,
        val result: Sequence<String>,
        // todo: Dtring -> String rolls back
        private val measure: (String) -> Double) {

    val isActive = minErrorPossible == error

    constructor(maxError: Int, measure: (String) -> Double) : this(maxError, 0.0, maxError.toDouble(), emptySequence(), measure)

    private fun withMinErrorPossible(newMinErrorPossible: Double): SearchResultsBuilder {
        assert(newMinErrorPossible in minErrorPossible..error)
        return SearchResultsBuilder(maxError, newMinErrorPossible, error, result, measure)
    }

    private fun withAddedIfMinPossibleEquals(candidates: Sequence<String>): SearchResultsBuilder {
        return if (!isActive) {
            val measured = mutableListOf<Pair<String, Double>>()
            candidates.forEach { measured.add(it to measure(it)) }
            val newMinError = measured.map { it.second }.min() ?: maxError + 1.0
            if (newMinError > error) return this
            assert(minErrorPossible <= newMinError)
            val additionalResult = measured.asSequence().filter { it.second == newMinError }.map { it.first }
            if (newMinError < error) return SearchResultsBuilder(maxError, minErrorPossible, newMinError, additionalResult, measure)
            SearchResultsBuilder(maxError, minErrorPossible, error, result + additionalResult, measure)
        } else {
            // laziness here
            SearchResultsBuilder(maxError, minErrorPossible, error, result + candidates.filter { measure(it) == error }, measure)
        }
    }

    // invalidates builder
    fun withAdded(newMinErrorPossible: Double, newCandidates: Sequence<String>): SearchResultsBuilder {
        assert(newMinErrorPossible in minErrorPossible..maxError.toDouble())
        if (error < newMinErrorPossible) return this
        return withMinErrorPossible(newMinErrorPossible).withAddedIfMinPossibleEquals(newCandidates)
    }

    // invalidates builder
    fun withAdded(other: SearchResultsBuilder): SearchResultsBuilder {
        assert(maxError >= other.maxError && other.isActive)
        if (error == other.error) return SearchResultsBuilder(maxError, minErrorPossible, error, result + other.result, measure)
        if (error < other.error) return this
        else return other
    }
}
