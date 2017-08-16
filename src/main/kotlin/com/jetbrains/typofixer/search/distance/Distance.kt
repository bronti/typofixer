package com.jetbrains.typofixer.search.distance

interface Distance {
    fun measure(str1: String, str2: String): Double
    fun roughMeasure(str1: String, str2: String): Int
}

class DamerauLevenshteinDistance(private val maxError: Int) : Distance {

    companion object {
        // all penalties are equal because of multiple try
        private const val SWAP_PENALTY = 0.9
        private const val REMOVE_PENALTY = 1.0
        private const val REPLACE_PENALTY = 1.0
        private const val CHANGE_CASE_PENALTY = 0.8
        private const val ADD_PENALTY = 1.0
    }

    /*
     * returns distance if it is less or equals to maxError and maxError + 1 otherwise
     */
    // todo: bigger identifiers should allow more mistakes (?)
    override fun measure(str1: String, str2: String): Double {

        val bigDistance = maxError + 1.0

        if (str1.isEmpty()) return Math.min(str2.length.toDouble(), bigDistance)
        if (str2.isEmpty()) return Math.min(str1.length.toDouble(), bigDistance)
        if (Math.abs(str1.length - str2.length) > maxError) return bigDistance

        val (left, right) = if (str1.length > str2.length) Pair(str2, str1) else Pair(str2, str1)

        val gapSize = 2 * maxError + 1

        var prevPrev = Array(gapSize) { Double.MAX_VALUE }
        var prev = Array(gapSize) { if (it - maxError >= 0) it.toDouble() - maxError else Double.MAX_VALUE }
        var curr = Array(gapSize) { Double.MAX_VALUE }
        var tempArrayHolder: Array<Double>

        for (rightInd in 1..right.length) {
            val rightChar = right[rightInd - 1]

            val minK = Math.max(maxError - rightInd, 0)
            val maxK = Math.min(gapSize - 1, left.length - rightInd + maxError)

            curr[minK] = rightInd.toDouble()

            assert(minK <= maxK)

            for (k in minK..maxK) {
                val leftInd = rightInd - maxError + k
                if (leftInd > 0) {
                    val leftChar = left[leftInd - 1]
                    // do nothing
                    if (leftChar == rightChar) {
                        curr[k] = Math.min(prev[k], curr[k])
                    }
                    // swap
                    if (rightInd > 1 && leftInd > 1 && leftChar == right[rightInd - 2] && rightChar == left[leftInd - 2]) {
                        curr[k] = Math.min(prevPrev[k] + SWAP_PENALTY, curr[k])
                    }
                    // remove (from left)
                    if (k > 0) {
                        curr[k] = Math.min(curr[k], curr[k - 1] + REMOVE_PENALTY)
                    }
                    // replace
                    val isEqualCharsWithDifferentCase = leftChar.toLowerCase() == rightChar.toLowerCase()
                    val actualReplacePenalty = if (isEqualCharsWithDifferentCase) CHANGE_CASE_PENALTY else REPLACE_PENALTY
                    curr[k] = Math.min(curr[k], prev[k] + actualReplacePenalty)
                }
                // add (to left)
                if (k + 1 < gapSize) {
                    curr[k] = Math.min(curr[k], prev[k + 1] + ADD_PENALTY)
                }
            }
            if ((minK..maxK).all { curr[it] > maxError }) {
                return bigDistance
            }

            tempArrayHolder = prevPrev
            prevPrev = prev
            prev = curr
            curr = tempArrayHolder
            curr.fill(Double.MAX_VALUE)
        }
        return Math.min(prev[maxError + left.length - right.length], bigDistance)
    }

    override fun roughMeasure(str1: String, str2: String): Int = Math.round(measure(str1, str2)).toInt()
}
