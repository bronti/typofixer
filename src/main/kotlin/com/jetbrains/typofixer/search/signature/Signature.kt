package com.jetbrains.typofixer.search.signature

/**
 * @author bronti.
 */
interface Signature {
    fun get(str: String): Int
    fun getRange(str: String, maxError: Int): List<Int>
}

class SimpleSignature : Signature {

    override fun get(str: String): Int {
        val base = str.fold(0) { acc: Int, it -> acc or (1 shl charMapping(it)) }
        val length = Math.min(lengthUpperBound - 1, str.length)
        return combine(base, length)
    }

    private fun combine(base: Int, length: Int): Int {
        return (length shl (maxMapping + 1)) + base
    }

    private fun split(signature: Int): Pair<Int, Int> {
        val base = signature and ((1 shl (maxMapping + 1)) - 1)
        val length = signature shr (maxMapping + 1)
        return Pair(base, length)
    }

    override fun getRange(str: String, maxError: Int): List<Int> {
        val (base, length) = split(get(str))
        val result = HashSet<Int>()

        // todo: optimize size of result
        for (baseError in (0..maxError)) {
            val bases = withKErrorsFromM(base, baseError, 0).distinct()
            for (lengthError in (0..maxError)) {
                if (length - lengthError > 0) {
                    result.addAll(bases.map { combine(it, length - lengthError) })
                }
                if (length + lengthError < lengthUpperBound) {
                    result.addAll(bases.map { combine(it, length + lengthError) })
                }
            }
        }
        return result.toList()
    }

    private fun withKErrorsFromM(base: Int, k: Int, m: Int): List<Int> {
        if (k == 0) return listOf(base)
        if (m == maxMapping + 1) return listOf()
        return withKErrorsFromM(base, k, m + 1) + withKErrorsFromM(base xor (1 shl m), k - 1, m + 1)
    }

    companion object {
        private val lengthUpperBound = 1 shl 7

        fun charMapping(c: Char) = charMap[c.toLowerCase()] ?: maxMapping

        private val charMap = hashMapOf(
                'a' to 2,
                'b' to 11,
                'c' to 12,
                'd' to 9,
                'e' to 0,
                'f' to 11,
                'g' to 11,
                'h' to 7,
                'i' to 4,
                'j' to 14,
                'k' to 14,
                'l' to 10,
                'm' to 14,
                'n' to 5,
                'o' to 3,
                'p' to 10,
                'q' to 12,
                'r' to 8,
                's' to 6,
                't' to 1,
                'u' to 13,
                'v' to 11,
                'w' to 12,
                'x' to 12,
                'y' to 13,
                'z' to 12,

                '1' to 15,
                '2' to 16,
                '3' to 16,
                '4' to 17,
                '5' to 18,
                '6' to 18,
                '7' to 19,
                '8' to 20,
                '9' to 20,
                '0' to 21,
                '!' to 15,
                '@' to 16,
                '#' to 17,
                '$' to 17,
                '%' to 18,
                '^' to 19,
                '&' to 19,
                '*' to 20,
                '_' to 21,
                '-' to 21,
                '+' to 22,
                '=' to 22,
                '<' to 23,
                '>' to 23,
                '?' to 23,
                '/' to 24,
                ':' to 22,
                '\\' to 24,
                '|' to 24,
                '~' to 15
        )

        val maxMapping = 24
    }
}