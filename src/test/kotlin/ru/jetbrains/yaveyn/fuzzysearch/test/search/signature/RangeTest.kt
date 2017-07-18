package ru.jetbrains.yaveyn.fuzzysearch.test.search.signature

import com.jetbrains.typofixer.search.signature.ComplexSignature
import com.natpryce.hamkrest.*
import com.natpryce.hamkrest.assertion.assert
import org.junit.Test


fun bitCount(diff: Int) = (0..31).map { 1 shl it }.map { diff and it }.filter { it > 0 }.count()
fun Int.thisFarFrom(p: Pair<Int, Int>) = bitCount(this xor p.first) == p.second

/**
 * @author bronti.
 */
class RangeTest {

    fun List<Pair<Int, Int>>.withLength(l: Int) = this.filter { it.second == l }
    fun List<Pair<Int, Int>>.withBase(b: Int)   = this.filter { it.first == b }

    fun hasBase(m: Matcher<Int>) = has(Pair<Int, Int>::first, m)
    fun thisFarFrom(oth: Int, n: Int) = Matcher(Int::thisFarFrom, Pair(oth, n))

    @Test
    fun testZeroError() {
        val signature = ComplexSignature()

        fun check(base: Int, length: Int) {
            assert.that(signature.getRawRange(base, length, 0).flatten(), hasSize(equalTo(1)) and hasElement(Pair(base, length)))
        }

        check(0, 0)
        check(167, 12)
        check(3076, 4)
    }

    @Test
    fun testZeroErrorWithBiggerMaxError() {
        val signature = ComplexSignature()
        fun getError0(base: Int, length: Int) = signature.getRawRange(base, length, 1)[0]

        fun checkError0(base: Int, length: Int) {
            assert.that(getError0(base, length), hasSize(equalTo(1)) and hasElement(Pair(base, length)))
        }

        checkError0(0, 0)
        checkError0(167, 12)
        checkError0(3076, 4)
    }

    @Test
    fun testOneError() {
        val signature = ComplexSignature()
        fun getError1(base: Int, length: Int) = signature.getRawRange(base, length, 1)[1].toList()

        fun checkEqualLength(base: Int, length: Int) {
            val range1EqLen = getError1(base, length).withLength(length)
            assert.that(range1EqLen, allElements(hasBase(thisFarFrom(base, 1))))
            assert.that(range1EqLen, hasSize(equalTo(25)))
        }

        fun checkAnotherLength(base: Int, length: Int) {
            val allLengthsRange = getError1(base, length)
            val range = allLengthsRange.withLength(length + 1) + allLengthsRange.withLength(length - 1)
            assert.that(range, allElements(hasBase(thisFarFrom(base, 1) or thisFarFrom(base, 0))))
            if (length <= 1) assert.that(range.withBase(base), hasSize(equalTo(1)))
            else assert.that(range.withBase(base), hasSize(equalTo(2)))
            val rangeNonEqualBases = range.filter {it.first != base }
            if (length <= 1) assert.that(rangeNonEqualBases, hasSize(equalTo( bitCount(base.inv()) - 31 + 25)))
            else assert.that(rangeNonEqualBases, hasSize(equalTo(25)))
        }

        fun check(base: Int, length: Int) {
            checkEqualLength(base, length)
            checkAnotherLength(base, length)
        }

        check(0, 0)
        check(0, 12)
        check(167, 12)
        check(3076, 4)
    }

    @Test
    fun testOneErrorWithBiggerMaxError() {
        val signature = ComplexSignature()
        fun getError1(base: Int, length: Int) = signature.getRawRange(base, length, 2)[1].toList()

        fun checkEqualLength(base: Int, length: Int) {
            val range1EqLen = getError1(base, length).withLength(length)
            assert.that(range1EqLen, allElements(hasBase(thisFarFrom(base, 1) or thisFarFrom(base, 2))))
            assert.that(range1EqLen, hasSize(equalTo(25 + 25 * 24 / 2)))
        }

        fun checkLengthError1(base: Int, length: Int) {
            val allLengthsRange = getError1(base, length)
            val range = allLengthsRange.withLength(length + 1) + allLengthsRange.withLength(length - 1)
            assert.that(range, allElements(hasBase(thisFarFrom(base, 1) or thisFarFrom(base, 0))))
            if (length <= 1) assert.that(range.withBase(base), hasSize(equalTo(1)))
            else assert.that(range.withBase(base), hasSize(equalTo(2)))
//            val rangeNonEqualBases = range.filter {it.first != base }
            // todo: size
        }

        fun checkLengthError2(base: Int, length: Int) {
            // todo:
        }

        fun check(base: Int, length: Int) {
            checkEqualLength(base, length)
            checkLengthError1(base, length)
            checkLengthError2(base, length)
        }

        check(0, 0)
        check(0, 2)
        check(167, 12)
        check(3076, 4)
        check(3077, 44)
    }

    // todo: test big length
}