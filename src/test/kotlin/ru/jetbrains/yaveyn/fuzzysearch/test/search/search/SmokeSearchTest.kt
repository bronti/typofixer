package ru.jetbrains.yaveyn.fuzzysearch.test.search.search

import com.jetbrains.typofixer.search.DLPreciseSearcherAlgorithm
import com.jetbrains.typofixer.search.DLSearcherAlgorithm
import com.jetbrains.typofixer.search.distance.DamerauLevenshteinDistanceTo
import com.jetbrains.typofixer.search.index.Index
import com.jetbrains.typofixer.search.signature.SimpleSignature
import com.natpryce.hamkrest.Matcher
import com.natpryce.hamkrest.and
import com.natpryce.hamkrest.assertion.assert
import com.natpryce.hamkrest.hasElement
import org.junit.Test

class SmokeSearchTest {

    fun distanceProvider(maxError: Int) = { it: String -> DamerauLevenshteinDistanceTo(it, maxError) }

    fun searcherProvider(maxError: Int, index: Index) = DLSearcherAlgorithm(maxError, distanceProvider(maxError), index)
    fun preciseSearcherProvider(maxError: Int, index: Index) = DLPreciseSearcherAlgorithm(maxError, distanceProvider(maxError), index)

    fun hasAll(strs: List<String>) = Matcher(List<String>::containsAll, strs)

    @Test
    fun simpleTest() {
        val index = Index(SimpleSignature())
        index.updateLocal(listOf("alabama", "Alabama", "alab", "ala", "bububum"))

        val searcher = searcherProvider(3, index)

        assert.that(searcher.simpleSearch("alabama"), hasAll(listOf("alabama", "Alabama", "alab")) and !hasElement("ala") and !hasElement("bububum"))
    }

    @Test
    fun preciseSearcherTest() {
        val index = Index(SimpleSignature())
        index.updateLocal(listOf("aceg", "aikm"))

        val preciseSearcher = preciseSearcherProvider(3, index)

        assert.that(preciseSearcher.simpleSearch("aceg"), hasElement("aikm"))
    }

    @Test
    fun notPreciseSearcherTest() {
        val index = Index(SimpleSignature())
        index.updateLocal(listOf("aceg", "aikm"))

        val searcher = searcherProvider(3, index)

        assert.that(searcher.simpleSearch("aceg"), !hasElement("aikm"))
    }
}
