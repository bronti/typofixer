package ru.jetbrains.yaveyn.fuzzysearch.test.search

import com.intellij.testFramework.fixtures.LightPlatformCodeInsightFixtureTestCase

/**
 * @author bronti.
 */

class ReplacementTest : LightPlatformCodeInsightFixtureTestCase() {

    fun testReplacementAfterTyped() {
        myFixture.configureByText("Foo.java", "clas<caret>")
        myFixture.type(' ')
        myFixture.checkResult("class <caret>")
    }

    fun testReplacementAfterEnter() {
        myFixture.configureByText("Foo.java", "clas<caret>")
        myFixture.type('\n')
        myFixture.checkResult("class\n<caret>")
    }

    fun testReplacementAfterClosingParenthesis() {
        myFixture.configureByText("Foo.java", "class Some {privvate<caret>}")
        myFixture.type('}')
        myFixture.checkResult("class Some {private}<caret>")
    }

    fun testReplacementAfterTypedInSolidText() {
        myFixture.configureByText("Foo.java", "clas<caret>Some")
        myFixture.type(' ')
        myFixture.checkResult("class <caret>Some")
    }

    fun testReplacementAfterEnterInSolidText() {
        myFixture.configureByText("Foo.java", "clas<caret>Some")
        myFixture.type('\n')
        myFixture.checkResult("class\n<caret>Some")
    }

    fun testReplacementInIdentifier() {
        myFixture.configureByText("Foo.java", "class Some { voidd<caret>}")
        myFixture.type(' ')
        myFixture.checkResult("class Some { void <caret>}")
    }

    fun testReplacementInErrorElement() {
        myFixture.configureByText("Foo.java", "innerface<caret>")
        myFixture.type(' ')
        myFixture.checkResult("interface <caret>")
    }

    fun testNoReplacementInsideIdentifier() {
        myFixture.configureByText("Foo.java", "innerface<caret>")
        myFixture.type('_')
        myFixture.checkResult("innerface_<caret>")
    }

    fun testNoReplacementInTheBeginningOfADocument() {
        myFixture.configureByText("Foo.java", "<caret>")
        myFixture.type(' ')
        myFixture.checkResult(" <caret>")
    }

    fun testReplacementWithShorterWord() {
        myFixture.configureByText("Foo.java", "classss<caret>")
        myFixture.type(' ')
        myFixture.checkResult("class <caret>")
    }

    fun testReplacementWithLongerWord() {
        myFixture.configureByText("Foo.java", "cla<caret>")
        myFixture.type(' ')
        myFixture.checkResult("class <caret>")
    }
}
