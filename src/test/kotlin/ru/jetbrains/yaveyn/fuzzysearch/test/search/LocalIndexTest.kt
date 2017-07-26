package ru.jetbrains.yaveyn.fuzzysearch.test.search

import com.intellij.openapi.project.DumbService
import com.intellij.testFramework.fixtures.LightPlatformCodeInsightFixtureTestCase

/**
 * @author bronti.
 */

class LocalIndexTest : LightPlatformCodeInsightFixtureTestCase() {

    fun testKeyword() = doTest(
            "clasa<caret>",
            ' ',
            "class <caret>")

    fun testClass() = doTest(
            "class Some { Somee<caret> }",
            ' ',
            "class Some { Some <caret> }")

    fun testMethod() = doTest(
            "class Some { void someMethod() { someMetho<caret>",
            ' ',
            "class Some { void someMethod() { someMethod <caret>")

    fun testField() = doTest(
            "class Some { int someField; void someMethod() { someFiel<caret>",
            ' ',
            "class Some { int someField; void someMethod() { someField <caret>")

    fun testPackage() = doTest(
            "package some.package; import some.packkage<caret>",
            '.',
            "package some.package; import some.package.<caret>")

    private fun doTest(input: String, typed: Char, output: String) {
        myFixture.configureByText("Foo.java", input)
        DumbService.getInstance(myModule.project).smartInvokeLater {
            myFixture.type(typed)
            myFixture.checkResult(output)
        }
    }
}
