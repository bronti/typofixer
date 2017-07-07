package com.jetbrains.typofixer.search.distance

interface DistanceTo {
    val target: String
    fun measure(str: String): Int
}

class DamerauLevenshteinDistanceTo(override val target: String, private val maxError: Int) : DistanceTo {

    /*
     * returns distance if it is less or equals to maxError and maxError + 1 otherwise
     */
    override fun measure(str: String): Int {

        val bigDistance = maxError + 1

        if (target.isEmpty()) return Math.min(str.length, bigDistance)
        if (str.isEmpty()) return Math.min(target.length, bigDistance)
        if (Math.abs(target.length - str.length) > maxError) return bigDistance

        val (left, right) = if (target.length > str.length) Pair(str, target) else Pair(str, target)

        val gapSize = 2 * maxError + 1

        var prevPrev = Array(gapSize) { Integer.MAX_VALUE }
        var prev = Array(gapSize) { if (it - maxError >= 0) it - maxError else Integer.MAX_VALUE }
        var curr = Array(gapSize) { Integer.MAX_VALUE }
        var tempArrayHolder: Array<Int>

        for (rightInd in 1..right.length) {
            val rightChar = right[rightInd - 1]

            val minK = Math.max(maxError - rightInd, 0)
            val maxK = Math.min(gapSize - 1, left.length - rightInd + maxError)

            curr[minK] = rightInd

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
                        curr[k] = Math.min(prevPrev[k] + 1, curr[k])
                    }
                    // remove (from left)
                    if (k > 0) {
                        curr[k] = Math.min(curr[k], curr[k - 1] + 1)
                    }
                    // replace
                    curr[k] = Math.min(curr[k], prev[k] + 1)
                }
                // add (to left)
                if (k + 1 < gapSize) {
                    curr[k] = Math.min(curr[k], prev[k + 1] + 1)
                }
            }
            if ((minK..maxK).all { curr[it] > maxError }) {
                return bigDistance
            }

            tempArrayHolder = prevPrev
            prevPrev = prev
            prev = curr
            curr = tempArrayHolder
            curr.fill(Integer.MAX_VALUE)
        }
        return Math.min(prev[maxError + left.length - right.length], bigDistance)
    }
}
