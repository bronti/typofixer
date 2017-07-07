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
}
