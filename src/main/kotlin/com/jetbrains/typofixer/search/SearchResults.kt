package com.jetbrains.typofixer.search

import com.jetbrains.typofixer.search.index.CombinedIndex

class SearchResults(
        private val maxRoundedError: Int,
        private val wordsByMinPossibleError: Map<Int, Iterator<FoundWord>>,
        private val measure: (String) -> Int
) {
    private var wordsByMeasure: Map<Int, Iterator<FoundWord>> = emptyMap()
    private var currentError = 0

    private fun refillMap(minPossibleError: Int) {
        val additionalWords: HashMap<Int, MutableList<FoundWord>> = hashMapOf()
        wordsByMinPossibleError.keys.filter { it <= minPossibleError }.sorted().forEach outer@ { index ->
            val nextWords = wordsByMinPossibleError[index]!!
            while (nextWords.hasNext()) {
                val nextWord = nextWords.next()
                val nextError = measure(nextWord.word)
                if (additionalWords[nextError] == null) {
                    additionalWords[nextError] = mutableListOf()
                }
                additionalWords[nextError]!!.add(nextWord)
                if (nextError == minPossibleError) return@outer
            }
        }
        val newKeys = wordsByMeasure.keys.toSet() + additionalWords.keys

        fun getOldWords(error: Int) = wordsByMeasure[error]?.asSequence() ?: emptySequence()
        fun getAdditionalWords(error: Int) = additionalWords[error]?.asSequence() ?: emptySequence()

        wordsByMeasure = newKeys.map { it to (getOldWords(it) + getAdditionalWords(it)).iterator() }.toMap()
    }

    private fun next(): Pair<Int, FoundWord>? {
        if (currentError > maxRoundedError) return null
        if (wordsByMeasure[currentError]?.hasNext() != true) {
            refillMap(currentError)
        }
        if (wordsByMeasure[currentError]?.hasNext() != true) {
            ++currentError
            return next()
        }
        return currentError to wordsByMeasure[currentError]!!.next()
    }

    // should be called once
    fun asSequence() = generateSequence(this::next)


//    //todo: fix Pair$
//    fun asSequence() = generateSequence<Pair$<>> { ... }
}

// todo: sorted results
//class SortedSearchResults
//      private val maxRoundedError: Int,
//      private val wordsByMinPossibleError: Map<Int, Iterator<FoundWord>>,
//      private val measure: (String) -> Int,
//      private val sorter: Sorter
//) { ... }

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

