package ru.jetbrains.yaveyn.fuzzysearch.test.search.replacement


class AdvancedReplacementTest : BaseReplacementTest() {

    fun testIs() = doTest(
            "fun some() { 7 is<caret>",
            ' ',
            "fun some() { 7 is <caret>")

    fun testNotReplacingToName() = doTest(
            "fun is<caret>",
            ' ',
            "fun is <caret>")

    fun testReplacingInParameters() = doTest(
            "fun is(varargg<caret>)",
            ' ',
            "fun is(vararg <caret>)")
}
