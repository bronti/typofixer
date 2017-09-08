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
        private const val REPLACE_PENALTY = 1.09
        private const val CHANGE_CASE_PENALTY = 0.8
        private const val ADJACENT_REPLACE_PENALTY = 0.9
        private const val ADD_PENALTY = 0.95

        private val penalties = listOf(
                SWAP_PENALTY,
                REMOVE_PENALTY,
                REPLACE_PENALTY,
                CHANGE_CASE_PENALTY,
                ADD_PENALTY
        )
    }

    private fun charDistance(c1: Char, c2: Char): Double {
        return with(DEFAULT_KEYBOARD_LAYOUT) {
            when {
                c1 isSameKey c2 && c1 isSameCase c2 -> 0.0
                c1 isSameKey c2 -> CHANGE_CASE_PENALTY
                c1 isAdjacentKey c2 && c1 isSameCase c2 -> ADJACENT_REPLACE_PENALTY
                else -> REPLACE_PENALTY
            }
        }

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

        val stepsCount = Math.min(replacement.length, base.length + maxRoundedError)

        fun dynamicallyCalculateDistance(
                replacementInd: Int,
                beforePreviousColumn: Array<Double>,
                previousColumn: Array<Double>
        ): Double {
            if (replacementInd == stepsCount + 1) {
                return Math.min(
                        previousColumn[maxRoundedError + base.length - replacement.length],
                        errorBiggerThanMax
                )
            }
            val curr = Array(gapSize) { Double.MAX_VALUE }
            val replacementChar = replacement[replacementInd - 1]

            val minK = Math.max(maxRoundedError - replacementInd, 0)
            val maxK = Math.min(
                    gapSize - 1,
                    (base.length - replacementInd) + (maxRoundedError + 1) - 1
            )

            curr[minK] = (replacementInd - maxRoundedError + minK) * REPLACE_PENALTY +
                    (maxRoundedError - minK) * ADD_PENALTY

            assert(minK <= maxK)

            for (k in minK..maxK) {
                val baseInd = replacementInd - maxRoundedError + k
                if (baseInd > 0) {
                    val baseChar = base[baseInd - 1]
                    // swap
                    if (replacementInd > 1 && baseInd > 1 && baseChar == replacement[replacementInd - 2] && replacementChar == base[baseInd - 2]) {
                        curr[k] = Math.min(beforePreviousColumn[k] + SWAP_PENALTY, curr[k])
                    }
                    // remove (from base)
                    if (k > 0) {
                        curr[k] = Math.min(curr[k], curr[k - 1] + REMOVE_PENALTY)
                    }
                    // replace
                    curr[k] = Math.min(curr[k], previousColumn[k] + charDistance(baseChar, replacementChar))
                }
                // add (to base)
                if (k + 1 < gapSize) {
                    curr[k] = Math.min(curr[k], previousColumn[k + 1] + ADD_PENALTY)
                }
            }
            if ((minK..maxK).all { curr[it] >= errorBiggerThanMax }) {
                return errorBiggerThanMax
            }
            return dynamicallyCalculateDistance(replacementInd + 1, previousColumn, curr)
        }

        val initialColumn = Array(gapSize) {
            if (it - maxRoundedError >= 0) (it - maxRoundedError) * REMOVE_PENALTY
            else Double.MAX_VALUE
        }

        return dynamicallyCalculateDistance(1, Array(gapSize) { Double.MAX_VALUE }, initialColumn)
    }

    override fun roundedMeasure(base: String, replacement: String): Int = Math.round(measure(base, replacement)).toInt()
}
