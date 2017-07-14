package com.jetbrains.typofixer.search.signature

/**
 * @author bronti.
 */

interface Signature {
    fun get(str: String): Int
    fun getRange(str: String, maxError: Int): Array<HashSet<Int>>
}

private typealias Mutation = (Int, Int) -> Int

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
    // todo: test it directly!!!
    override fun getRange(str: String, maxError: Int): Array<HashSet<Int>> {
        fun errorsToMinRealError(baseError: Int, lengthError: Int): Int {
            return if (lengthError >= baseError) lengthError else (baseError + lengthError + 1) / 2
        }

        val signature = get(str)
        val (base, length) = split(signature)

        val result = Array(maxError + 1) { HashSet<Int>() }
        result[0].add(signature)

        val positivelyMutatedBases = Array(maxError + 1) { HashSet<Int>() }
        val negativelyMutatedBases = Array(maxError + 1) { HashSet<Int>() }

        fun updateBases(bases: Array<HashSet<Int>>, mutate: Mutation, maxIndex: Int) {
            bases[0].add(base)
            for (index in 1..maxIndex) {
                bases[index - 1]
                        .flatMap { mutateBase(it, mutate) }
                        .filter { it !in bases[index - 1] }
                        .forEach { bases[index].add(it) }
            }
        }

        val positiveMutation = { bs: Int, shift: Int -> bs or (1 shl shift) }
        val negativeMutation = { bs: Int, shift: Int -> bs and (1 shl shift).inv() }
        val bidirectionalMutation = { bs: Int, shift: Int -> bs xor (1 shl shift) }

        updateBases(positivelyMutatedBases, positiveMutation, maxError)
        updateBases(negativelyMutatedBases, negativeMutation, maxError)

        // works for maxError <= 2... (otherwise too big range)
        for (lengthError in (0..maxError)) {
            val maxBaseError = maxError - lengthError

            fun updateResultsForLengthError(newLength: Int, startingBases: Array<HashSet<Int>>) {
                val bases = Array(maxBaseError + 1) { HashSet<Int>(startingBases[it]) }
                updateBases(bases, bidirectionalMutation, maxBaseError)
                bases.forEachIndexed { baseError, bss ->
                    result[errorsToMinRealError(baseError, lengthError)].addAll(bss.map { combine(it, newLength) })
                }
            }

            if (length - lengthError > 0) {
                updateResultsForLengthError(length - lengthError, negativelyMutatedBases)
            }
            if (length + lengthError < lengthUpperBound) {
                updateResultsForLengthError(length + lengthError, positivelyMutatedBases)
            }
        }
        return result
    }

    private fun mutateBase(base: Int, mutate: (Int, Int) -> Int): HashSet<Int> {
        return HashSet((0..(baseShift - 1))
                .mapNotNull {
                    val mutated = mutate(base, it)
                    if (mutated == base) null else mutated
                })
    }

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