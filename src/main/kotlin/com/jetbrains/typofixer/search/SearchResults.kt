package com.jetbrains.typofixer.search

import com.jetbrains.typofixer.search.distance.Distance
import com.jetbrains.typofixer.search.index.CombinedIndex


class SortedSearchResults(
        private val base: String,
        private val maxRoundedError: Int,
        wordsByMinPossibleError: Map<Int, Iterator<FoundWord>>,
        private val distanceProvider: Distance,
        private val sorter: Sorter
) {
    private var isValid = true
    private val unsortedResult = SearchResults(wordsByMinPossibleError, { distanceProvider.roundedMeasure(base, it) })

    private fun fordsForRoundedError(error: Int): Sequence<FoundWord> {
        assert(error <= maxRoundedError)
        assert(error >= 0)
        unsortedResult.refillMap(error)
        val nextWords = unsortedResult.wordsByMeasure[error] ?: return emptySequence()
        return sorter.sort(nextWords.asSequence(), base)
    }

    fun asSequence(): Sequence<FoundWord> {
        if (!isValid) throw IllegalStateException("Search result read twice")
        val result = (0..maxRoundedError).asSequence().flatMap { fordsForRoundedError(it) }
        isValid = false
        return result
    }
}

private class SearchResults(
        private val wordsByMinPossibleError: Map<Int, Iterator<FoundWord>>,
        private val measure: (String) -> Int
) {
    var wordsByMeasure: Map<Int, Iterator<FoundWord>> = emptyMap()

    fun refillMap(minPossibleError: Int) {
        val additionalWords: HashMap<Int, MutableList<FoundWord>> = hashMapOf()
        wordsByMinPossibleError.keys.filter { it <= minPossibleError }.sorted().forEach { index ->
            val nextWords = wordsByMinPossibleError[index]!!
            while (nextWords.hasNext()) {
                val nextWord = nextWords.next()
                val nextError = measure(nextWord.word)
                if (additionalWords[nextError] == null) {
                    additionalWords[nextError] = mutableListOf()
                }
                additionalWords[nextError]!!.add(nextWord)
            }
        }
        val newKeys = wordsByMeasure.keys.toSet() + additionalWords.keys

        fun getOldWords(error: Int) = wordsByMeasure[error]?.asSequence() ?: emptySequence()
        fun getAdditionalWords(error: Int) = additionalWords[error]?.asSequence() ?: emptySequence()

        wordsByMeasure = newKeys.map { it to (getOldWords(it) + getAdditionalWords(it)).iterator() }.toMap()
    }

//    //todo: fix Pair$
//    fun asSequence() = generateSequence<Pair$<>> { ... }
}

class FoundWord(val word: String, val type: FoundWordType)

enum class FoundWordType {
    IDENTIFIER, KEYWORD;

    companion object {
        fun getByIndexType(type: CombinedIndex.IndexType) = when (type) {
            CombinedIndex.IndexType.KEYWORD -> KEYWORD
            else -> IDENTIFIER
        }
    }
}

