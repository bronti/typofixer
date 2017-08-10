//package ru.jetbrains.yaveyn.fuzzysearch.test.search.search
//
//import com.jetbrains.typofixer.search.DLPreciseSearchAlgorithm
//import com.jetbrains.typofixer.search.DLSearchAlgorithm
//import com.jetbrains.typofixer.search.SearchAlgorithm
//import com.jetbrains.typofixer.search.index.CombinedIndex
//import com.jetbrains.typofixer.search.signature.ComplexSignature
//import com.natpryce.hamkrest.*
//import com.natpryce.hamkrest.assertion.assert
//import org.junit.Ignore
//import org.junit.Test
//
//@Ignore
//class BigSearchTest {
//
//    fun hasAll(strs: List<String>) = Matcher(List<String>::containsAll, strs)
//
//    val pathToIntellij = "./testData/intellij-community-master.zip"
////    val simpleIndex = FromZipTestIndexRetriever(ComplexSignature()).retrieve(pathToIntellij)
//
//    fun searcherProvider(maxError: Int, index: CombinedIndex) = DLSearchAlgorithm(maxError, index)
//    fun preciseSearcherProvider(maxError: Int, index: CombinedIndex) = DLPreciseSearchAlgorithm(maxError, index)
//
//
//    @Test
//    fun intellijTest() {
//        val maxError = 2
//        val searcher = searcherProvider(maxError, simpleIndex)
//
//        assert.that(searcher.simpleSearch("retrun"), hasElement("return"))
//        assert.that(searcher.simpleSearch("Stirng"), hasElement("String") and hasElement("string"))
//    }
//
//    @Ignore
//    @Test
//    fun baselineIntellijTest() {
//        val maxError = 2
//        val searcher = preciseSearcherProvider(maxError, simpleIndex)
//
//        assert.that(searcher.simpleSearch("retrun"), hasElement("return"))
//        assert.that(searcher.simpleSearch("Stirng"), hasElement("String") and hasElement("string"))
//    }
//
//    @Test
//    fun precisionTest() {
//        doPrecisionIntellijTest("retrun", simpleIndex)
//        doPrecisionIntellijTest("Stirng", simpleIndex)
//    }
//
//    fun doPrecisionIntellijTest(toSearch: String, index: CombinedIndex) {
//        val simplePrecisions = (0..2).map { doFixedErrorPrecisionIntellijTest(it, toSearch, searcherProvider(it, index), index) }
//
//        println("$toSearch:")
//        simplePrecisions.forEachIndexed { i, it -> println("maxError = $i: precision = $it") }
//        assert.that(simplePrecisions[0], equalTo(1.0))
//        assert.that(simplePrecisions[1], equalTo(1.0))
//        assert.that(simplePrecisions[2], greaterThan(0.9))
//        assert.that(simplePrecisions[2], !equalTo(1.0))
//    }
//
//    fun doFixedErrorPrecisionIntellijTest(maxError: Int, toSearch: String, searcher: SearchAlgorithm, index: CombinedIndex): Double {
//        val preciseSearcher = preciseSearcherProvider(maxError, index)
//
//        val preciseResult = preciseSearcher.simpleSearch(toSearch)
//        val result = searcher.simpleSearch(toSearch)
//
//        assert.that(preciseResult, hasAll(result))
//
//        val precision = result.size.toDouble() / preciseResult.size
//
//        return precision
//    }
//
//
//}
