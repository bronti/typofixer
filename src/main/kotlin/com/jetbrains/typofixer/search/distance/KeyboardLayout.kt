package com.jetbrains.typofixer.search.distance

private val LINE_LENGTHS = listOf(13, 12, 12, 10)
private val LINE_START = listOf(0, 1, 1, 1)


// todo: wtf is key before z?!
class KeyboardLayout(keysWithoutShift: String, keysWithShift: String) {
    private inner class CharPlace(val line: Int, val column: Int, val shiftPressed: Boolean) {
        infix fun isAdjacentTo(other: CharPlace): Boolean = when {
            line < other.line -> other isAdjacentTo this
            line - other.line > 1 -> false
            this isSameKey other -> false
            line == other.line -> Math.abs(column - other.column) <= 1
            line == other.line + 1 -> other.column - column in 0..1
            else -> throw IllegalStateException()
        }

        infix fun isSameKey(other: CharPlace) = line == other.line && column == other.column
        infix fun isSameCase(other: CharPlace) = shiftPressed == other.shiftPressed
    }

    private val charPlaces = HashMap<Char, CharPlace>()

    private fun initKeys(keys: String, shiftPressed: Boolean) {
        var keyIndexShift = 0
        for (lineNum in 0..3) {
            (0 until LINE_LENGTHS[lineNum])
                    .map { keys[it + keyIndexShift] }
                    .forEachIndexed { index, it -> charPlaces[it] = CharPlace(lineNum, index + LINE_START[lineNum], shiftPressed) }
            keyIndexShift += LINE_LENGTHS[lineNum]
        }
    }

    init {
        initKeys(keysWithoutShift, false)
        initKeys(keysWithShift, true)
    }

    private fun applyToPlaces(c1: Char, c2: Char, toApply: CharPlace.(CharPlace) -> Boolean): Boolean {
        val chPlace1 = charPlaces[c1] ?: return false
        val chPlace2 = charPlaces[c2] ?: return false
        return chPlace1.toApply(chPlace2)
    }

    infix fun Char.isAdjacentKey(other: Char) = applyToPlaces(this, other, CharPlace::isAdjacentTo)
    infix fun Char.isSameCase(other: Char) = applyToPlaces(this, other, CharPlace::isSameCase)
    infix fun Char.isSameKey(other: Char) = applyToPlaces(this, other, CharPlace::isSameKey)
}


// todo: make it customizable
val DEFAULT_KEYBOARD_LAYOUT = KeyboardLayout("`1234567890-=qwertyuiop[]asdfghjkl;'\\zxcvbnm,./", "~!@#$%^&*()_+QWERTYUIOP{}ASDFGHJKL:\"|ZXCVBNM<>?")

