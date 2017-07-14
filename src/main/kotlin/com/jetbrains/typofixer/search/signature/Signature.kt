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
        val base = str.fold(0) { acc: Int, it -> acc or charMask(it) }
        val length = Math.min(lengthUpperBound - 1, str.length)
        return combine(base, length)
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
    override fun getRange(str: String, maxError: Int): List<Int> {
        val (base, length) = split(get(str))
        val result = HashSet<Int>()

        val basesPlus = HashSet<Int>(listOf(base))
        val basesMinus = HashSet<Int>(listOf(base))

        // works for maxError <= 2... (otherwise to big range)
        for (lengthError in (0..maxError)) {
            if (lengthError != 0) {
                basesPlus
                        .map { mutateBase(it, { bs, m -> bs or (1 shl m)}) }
                        .forEach { basesPlus.addAll(it) }
                basesMinus
                        .map { mutateBase(it, { bs, m -> bs and (1 shl m).inv()}) }
                        .forEach { basesMinus.addAll(it) }
            }
            val maxBaseError = maxError - lengthError
            if (length - lengthError > 0) {
                val bases = HashSet<Int>(basesMinus)
                for (k in 1..maxBaseError) {
                    bases.map { bs ->
                        (0..(baseShift - 1)).map { bs xor (1 shl it) }
                    }.forEach { bases.addAll(it) }
                }
                result.addAll(bases.map { combine(it, length - lengthError) })
            }
            if (length + lengthError < lengthUpperBound) {
                val bases = HashSet<Int>(basesPlus)
                for (k in 1..maxBaseError) {
                    bases.map { bs ->
                        (0..(baseShift - 1)).map { bs xor (1 shl it) }
                    }.forEach { bases.addAll(it) }
                }
                result.addAll(bases.map { combine(it, length + lengthError) })
            }
        }
        return result.toList()
    }

    private fun mutateBase(base: Int, mutate: (Int, Int) -> Int) = HashSet((0..(baseShift - 1)).map { mutate(base, it) })


    companion object {
        private val lengthUpperBound = 1 shl 7

        fun charMask(c: Char) = CHAR_MASK[c.toLowerCase()] ?: (baseShift - 1)

        private val CHAR_MASK = hashMapOf(
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

        val baseShift = 24
        val BASE_MASK = ((1 shl baseShift) - 1)
    }
}