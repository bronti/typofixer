package com.jetbrains.typofixer.search.distance

interface Distance {
    fun measure(base: String, replacement: String): Double
    fun roughMeasure(base: String, replacement: String): Int
}

class DamerauLevenshteinDistance(private val maxError: Int) : Distance {

    companion object {
        // all penalties are equal because of multiple try
        private const val SWAP_PENALTY = 0.9
        private const val REMOVE_PENALTY = 1.0
        private const val REPLACE_PENALTY = 1.0
        private const val CHANGE_CASE_PENALTY = 0.8
        private const val ADD_PENALTY = 0.95
    }

    /*
     * returns distance if it is less or equals to maxError and maxError + 1 otherwise
     */
    // todo: bigger identifiers should allow more mistakes (?)
    // todo: check args order
    override fun measure(base: String, replacement: String): Double {

        val bigDistance = maxError + 1.0

        if (base.isEmpty()) return Math.min(replacement.length.toDouble(), bigDistance)
        if (replacement.isEmpty()) return Math.min(base.length.toDouble(), bigDistance)
        if (Math.abs(base.length - replacement.length) > maxError) return bigDistance

        val gapSize = 2 * maxError + 1

        var prevPrev = Array(gapSize) { Double.MAX_VALUE }
        var prev = Array(gapSize) { if (it - maxError >= 0) (it - maxError).toDouble() else Double.MAX_VALUE }
        var curr = Array(gapSize) { Double.MAX_VALUE }
        var tempArrayHolder: Array<Double>

        for (replacementInd in 1..replacement.length) {
            val replacementChar = replacement[replacementInd - 1]

            val minK = Math.max(maxError - replacementInd, 0)
            val maxK = Math.min(gapSize - 1, (base.length - replacementInd) + (maxError + 1) - 1)

            curr[minK] = replacementInd.toDouble()

            assert(minK <= maxK)

            for (k in minK..maxK) {
                val baseInd = replacementInd - maxError + k
                if (baseInd > 0) {
                    val baseChar = base[baseInd - 1]
                    // do nothing
                    if (baseChar == replacementChar) {
                        curr[k] = Math.min(prev[k], curr[k])
                    }
                    // swap
                    if (replacementInd > 1 && baseInd > 1 && baseChar == replacement[replacementInd - 2] && replacementChar == base[baseInd - 2]) {
                        curr[k] = Math.min(prevPrev[k] + SWAP_PENALTY, curr[k])
                    }
                    // remove (from base)
                    if (k > 0) {
                        curr[k] = Math.min(curr[k], curr[k - 1] + REMOVE_PENALTY)
                    }
                    // replace
                    val isEqualCharsWithDifferentCase = baseChar.toLowerCase() == replacementChar.toLowerCase()
                    val actualReplacePenalty = if (isEqualCharsWithDifferentCase) CHANGE_CASE_PENALTY else REPLACE_PENALTY
                    curr[k] = Math.min(curr[k], prev[k] + actualReplacePenalty)
                }
                // add (to base)
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
        return Math.min(prev[maxError + base.length - replacement.length], bigDistance)
    }

    override fun roughMeasure(base: String, replacement: String): Int = Math.round(measure(base, replacement)).toInt()
}
