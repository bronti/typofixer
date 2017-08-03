package ru.jetbrains.yaveyn.fuzzysearch.test.search.replacement

import com.intellij.openapi.project.DumbService
import com.intellij.testFramework.fixtures.LightPlatformCodeInsightFixtureTestCase

open class BaseReplacementTest : LightPlatformCodeInsightFixtureTestCase() {

    protected fun doTest(input: String, typed: Char, output: String) {
        myFixture.configureByText("Foo.java", input)
        DumbService.getInstance(myModule.project).smartInvokeLater {
            myFixture.type(typed)
            myFixture.checkResult(output)
        }
    }
}