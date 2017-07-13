package com.jetbrains.typofixer.search.signature

/**
 * @author bronti.
 */
interface Signature {
    fun get(str: String): Int
    fun getRange(str: String, maxError: Int): List<Int>
}

// todo: make language specific
class SimpleSignature : Signature {

    override fun get(str: String): Int {
        val base = str.fold(0) { acc: Int, it -> acc or (1 shl charMapping(it)) }
        val length = Math.max(lengthUpperBound - 1, str.length)
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
            for (lengthError in (0..maxError - baseError)) {
                val bases = withKErrorsFromM(base, baseError, 0).distinct()
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
        if (m == maxMapping) return listOf()
        return withKErrorsFromM(base, k, m + 1) + withKErrorsFromM(base xor (1 shl m), k - 1, m + 1)
    }

    companion object {
        private val lengthUpperBound = 1 shl 7

        fun charMapping(c: Char) = charMap[c.toLowerCase()] ?: maxMapping

        private val charMap = hashMapOf(
                'a' to 0,
                'b' to 11,
                'c' to 10,
                'd' to 1,
                'e' to 5,
                'f' to 1,
                'g' to 2,
                'h' to 2,
                'i' to 7,
                'j' to 3,
                'k' to 3,
                'l' to 12,
                'm' to 12,
                'n' to 11,
                'o' to 8,
                'p' to 8,
                'q' to 4,
                'r' to 5,
                's' to 0,
                't' to 6,
                'u' to 7,
                'v' to 10,
                'w' to 4,
                'x' to 9,
                'y' to 6,
                'z' to 9,
                '1' to 13,
                '2' to 13,
                '3' to 14,
                '4' to 14,
                '5' to 15,
                '6' to 15,
                '7' to 16,
                '8' to 16,
                '9' to 17,
                '0' to 17,
                '!' to 13,
                '@' to 13,
                '#' to 14,
                '$' to 14,
                '%' to 15,
                '^' to 15,
                '&' to 16,
                '*' to 16,
                '_' to 18,
                '-' to 18,
                '+' to 18,
                '=' to 18,
                '<' to 19,
                '>' to 19,
                '?' to 20,
                '/' to 20,
                ':' to 21,
                '\\' to 21,
                '|' to 21,
                '~' to 22
        )

        val maxMapping = 23
    }
}