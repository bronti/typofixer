//package ru.jetbrains.yaveyn.fuzzysearch.test.search.search
//
//import com.jetbrains.typofixer.search.DLPreciseSearchAlgorithm
//import com.jetbrains.typofixer.search.DLSearchAlgorithm
//import com.jetbrains.typofixer.search.index.CombinedIndex
//import com.jetbrains.typofixer.search.signature.ComplexSignature
//import com.natpryce.hamkrest.Matcher
//import com.natpryce.hamkrest.and
//import com.natpryce.hamkrest.assertion.assert
//import com.natpryce.hamkrest.hasElement
//import org.junit.Test
//
//class SmokeSearchTest {
//
//    fun searcherProvider(maxError: Int, index: CombinedIndex) = DLSearchAlgorithm(maxError, index)
//    fun preciseSearcherProvider(maxError: Int, index: CombinedIndex) = DLPreciseSearchAlgorithm(maxError, index)
//
//    fun hasAll(strs: List<String>) = Matcher(List<String>::containsAll, strs)
//
//    @Test
//    fun simpleTest() {
//        val index = CombinedIndex(ComplexSignature())
//        index.addToIndex(listOf("alabama", "Alabama", "alab", "ala", "bububum"))
//
//        val searcher = searcherProvider(3, index)
//
//        assert.that(searcher.simpleSearch("alabama"), hasAll(listOf("alabama", "Alabama", "alab")) and !hasElement("ala") and !hasElement("bububum"))
//    }
//
//    @Test
//    fun preciseSearcherTest() {
//        val index = CombinedIndex(ComplexSignature())
//        index.addToIndex(listOf("aceg", "aikm"))
//
//        val preciseSearcher = preciseSearcherProvider(3, index)
//
//        assert.that(preciseSearcher.simpleSearch("aceg"), hasElement("aikm"))
//    }
//
//    @Test
//    fun notPreciseSearcherTest() {
//        val index = CombinedIndex(ComplexSignature())
//        index.addToIndex(listOf("aceg", "aikm"))
//
//        val searcher = searcherProvider(3, index)
//
//        assert.that(searcher.simpleSearch("aceg"), !hasElement("aikm"))
//    }
//}
