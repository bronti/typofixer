package com.jetbrains.typofixer.search

class SearchResultsBuilder private constructor(
        private val maxError: Int,
        private val minErrorPossible: Int,
        val error: Int,
        val result: Sequence<String>,
        // todo: Dtring -> String rolls back
        private val measure: (String) -> Int) {

    val isActive = minErrorPossible == error

    constructor(maxError: Int, measure: (String) -> Int) : this(maxError, 0, maxError, emptySequence(), measure)

    private fun withMinErrorPossible(newMinErrorPossible: Int): SearchResultsBuilder {
        assert(newMinErrorPossible in minErrorPossible..error)
        return SearchResultsBuilder(maxError, newMinErrorPossible, error, result, measure)
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
            if (newMinError < error) return SearchResultsBuilder(maxError, minErrorPossible, newMinError, additionalResult, measure)
            SearchResultsBuilder(maxError, minErrorPossible, error, result + additionalResult, measure)
        } else {
            // laziness here
            SearchResultsBuilder(maxError, minErrorPossible, error, result + candidates.filter { measure(it) == error }, measure)
        }
    }

    // invalidates builder
    fun combinedWith(newMinErrorPossible: Int, newCandidates: Sequence<String>): SearchResultsBuilder {
        assert(newMinErrorPossible in minErrorPossible..maxError)
        if (error < newMinErrorPossible) return this
        return withMinErrorPossible(newMinErrorPossible).withAddedIfMinPossibleEquals(newCandidates)
    }

    // invalidates builder
    fun combinedWith(other: SearchResultsBuilder): SearchResultsBuilder {
        assert(maxError >= other.maxError && other.isActive)
        if (error == other.error) return SearchResultsBuilder(maxError, minErrorPossible, error, result + other.result, measure)
        if (error < other.error) return this
        else return other
    }
}
