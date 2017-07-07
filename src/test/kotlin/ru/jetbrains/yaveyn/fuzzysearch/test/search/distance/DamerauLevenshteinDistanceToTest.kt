package ru.jetbrains.yaveyn.fuzzysearch.test.search.distance

import com.jetbrains.typofixer.search.distance.DamerauLevenshteinDistanceTo
import com.natpryce.hamkrest.assertion.assert
import com.natpryce.hamkrest.equalTo
import org.junit.Test

class DamerauLevenshteinDistanceToTest {

    fun distanceWithMaxError(maxError: Int) = { it: String -> DamerauLevenshteinDistanceTo(it, maxError) }

    @Test
    fun equalsTest() {
        val word = "theWord"

        doTest(word, word, 0)
    }

    @Test
    fun emptyWordTest() {
        val word = "ord"

        doTest(word, "", 3)
    }

    @Test
    fun replaceTest() {
        val word1 = "ord"
        val word2 = "odd"

        doTest(word1, word2, 1)
    }

    @Test
    fun removeTest() {
        val word1 = "ord"
        val word2 = "od"

        doTest(word1, word2, 1)
    }

    @Test
    fun addTest() {
        val word1 = "ord"
        val word2 = "lord"

        doTest(word1, word2, 1)
    }

    @Test
    fun swapTest() {
        val word1 = "lordoVldemorto"
        val word2 = "lordVoldemorto"

        doTest(word1, word2, 1)
    }

    @Test
    fun bigDistanceTest() {
        val word1 = "ord"
        val word2 = "lordVoldemort"

        doTest(word1, word2, 10)
    }

    private fun doTest(word1: String, word2: String, expectedDistance: Int) {
        for (maxError in 0..4) {
            doTestWithMaxError(maxError, word1, word2, expectedDistance)
        }
    }

    private fun doTestWithMaxError(maxError: Int, word1: String, word2: String, expectedDistance: Int) {
        val expectedResult = Math.min(maxError + 1, expectedDistance)
        val distance = distanceWithMaxError(maxError)

        assert.that(distance(word1).measure(word2), equalTo(expectedResult))
        assert.that(distance(word2).measure(word1), equalTo(expectedResult))
    }

}