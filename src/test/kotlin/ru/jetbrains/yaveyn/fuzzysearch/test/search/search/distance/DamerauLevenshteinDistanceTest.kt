package ru.jetbrains.yaveyn.fuzzysearch.test.search.search.distance

import com.jetbrains.typofixer.search.distance.DamerauLevenshteinDistance
import com.natpryce.hamkrest.assertion.assert
import com.natpryce.hamkrest.isWithin
import org.junit.Test

class DamerauLevenshteinDistanceTest {

    @Test
    fun equalsTest() {
        val word = "theWord"

        doSymmetricTest(word, word, 0.0)
    }

    @Test
    fun emptyWordTest() {
        val word = "ord"

        doTest(word, "", 3.0, 2.85)
    }

    @Test
    fun adjacentReplaceTest() {
        val base = "ord"
        val replacement = "odd"

        doSymmetricTest(base, replacement, 0.9)
    }

    @Test
    fun adjacentDiffCaseReplaceTest() {
        val base = "ord"
        val replacement = "oDd"

        doSymmetricTest(base, replacement, 1.09)
    }

    @Test
    fun diffCaseReplaceTest() {
        val base = "ord"
        val replacement = "oRd"

        doSymmetricTest(base, replacement, 0.8)
    }

    @Test
    fun regularReplaceReplaceTest() {
        val base = "ord"
        val replacement = "omd"

        doSymmetricTest(base, replacement, 1.09)
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

        doSymmetricTest(base, replacement, 0.9)
    }

    @Test
    fun shiftTest() {
        val base = "lordoVldemorto"
        val replacement = "lordovldemorto"

        doSymmetricTest(base, replacement, 0.8)
    }

    @Test
    fun bigDistanceTest() {
        val base = "ord"
        val replacement = "lordVoldemort"

        doSymmetricTest(base, replacement, 10.0)
    }

    private fun doSymmetricTest(word1: String, word2: String, expectedDistance: Double) = doTest(word1, word2, expectedDistance, expectedDistance)
    private fun doTest(word1: String, word2: String, expectedDistance: Double, expectedDistanceOtherOrder: Double) {
        for (maxError in 0..4) {
            doTestWithMaxError(maxError, word1, word2, expectedDistance, expectedDistanceOtherOrder)
        }
    }

    private fun doTestWithMaxError(maxError: Int, word1: String, word2: String, expectedDistance: Double, expectedDistanceOtherOrder: Double) {
        val distance = DamerauLevenshteinDistance(maxError)
        val expectedResult = Math.min(distance.errorBiggerThanMax, expectedDistance)
        val expectedResultOtherOrder = Math.min(distance.errorBiggerThanMax, expectedDistanceOtherOrder)

        assert.that(distance.measure(word1, word2), isWithin(expectedResult - 0.001..expectedResult + 0.001))
        assert.that(distance.measure(word2, word1), isWithin(expectedResultOtherOrder - 0.001..expectedResultOtherOrder + 0.001))
    }

}