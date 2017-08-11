package ru.jetbrains.yaveyn.fuzzysearch.test.search.search.distance

import com.jetbrains.typofixer.search.distance.DamerauLevenshteinDistanceTo
import com.natpryce.hamkrest.assertion.assert
import com.natpryce.hamkrest.equalTo
import org.junit.Test

class DamerauLevenshteinDistanceToTest {

    fun distanceWithMaxError(maxError: Int) = { it: String -> DamerauLevenshteinDistanceTo(it, maxError) }

    @Test
    fun equalsTest() {
        val word = "theWord"

        doTest(word, word, 0.0)
    }

    @Test
    fun emptyWordTest() {
        val word = "ord"

        doTest(word, "", 3.0)
    }

    @Test
    fun replaceTest() {
        val word1 = "ord"
        val word2 = "odd"

        doTest(word1, word2, 1.0)
    }

    @Test
    fun removeTest() {
        val word1 = "ord"
        val word2 = "od"

        doTest(word1, word2, 1.0)
    }

    @Test
    fun addTest() {
        val word1 = "ord"
        val word2 = "lord"

        doTest(word1, word2, 1.0)
    }

    @Test
    fun swapTest() {
        val word1 = "lordoVldemorto"
        val word2 = "lordVoldemorto"

        doTest(word1, word2, 0.9)
    }

    @Test
    fun shiftTest() {
        val word1 = "lordoVldemorto"
        val word2 = "lordovldemorto"

        doTest(word1, word2, 0.8)
    }

    @Test
    fun bigDistanceTest() {
        val word1 = "ord"
        val word2 = "lordVoldemort"

        doTest(word1, word2, 10.0)
    }

    private fun doTest(word1: String, word2: String, expectedDistance: Double) {
        for (maxError in 0..4) {
            doTestWithMaxError(maxError, word1, word2, expectedDistance)
        }
    }

    private fun doTestWithMaxError(maxError: Int, word1: String, word2: String, expectedDistance: Double) {
        val expectedResult = Math.min(maxError + 1.0, expectedDistance)
        val distance = distanceWithMaxError(maxError)

        assert.that(distance(word1).measure(word2), equalTo(expectedResult))
        assert.that(distance(word2).measure(word1), equalTo(expectedResult))
    }

}