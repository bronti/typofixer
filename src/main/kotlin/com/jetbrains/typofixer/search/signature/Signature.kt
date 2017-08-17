package com.jetbrains.typofixer.search.signature

/**
 * @author bronti.
 */

interface Signature {
    fun get(str: String): Int
    fun getRange(str: String, maxRoundedError: Int): List<HashSet<Int>>
}


// works only for maxRoundedError <= 2!! too big range otherwise
abstract class SignatureBase : Signature {

    abstract protected fun <T> doGetRange(base: Int, length: Int, maxRoundedError: Int, makeResult: (Int, Int) -> T)
            : List<HashSet<T>>

    override fun get(str: String): Int {
        val (base, length) = getRaw(str)
        return combine(base, length)
    }

    private fun getRaw(str: String): Pair<Int, Int> {
        val base = str.fold(0) { acc: Int, it -> acc or charMask(it) }
        val length = Math.min(lengthUpperBound - 1, str.length)
        return Pair(base, length)
    }

    private fun combine(base: Int, length: Int): Int {
        return (length shl baseShift) + base
    }

    private fun split(signature: Int): Pair<Int, Int> {
        val base = signature and BASE_MASK
        val length = signature shr baseShift
        return Pair(base, length)
    }

    // should return nonempty collection
    override fun getRange(str: String, maxRoundedError: Int): List<HashSet<Int>> {
        val (base, length) = getRaw(str)
        return doGetRange(base, length, maxRoundedError, this::combine)
    }

    fun getRawRange(base: Int, length: Int, maxRoundedError: Int): List<HashSet<Pair<Int, Int>>> {
        return doGetRange(base, length, maxRoundedError, ::Pair)
    }

    companion object {
        protected @JvmStatic val lengthUpperBound = 1 shl 7

        protected fun charMask(c: Char) = CHAR_MASK[c.toLowerCase()] ?: (c.toInt() % baseShift)

        protected val CHAR_MASK = hashMapOf(
                'a' to (1 shl 2),
                'b' to (1 shl 11),
                'c' to (1 shl 12),
                'd' to (1 shl 9),
                'e' to (1 shl 0),
                'f' to (1 shl 11),
                'g' to (1 shl 11),
                'h' to (1 shl 7),
                'i' to (1 shl 4),
                'j' to (1 shl 14),
                'k' to (1 shl 14),
                'l' to (1 shl 10),
                'm' to (1 shl 14),
                'n' to (1 shl 5),
                'o' to (1 shl 3),
                'p' to (1 shl 10),
                'q' to (1 shl 12),
                'r' to (1 shl 8),
                's' to (1 shl 6),
                't' to (1 shl 1),
                'u' to (1 shl 13),
                'v' to (1 shl 11),
                'w' to (1 shl 12),
                'x' to (1 shl 12),
                'y' to (1 shl 13),
                'z' to (1 shl 12),

                '1' to (1 shl 15),
                '2' to (1 shl 16),
                '3' to (1 shl 16),
                '4' to (1 shl 17),
                '5' to (1 shl 18),
                '6' to (1 shl 18),
                '7' to (1 shl 19),
                '8' to (1 shl 20),
                '9' to (1 shl 20),
                '0' to (1 shl 21),

                '!' to (1 shl 15),
                '@' to (1 shl 16),
                '#' to (1 shl 17),
                '$' to (1 shl 17),
                '%' to (1 shl 18),
                '^' to (1 shl 19),
                '&' to (1 shl 19),
                '*' to (1 shl 20),
                '_' to (1 shl 21),
                '-' to (1 shl 21),
                '+' to (1 shl 22),
                '=' to (1 shl 22),
                '<' to (1 shl 23),
                '>' to (1 shl 23),
                '?' to (1 shl 23),
                '/' to (1 shl 24),
                ':' to (1 shl 22),
                '\\' to (1 shl 24),
                '|' to (1 shl 24),
                '~' to (1 shl 15)
        )

        val baseShift = 25
        protected val BASE_MASK = ((1 shl baseShift) - 1)
    }
}
