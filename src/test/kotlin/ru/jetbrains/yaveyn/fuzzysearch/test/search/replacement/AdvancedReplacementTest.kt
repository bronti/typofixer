package ru.jetbrains.yaveyn.fuzzysearch.test.search.replacement

import org.junit.Ignore


class AdvancedReplacementTest : BaseReplacementTest() {

    fun testIs() = doTest(
            "fun some() { 7 is<caret>",
            ' ',
            "fun some() { 7 is <caret>",
            true)

    fun testNotReplacingToName() = doTest(
            "fun is<caret>",
            ' ',
            "fun is <caret>",
            true)

    // todo: should it work?
    @Ignore
    fun testReplacingInParameters() = doTest(
            "fun is(varargg<caret>)",
            ' ',
            "fun is(vararg <caret>)",
            true)

    fun testOverride() = doTest(
            "class Some { ovarride<caret>}",
            ' ',
            "class Some { override <caret>}",
            true)

    fun testKeywordInPrimaryConstructor() = doTest(
            "class Some(vall<caret>)",
            ' ',
            "class Some(val <caret>)",
            true)
}
