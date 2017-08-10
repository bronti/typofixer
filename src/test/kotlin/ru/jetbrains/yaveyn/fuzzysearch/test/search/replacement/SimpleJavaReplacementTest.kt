package ru.jetbrains.yaveyn.fuzzysearch.test.search.replacement

/**
 * @author bronti.
 */

class SimpleJavaReplacementTest : BaseReplacementTest() {

    fun testReplacementAfterTyped() = doTest(
            "clasa<caret>",
            ' ',
            "class <caret>")

    fun testReplacementAfterEnter() = doTest(
            "clasa<caret>",
            '\n',
            "class\n<caret>")

    fun testReplacementAfterClosingParenthesis() = doTest(
            "class Some {privvate<caret>}",
            '}',
            "class Some {private}<caret>")

    fun testReplacementAfterTypedInSolidText() = doTest(
            "clasa<caret>Some",
            ' ',
            "class <caret>Some")

    fun testReplacementAfterEnterInSolidText() = doTest(
            "clasa<caret>Some",
            '\n',
            "class\n        <caret>Some")

    fun testReplacementInIdentifier() = doTest(
            "class Some { Somee<caret>}",
            ' ',
            "class Some { Some <caret>}")

    fun testReplacementInErrorElement() = doTest(
            "innerface<caret>",
            ' ',
            "interface <caret>")

    fun testNoReplacementInsideIdentifier() = doTest(
            "innerface<caret>",
            '_',
            "innerface_<caret>")

    fun testNoReplacementAtDocumentStart() = doTest(
            "<caret>something",
            ' ',
            " <caret>something")

    fun testReplacementWithShorterWord() = doTest(
            "interfacell<caret>",
            ' ',
            "interface <caret>")

    fun testReplacementWithLongerWord() = doTest(
            "nteface<caret>",
            ' ',
            "interface <caret>")

    fun doTest(input: String, typed: Char, output: String) {
        super.doTest(input, typed, output, false)
    }
}
