package ru.jetbrains.yaveyn.fuzzysearch.test.search

import com.jetbrains.typofixer.search.DLPreciseSearcher
import com.jetbrains.typofixer.search.DLSearcher
import com.jetbrains.typofixer.search.Searcher
import com.jetbrains.typofixer.search.distance.DamerauLevenshteinDistanceTo
import com.jetbrains.typofixer.search.index.Index
import com.jetbrains.typofixer.search.signature.SimpleSignature
import com.natpryce.hamkrest.*
import com.natpryce.hamkrest.assertion.assert
import org.junit.Ignore
import org.junit.Test

//@Ignore
class BigSearchTest {

    fun hasAll(strs: List<String>) = Matcher(List<String>::containsAll, strs)

    val pathToIntellij = "./testData/intellij-community-master.zip"
    val simpleIndex = FromZipTestIndexRetriever(SimpleSignature()).retrieve(pathToIntellij)

    fun distanceProvider(maxError: Int) = { it: String -> DamerauLevenshteinDistanceTo(it, maxError) }

    fun searcherProvider(maxError: Int, index: Index) = DLSearcher(maxError, distanceProvider(maxError), index)
    fun preciseSearcherProvider(maxError: Int, index: Index) = DLPreciseSearcher(maxError, distanceProvider(maxError), index)


    @Test
    fun intellijTest() {
        val maxError = 2
        val searcher = searcherProvider(maxError, simpleIndex)

        assert.that(searcher.simpleSearch("retrun"), hasElement("return"))
        assert.that(searcher.simpleSearch("Stirng"), hasElement("String") and hasElement("string"))
    }

    @Ignore
    @Test
    fun baselineIntellijTest() {
        val maxError = 2
        val searcher = preciseSearcherProvider(maxError, simpleIndex)

        assert.that(searcher.simpleSearch("retrun"), hasElement("return"))
        assert.that(searcher.simpleSearch("Stirng"), hasElement("String") and hasElement("string"))
    }

    @Test
    fun precisionTest() {
        doPrecisionIntellijTest("retrun", simpleIndex)
        doPrecisionIntellijTest("Stirng", simpleIndex)
    }

    fun doPrecisionIntellijTest(toSearch: String, index: Index) {
        val simplePrecisions = (0..2).map { doFixedErrorPrecisionIntellijTest(it, toSearch, searcherProvider(it, index), index) }

        println("$toSearch:")
        simplePrecisions.forEachIndexed { i, it -> println("maxError = $i: precision = $it") }
        assert.that(simplePrecisions[0], equalTo(1.0))
        assert.that(simplePrecisions[1], equalTo(1.0))
        assert.that(simplePrecisions[2], greaterThan(0.9))
        assert.that(simplePrecisions[2], !equalTo(1.0))
    }

    fun doFixedErrorPrecisionIntellijTest(maxError: Int, toSearch: String, searcher: Searcher, index: Index): Double {
        val preciseSearcher = preciseSearcherProvider(maxError, index)

        val preciseResult = preciseSearcher.simpleSearch(toSearch)
        val result = searcher.simpleSearch(toSearch)

        assert.that(preciseResult, hasAll(result))

        val precision = result.size.toDouble() / preciseResult.size

        return precision
    }


}
