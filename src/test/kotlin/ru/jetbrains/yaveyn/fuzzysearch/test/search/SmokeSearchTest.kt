package ru.jetbrains.yaveyn.fuzzysearch.test.search

import com.jetbrains.typofixer.search.DLPreciseSearcher
import com.jetbrains.typofixer.search.DLSearcher
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

    fun searcherProvider(maxError: Int, index: Index) = DLSearcher(maxError, distanceProvider(maxError), index)
    fun preciseSearcherProvider(maxError: Int, index: Index) = DLPreciseSearcher(maxError, distanceProvider(maxError), index)

    fun hasAll(strs: List<String>) = Matcher(List<String>::containsAll, strs)

    @Test
    fun simpleTest() {
        val index = Index(SimpleSignature())
        listOf("alabama", "Alabama", "alab", "ala", "bububum").forEach { index.add(it) }

        val searcher = searcherProvider(3, index)

        assert.that(searcher.simpleSearch("alabama"), hasAll(listOf("alabama", "Alabama", "alab")) and !hasElement("ala") and !hasElement("bububum"))
    }

    @Test
    fun preciseSearcherTest() {
        val index = Index(SimpleSignature())
        listOf("aceg", "aikm").forEach { index.add(it) }

        val preciseSearcher = preciseSearcherProvider(3, index)

        assert.that(preciseSearcher.simpleSearch("aceg"), hasElement("aikm"))
    }

    @Test
    fun notPreciseSearcherTest() {
        val index = Index(SimpleSignature())
        listOf("aceg", "aikm").forEach { index.add(it) }

        val searcher = searcherProvider(3, index)

        assert.that(searcher.simpleSearch("aceg"), !hasElement("aikm"))
    }
}
