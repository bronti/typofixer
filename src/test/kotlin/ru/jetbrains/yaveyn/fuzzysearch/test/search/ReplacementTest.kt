package ru.jetbrains.yaveyn.fuzzysearch.test.search

import com.intellij.openapi.project.DumbService
import com.intellij.testFramework.fixtures.LightPlatformCodeInsightFixtureTestCase

/**
 * @author bronti.
 */

class ReplacementTest : LightPlatformCodeInsightFixtureTestCase() {

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
            "class\n<caret>Some")

    fun testReplacementInIdentifier() = doTest(
            "class Some { voidd<caret>}",
            ' ',
            "class Some { void <caret>}")

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

    private fun doTest(input: String, typed: Char, output: String) {
        myFixture.configureByText("Foo.java", input)
        DumbService.getInstance(myModule.project).smartInvokeLater {
            myFixture.type(typed)
            myFixture.checkResult(output)
        }
    }
}
