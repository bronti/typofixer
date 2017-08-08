package ru.jetbrains.yaveyn.fuzzysearch.test.search.replacement

import com.intellij.openapi.project.DumbService
import com.intellij.testFramework.fixtures.LightPlatformCodeInsightFixtureTestCase
import com.intellij.testFramework.runInEdtAndWait
import com.jetbrains.typofixer.TypoFixerComponent
import com.jetbrains.typofixer.TypoResolver
import org.junit.Ignore

@Ignore
open class BaseReplacementTest : LightPlatformCodeInsightFixtureTestCase() {

    override fun setUp() {
        super.setUp()
        val searcher = project!!.getComponent(TypoFixerComponent::class.java).searcher

        DumbService.getInstance(project).waitForSmartMode()
        searcher.getIndex().canRefreshGlobal = false
        searcher.forceGlobalIndexRefreshing()
    }

    var expectedResult: String? = null

    protected fun doTest(input: String, typed: Char, output: String, isKotlin: Boolean = false) {
        val (caretPos, _) = input.findAnyOf(listOf("<caret>"))!!
        val afterTyped =
                if (typed in listOf('>', ')', '}')) {
                    input.substring(0, caretPos) + typed + input.substring(caretPos, caretPos + "<caret>".length) + input.substring(caretPos + "<caret>".length + 1, input.length)
                } else {
                    input.substring(0, caretPos) + typed + input.substring(caretPos, input.length)
                }
        val fileName = "Foo." + if (isKotlin) "kt" else "java"
        myFixture.configureByText(fileName, input)
        DumbService.getInstance(project).waitForSmartMode()
        val resolver = TypoResolver.getInstance(typed, myFixture.editor, myFixture.file)
        myFixture.configureByText(fileName, afterTyped)
        DumbService.getInstance(project).waitForSmartMode()
        resolver?.resolve()
        expectedResult = output
    }

    override fun runTest() {
        super.runTest()

        runInEdtAndWait { myFixture.checkResult(expectedResult!!) }
    }

}