package com.jetbrains.typofixer.search.signature

/**
 * @author bronti.
 */

interface Signature {
    fun get(str: String): Int
    fun getRange(str: String, maxError: Int): List<HashSet<Int>>
}

private typealias Mutation = (Int, Int) -> Int

class SimpleSignature : Signature {

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
    override fun getRange(str: String, maxError: Int): List<HashSet<Int>> {
        val (base, length) = getRaw(str)
        return doGetRange(base, length, maxError, this::combine)
    }

    fun getRawRange(base: Int, length: Int, maxError: Int): List<HashSet<Pair<Int, Int>>> {
        return doGetRange(base, length, maxError, ::Pair)
    }

    // todo: test it directly!!!
    // todo: kill it
    private fun <T> doGetRange(base: Int, length: Int, maxError: Int, makeResult: (Int, Int) -> T): List<HashSet<T>> {
        fun errorsToMinRealError(baseError: Int, lengthError: Int): Int {
            return if (lengthError >= baseError) lengthError else (baseError + lengthError + 1) / 2
        }

        val result = Array(maxError + 1) { HashSet<T>() }

        fun basesWithMutation(maxIndex: Int, startingBases: HashSet<Int>, mutate: Mutation): List<HashSet<Int>> {
            val bases = Array(maxIndex + 1) { HashSet<Int>() }
            bases[0] = startingBases
            val restricted = hashSetOf<Int>()
            for (index in 1..maxIndex) {
                restricted.addAll(bases[index - 1])
                bases[index - 1]
                        .flatMap { mutateBase(it, mutate) }
                        .filter { it !in restricted }
                        .forEach { bases[index].add(it) }
            }
            return bases.toList()
        }

        val positiveMutation = { bs: Int, shift: Int -> bs or (1 shl shift) }
        val negativeMutation = { bs: Int, shift: Int -> bs and (1 shl shift).inv() }
        val bidirectionalMutation = { bs: Int, shift: Int -> bs xor (1 shl shift) }

        fun baseMutationsWithFirstK(mutation: Mutation): List<List<HashSet<Int>>> {
            val basicMutations = basesWithMutation(maxError, hashSetOf(base), mutation)
            return basicMutations.mapIndexed { baseError, bases ->
                basesWithMutation(maxError - baseError, bases, bidirectionalMutation)
            }
        }

        val forBiggerLength = baseMutationsWithFirstK(positiveMutation)
        val forLessLength = baseMutationsWithFirstK(negativeMutation)

        // works for maxError <= 2 (3?) ... (otherwise too big range)
        for (lengthError in (0..maxError)) {
            val maxBidirectionalError = maxError - lengthError

            fun updateResultsForLengthError(newLength: Int, allBases: List<List<HashSet<Int>>>) {
                for (startingBaseError in 0..lengthError) {
                    val bases = allBases[startingBaseError]
                    for (bidirectionalBaseError in 0..maxBidirectionalError) {
                        result[errorsToMinRealError(startingBaseError + bidirectionalBaseError, lengthError)]
                                .addAll(bases[bidirectionalBaseError].map { makeResult(it, newLength) })
                    }
                }
            }

            if (length - lengthError > 0) {
                updateResultsForLengthError(length - lengthError, forLessLength)
            }
            if (length + lengthError < lengthUpperBound) {
                updateResultsForLengthError(length + lengthError, forBiggerLength)
            }
        }
        // todo: get rid of it?..
        for (error in result.indices.reversed()) {
            for (smallerError in 0..error-1) {
                result[error] = result[error].filter { it !in result[smallerError] }.toHashSet()
            }
        }
        return result.toList()
    }

    private fun mutateBase(base: Int, mutate: (Int, Int) -> Int): HashSet<Int> {
        return HashSet((0..baseShift)
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