package com.jetbrains.typofixer.search.distance

interface Distance {
    fun measure(base: String, replacement: String): Double
    fun roundedMeasure(base: String, replacement: String): Int
}

class DamerauLevenshteinDistance(private val maxRoundedError: Int) : Distance {

    val errorBiggerThanMax = maxRoundedError + 0.5

    companion object {
        // all penalties are equal because of multiple try
        private const val SWAP_PENALTY = 0.9
        private const val REMOVE_PENALTY = 1.0
        private const val REPLACE_PENALTY = 1.0
        private const val CHANGE_CASE_PENALTY = 0.8
        private const val ADD_PENALTY = 0.95

        private val penalties = listOf(
                SWAP_PENALTY,
                REMOVE_PENALTY,
                REPLACE_PENALTY,
                CHANGE_CASE_PENALTY,
                ADD_PENALTY
        )
    }

    /*
     * returns distance if it is less than errorBiggerThanMax and errorBiggerThanMax otherwise
     */
    // todo: bigger identifiers should allow more mistakes (?)
    override fun measure(base: String, replacement: String): Double {

        assert(maxRoundedError * (penalties.max()!!) < errorBiggerThanMax)

        if (base.isEmpty()) return Math.min(replacement.length * ADD_PENALTY, errorBiggerThanMax)
        if (replacement.isEmpty()) return Math.min(base.length * REMOVE_PENALTY, errorBiggerThanMax)
        if (Math.abs(base.length - replacement.length) > maxRoundedError) return errorBiggerThanMax

        val gapSize = 2 * maxRoundedError + 1

        var prevPrev = Array(gapSize) { Double.MAX_VALUE }
        var prev = Array(gapSize) { if (it - maxRoundedError >= 0) (it - maxRoundedError) * REMOVE_PENALTY else Double.MAX_VALUE }
        var curr = Array(gapSize) { Double.MAX_VALUE }
        var tempArrayHolder: Array<Double>

        for (replacementInd in 1..Math.min(replacement.length, base.length + maxRoundedError)) {
            val replacementChar = replacement[replacementInd - 1]

            val minK = Math.max(maxRoundedError - replacementInd, 0)
            val maxK = Math.min(gapSize - 1, (base.length - replacementInd) + (maxRoundedError + 1) - 1)

            curr[minK] = (replacementInd - maxRoundedError + minK) * REPLACE_PENALTY + (maxRoundedError - minK) * ADD_PENALTY

            assert(minK <= maxK)

            for (k in minK..maxK) {
                val baseInd = replacementInd - maxRoundedError + k
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
            if ((minK..maxK).all { curr[it] >= errorBiggerThanMax }) {
                return errorBiggerThanMax
            }

            tempArrayHolder = prevPrev
            prevPrev = prev
            prev = curr
            curr = tempArrayHolder
            curr.fill(Double.MAX_VALUE)
        }
        return Math.min(prev[maxRoundedError + base.length - replacement.length], errorBiggerThanMax)
    }

    override fun roundedMeasure(base: String, replacement: String): Int = Math.round(measure(base, replacement)).toInt()
}
