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

    fun testKotlinSpecificFields() = doTest(
            "fun s() = project.testing.uniquepackagenameololo.Ololo().somePrettyMethod<caret>",
            ' ',
            "fun s() = project.testing.uniquepackagenameololo.Ololo().somePrettyMethodU <caret>",
            true)

    fun testJavaPublicFields() = doTest(
            "class Cls { void funn() { new project.testing.uniquepackagenameololo.UniqueLikeASnowflake().publicFieldWantsToSlee<caret>}}",
            ' ',
            "class Cls { void funn() { new project.testing.uniquepackagenameololo.UniqueLikeASnowflake().publicFieldWantsToSleep <caret>}}",
            false)

    fun testJavaPrivateFields() = doTest(
            "class Cls { void funn() { new project.testing.uniquepackagenameololo.UniqueLikeASnowflake().privateFieldOhSoPrivat<caret>}}",
            ' ',
            "class Cls { void funn() { new project.testing.uniquepackagenameololo.UniqueLikeASnowflake().privateFieldOhSoPrivat <caret>}}",
            false)

    fun testJavaPublicMethods() = doTest(
            "class Cls { void funn() { new project.testing.uniquepackagenameololo.UniqueLikeASnowflake().publicMethodDanceWithM<caret>}}",
            '(',
            "class Cls { void funn() { new project.testing.uniquepackagenameololo.UniqueLikeASnowflake().publicMethodDanceWithMe(<caret>)}}",
            false)

    fun testJavaSpaceAfterMethod() = doTest(
            "class Cls { void funn() { new project.testing.uniquepackagenameololo.UniqueLikeASnowflake().publicMethodDanceWithM<caret>}}",
            ' ',
            "class Cls { void funn() { new project.testing.uniquepackagenameololo.UniqueLikeASnowflake().publicMethodDanceWithM <caret>}}",
            false)

    fun testJavaPrivateMethods() = doTest(
            "class Cls { void funn() { new project.testing.uniquepackagenameololo.UniqueLikeASnowflake().privateMethod66<caret>}}",
            '(',
            "class Cls { void funn() { new project.testing.uniquepackagenameololo.UniqueLikeASnowflake().privateMethod66(<caret>)}}",
            false)

    fun testKotlinFields() = doTest(
            "fun x() = project.testing.uniquepackagenameololo.UniqueLikeASnowflake().publicFieldWantsToSlee<caret>",
            ' ',
            "fun x() = project.testing.uniquepackagenameololo.UniqueLikeASnowflake().publicFieldWantsToSleep <caret>",
            true)

    // todo:
    fun testKotlinPrivateMethods() = doTest(
            "fun x() = project.testing.uniquepackagenameololo.UniqueLikeASnowflake().privateMethod66<caret>",
            '(',
            "fun x() = project.testing.uniquepackagenameololo.UniqueLikeASnowflake().privateMethod66(<caret>)",
            true)

    fun testKotlinMethodReference() = doTest(
            "fun x() = project.testing.uniquepackagenameololo.UniqueLikeASnowflake::publicMethodDanceWithM<caret>",
            ' ',
            "fun x() = project.testing.uniquepackagenameololo.UniqueLikeASnowflake::publicMethodDanceWithMe <caret>",
            true)

    fun testError2WithError1Existent() = doTest(
            "fun packageeee(): Boolean = packagee<caret>",
            '(',
            "fun packageeee(): Boolean = packageeee(<caret>)",
            true)

    fun testParamInLambda() = doTest(
            "val k = { id<caret>}",
            ' ',
            "val k = { id <caret>}",
            true)

    fun testParamInLambdaAfterBraces() = doTest(
            "val k = { ((id<caret>))}",
            ',',
            "val k = { ((id,<caret>))}",
            true)



}
