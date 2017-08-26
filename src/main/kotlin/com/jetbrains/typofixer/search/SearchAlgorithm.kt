package com.jetbrains.typofixer.search

import com.jetbrains.typofixer.ResolveCancelledException
import com.jetbrains.typofixer.search.distance.DamerauLevenshteinDistance
import com.jetbrains.typofixer.search.index.CombinedIndex
import com.jetbrains.typofixer.search.index.GlobalInnerIndexBase
import org.jetbrains.annotations.TestOnly

/**
 * @author bronti.
 */

abstract class SearchAlgorithm(
        val maxRoundedError: Int,
        val distance: DamerauLevenshteinDistance,
        protected val index: CombinedIndex
) {
    protected val sorter = Sorter(distance)

    protected abstract fun getSignatures(str: String): List<Set<Int>>

    // todo: Sorted
    // order in wordTypes matters
    abstract fun find(str: String, types: List<CombinedIndex.IndexType>, checkTime: () -> Unit): SortedSearchResults

    @TestOnly
    abstract fun findAll(str: String): Sequence<String>
}


abstract class DLSearchAlgorithmBase(
        maxRoundedError: Int,
        index: CombinedIndex
) : SearchAlgorithm(maxRoundedError, DamerauLevenshteinDistance(maxRoundedError), index) {

    private fun getFromIndex(str: String, types: List<CombinedIndex.IndexType>, checkTime: () -> Unit) =
            getSignatures(str)
                    .map { signatures ->
                        try {
                            checkTime()
                            types.asSequence().flatMap { type ->
                                index.getAll(type, signatures).map { FoundWord(it, FoundWordType.getByIndexType(type)) }
                            }
                        } catch (e: GlobalInnerIndexBase.TriedToAccessIndexWhileItIsRefreshing) {
                            throw ResolveCancelledException()
                        }
                    }
                    .mapIndexed { index, it -> index to it.iterator() }
                    .toMap()

    // todo: candidates count
    override fun find(str: String, types: List<CombinedIndex.IndexType>, checkTime: () -> Unit) =
            SortedSearchResults(str, maxRoundedError, getFromIndex(str, types, checkTime), distance, sorter)

    @TestOnly
    override fun findAll(str: String): Sequence<String> {
        return index.getAltogether(getSignatures(str).flatten().toSet()).filter { distance.roundedMeasure(str, it) <= maxRoundedError }
    }
}

class DLSearchAlgorithm(maxRoundedError: Int, index: CombinedIndex) : DLSearchAlgorithmBase(maxRoundedError, index) {
    override fun getSignatures(str: String) = index.signature.getRange(str, maxRoundedError)
}

class DLPreciseSearchAlgorithm(maxRoundedError: Int, index: CombinedIndex) : DLSearchAlgorithmBase(maxRoundedError, index) {
    // todo: optimize precise
    override fun getSignatures(str: String) = index.signature.getRange(str, 2 * maxRoundedError + 1)
}