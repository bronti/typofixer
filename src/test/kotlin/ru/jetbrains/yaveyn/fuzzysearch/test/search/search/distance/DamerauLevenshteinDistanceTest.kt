package ru.jetbrains.yaveyn.fuzzysearch.test.search.search.distance

import com.jetbrains.typofixer.search.distance.DamerauLevenshteinDistance
import com.natpryce.hamkrest.assertion.assert
import com.natpryce.hamkrest.isWithin
import org.junit.Test

class DamerauLevenshteinDistanceTest {

    @Test
    fun equalsTest() {
        val word = "theWord"

        doTest(word, word, 0.0, 0.0)
    }

    @Test
    fun emptyWordTest() {
        val word = "ord"

        doTest(word, "", 3.0, 2.85)
    }

    @Test
    fun replaceTest() {
        val base = "ord"
        val replacement = "odd"

        doTest(base, replacement, 1.0, 1.0)
    }

    @Test
    fun addRemoveTest() {
        val base = "od"
        val replacement = "ord"

        doTest(base, replacement, 0.95, 1.0)
    }

    @Test
    fun swapTest() {
        val base = "lordoVldemorto"
        val replacement = "lordVoldemorto"

        doTest(base, replacement, 0.9, 0.9)
    }

    @Test
    fun shiftTest() {
        val base = "lordoVldemorto"
        val replacement = "lordovldemorto"

        doTest(base, replacement, 0.8, 0.8)
    }

    @Test
    fun bigDistanceTest() {
        val base = "ord"
        val replacement = "lordVoldemort"

        doTest(base, replacement, 10.0, 10.0)
    }

    private fun doTest(word1: String, word2: String, expectedDistance: Double, expectedDistanceOtherOrder: Double) {
        for (maxError in 0..4) {
            doTestWithMaxError(maxError, word1, word2, expectedDistance, expectedDistanceOtherOrder)
        }
    }

    private fun doTestWithMaxError(maxError: Int, word1: String, word2: String, expectedDistance: Double, expectedDistanceOtherOrder: Double) {
        val distance = DamerauLevenshteinDistance(maxError)
        val expectedResult = Math.min(distance.errorBiggerThanMax, expectedDistance)
        val expectedResultOtherOrder = Math.min(distance.errorBiggerThanMax, expectedDistanceOtherOrder)

        assert.that(distance.measure(word1, word2), isWithin(expectedResult - 0.01..expectedResult + 0.01))
        assert.that(distance.measure(word2, word1), isWithin(expectedResultOtherOrder - 0.01..expectedResultOtherOrder + 0.01))
    }

}