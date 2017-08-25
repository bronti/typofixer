package com.jetbrains.typofixer.search.signature

/**
 * @author bronti.
 */


private typealias BaseMutation = (Int, Int) -> Int


// todo: rewrite it
class ComplexSignature : SignatureBase() {

    // returns list of signature sets.
    // set with index k can only contain signatures corresponding to words with error >= k
    override fun <T> doGetRange(base: Int, length: Int, maxRoundedError: Int, makeResult: (Int, Int) -> T): List<HashSet<T>> {
        // returns min value of real error which can be be produced by word with given baseError and lengthError
        fun errorsToMinRealError(baseError: Int, lengthError: Int): Int {
            return if (lengthError >= baseError) lengthError else (baseError + lengthError + 1) / 2
        }

        val resultingRange = Array(maxRoundedError + 1) { HashSet<T>() }

        // returns list of signature sets.
        // result[0] == startingBases
        // result[i] contains only signatures which could be made from startingBases by applying given type of mutation i times
        // result[i] cannot intercept with result[j] when i != j
        fun basesWithMutation(maxMutationCount: Int, startingBases: HashSet<Int>, mutate: BaseMutation, additionalRestricted: List<HashSet<Int>> = listOf()): List<HashSet<Int>> {
            val bases = Array(maxMutationCount + 1) { HashSet<Int>() }
            bases[0] = startingBases
            val restricted = hashSetOf<Int>()
            for (index in 1..maxMutationCount) {
                restricted.addAll(bases[index - 1])
                bases[index - 1]
                        .flatMap { mutateBase(it, mutate) }
                        .filter { bs -> bs !in restricted && additionalRestricted.all { restr -> bs !in restr } }
                        .forEach { bases[index].add(it) }
            }
            return bases.toList()
        }

        val positiveMutation = { bs: Int, shift: Int -> bs or (1 shl shift) }
        val negativeMutation = { bs: Int, shift: Int -> bs and (1 shl shift).inv() }
        val bidirectionalMutation = { bs: Int, shift: Int -> bs xor (1 shl shift) }

        // result[i][j] contains only signatures which could be made from base by applying given type of mutation i times
        // and then bidirectionalMutation j times
        // but result[i][j] cannot intercept with result[k][l] when i <= k and j <= l
        fun baseMutationsWithFirstK(mutation: BaseMutation): List<List<HashSet<Int>>> {
            val basicMutations = basesWithMutation(maxRoundedError, hashSetOf(base), mutation)
            val restricted = ArrayList<HashSet<Int>>(maxRoundedError + 1)
            return basicMutations.mapIndexed { basicMutationCount, bases ->
                val toReturn = basesWithMutation(maxRoundedError - basicMutationCount, bases, bidirectionalMutation, restricted)
                if (restricted.isNotEmpty()) restricted.removeAt(restricted.size - 1)
                restricted.forEachIndexed { index, set -> set.addAll(toReturn[index]) }
                toReturn
            }
        }

        val forBiggerLength = baseMutationsWithFirstK(positiveMutation)
        val forLessLength = baseMutationsWithFirstK(negativeMutation)

        for (lengthError in (0..maxRoundedError)) {
            val maxBidirectionalError = maxRoundedError - lengthError

            fun updateResultsForLengthError(newLength: Int, allBases: List<List<HashSet<Int>>>) {
                for (startingBaseError in 0..lengthError) {
                    val bases = allBases[startingBaseError]
                    for (bidirectionalBaseError in 0..maxBidirectionalError) {
                        resultingRange[errorsToMinRealError(startingBaseError + bidirectionalBaseError, lengthError)]
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

        for (error in resultingRange.indices.reversed()) {
            for (smallerError in 0 until error) {
                resultingRange[error] = resultingRange[error].filter { it !in resultingRange[smallerError] }.toHashSet()
            }
        }
        return resultingRange.toList()
    }

    private fun mutateBase(base: Int, mutate: (Int, Int) -> Int): HashSet<Int> {
        return HashSet((0 until baseShift)
                .mapNotNull {
                    val mutated = mutate(base, it)
                    if (mutated == base) null else mutated
                })
    }
}