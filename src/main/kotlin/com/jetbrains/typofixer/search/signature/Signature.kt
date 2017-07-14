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

    private fun withKErrorsFromM(base: Int, k: Int, m: Int): List<Int> {
        if (k == 0) return listOf(base)
        if (m == baseShift) return listOf()
        return withKErrorsFromM(base, k, m + 1) + withKErrorsFromM(base xor (1 shl m), k - 1, m + 1)
    }

    private fun mutateBase(base: Int, mutate: (Int, Int) -> Int) = HashSet((0..(baseShift - 1)).map { mutate(base, it) })


    companion object {
        private val lengthUpperBound = 1 shl 7

        fun charMask(c: Char) = CHAR_MASK[c.toLowerCase()] ?: (baseShift - 1)

        private val CHAR_MASK = hashMapOf(
                'a' to (1 shl 0),
                'b' to (1 shl 11),
                'c' to (1 shl 10),
                'd' to (1 shl 1),
                'e' to (1 shl 5),
                'f' to (1 shl 1),
                'g' to (1 shl 2),
                'h' to (1 shl 2),
                'i' to (1 shl 7),
                'j' to (1 shl 3),
                'k' to (1 shl 3),
                'l' to (1 shl 12),
                'm' to (1 shl 12),
                'n' to (1 shl 11),
                'o' to (1 shl 8),
                'p' to (1 shl 8),
                'q' to (1 shl 4),
                'r' to (1 shl 5),
                's' to (1 shl 0),
                't' to (1 shl 6),
                'u' to (1 shl 7),
                'v' to (1 shl 10),
                'w' to (1 shl 4),
                'x' to (1 shl 9),
                'y' to (1 shl 6),
                'z' to (1 shl 9),
                '1' to (1 shl 13),
                '2' to (1 shl 13),
                '3' to (1 shl 14),
                '4' to (1 shl 14),
                '5' to (1 shl 15),
                '6' to (1 shl 15),
                '7' to (1 shl 16),
                '8' to (1 shl 16),
                '9' to (1 shl 17),
                '0' to (1 shl 17),
                '!' to (1 shl 13),
                '@' to (1 shl 13),
                '#' to (1 shl 14),
                '$' to (1 shl 14),
                '%' to (1 shl 15),
                '^' to (1 shl 15),
                '&' to (1 shl 16),
                '*' to (1 shl 16),
                '_' to (1 shl 18),
                '-' to (1 shl 18),
                '+' to (1 shl 18),
                '=' to (1 shl 18),
                '<' to (1 shl 19),
                '>' to (1 shl 19),
                '?' to (1 shl 20),
                '/' to (1 shl 20),
                ':' to (1 shl 21),
                '\\' to (1 shl 21),
                '|' to (1 shl 21),
                '~' to (1 shl 22)
        )

        val baseShift = 24
        val BASE_MASK = ((1 shl baseShift) - 1)
    }
}