package ru.jetbrains.yaveyn.fuzzysearch.test.search

import com.intellij.testFramework.fixtures.LightPlatformCodeInsightFixtureTestCase

/**
 * @author bronti.
 */

class LocalIndexTest : LightPlatformCodeInsightFixtureTestCase() {

    fun testKeyword() {
        myFixture.configureByText("Foo.java", "clas<caret>")
        myFixture.type(' ')
        myFixture.checkResult("class <caret>")
    }

    fun testClass() {
        myFixture.configureByText("Foo.java", "class Some { Somee<caret> }")
        myFixture.type(' ')
        myFixture.checkResult("class Some { Some <caret> }")
    }

    fun testMethod() {
        myFixture.configureByText("Foo.java", "class Some { void someMethod() { someMetho<caret>")
        myFixture.type(' ')
        myFixture.checkResult("class Some { void someMethod() { someMethod <caret>")
    }

    fun testField() {
        myFixture.configureByText("Foo.java", "class Some { int someField; void someMethod() { someFiel<caret>")
        myFixture.type(' ')
        myFixture.checkResult("class Some { int someField; void someMethod() { someField <caret>")
    }

    fun testPackage() {
        myFixture.configureByText("Foo.java", "package some.package; import some.packkage<caret>")
        myFixture.type('.')
        myFixture.checkResult("package some.package; import some.package.<caret>")
    }
}
