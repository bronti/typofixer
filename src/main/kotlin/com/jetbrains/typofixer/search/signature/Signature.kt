package com.jetbrains.typofixer.search.signature

/**
 * @author bronti.
 */
interface Signature {
    fun signature(str: String): Int
    fun signatureRange(str: String, maxError: Int): List<Int>
}

// todo: make language specific
class SimpleSignature : Signature {

    // str should contain only a-zA-Z0-9_
    override fun signature(str: String): Int {
        fun index(c: Char) = charMap[c.toLowerCase()]!!
        return str.fold(0) { acc: Int, it -> acc or (1 shl index(it)) }
    }

    override fun signatureRange(str: String, maxError: Int): List<Int> {
        val sig = signature(str)
        return (0..maxError).flatMap { withKErrorsFromM(sig, it, 0) }
    }

    private fun withKErrorsFromM(signature: Int, k: Int, m: Int): List<Int> {
        if (k == 0) return listOf(signature)
        if (m == maxIndexForCharMap) return listOf()
        return withKErrorsFromM(signature, k, m + 1) + withKErrorsFromM(signature xor (1 shl m), k - 1, m + 1)
    }

    companion object {
        val charMap = hashMapOf(
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
                '_' to 18
        )

        val maxIndexForCharMap = 18
    }
}