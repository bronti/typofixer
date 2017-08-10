package ru.jetbrains.yaveyn.fuzzysearch.test.search.replacement

import org.junit.Ignore


class AdvancedReplacementTest : BaseReplacementTest() {

    fun testIs() = doTest(
            "fun some() { 7 is<caret>",
            ' ',
            "fun some() { 7 is <caret>",
            true)

    fun testKotlinNotReplacingToName() = doTest(
            "fun is<caret>",
            ' ',
            "fun is <caret>",
            true)

    fun testJavaNotReplacingToName() = doTest(
            "class Class { void class<caret>}",
            ' ',
            "class Class { void class <caret>}",
            false)

    // todo: should it work?
    @Ignore
    fun testKotlinReplacingInParameters() = doTest(
            "fun is(varargg<caret>)",
            ' ',
            "fun is(vararg <caret>)",
            true)

    fun testJavaNoReplacingInParameterName() = doTest(
            "class Class { void meth(Class class<caret>)}",
            ' ',
            "class Class { void meth(Class class <caret>)}",
            false)

    fun testJavaReplacingInParameterType() = doTest(
            "class Class { void meth(Classs<caret>)}",
            ' ',
            "class Class { void meth(Class <caret>)}",
            false)

    fun testOverride() = doTest(
            "class Some { ovarride<caret>}",
            ' ',
            "class Some { override <caret>}",
            true)

    // todo: doesn't work in tests but works irl. wtf?
    fun testKotlinSpecificFields() = doTest(
            "fun s() = Ololo().somePrettyMet<caret>",
            ' ',
            "fun s() = Ololo().somePrettyMethodU <caret>",
            true)

    fun testJavaFields() = doTest(
            "class Cls { void funn() { UniqueLikeASnowflake().publicFieldWantsToSlee<caret>}}",
            ' ',
            "class Cls { void funn() { UniqueLikeASnowflake().publicFieldWantsToSlee <caret>}}",
            false)

    // todo: doesn't work in tests but works irl. wtf?
    fun testKotlinFields() = doTest(
            "fun x() = UniqueLikeASnowflake().publicFieldWantsToSlee<caret>",
            ' ',
            "fun x() = UniqueLikeASnowflake().publicFieldWantsToSleep <caret>",
            true)

    // todo: test kotlin specific fields (new projectfortesting needed?)
}
