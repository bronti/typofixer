package ru.jetbrains.yaveyn.fuzzysearch.test.search

import com.intellij.openapi.project.DumbService
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.LightPlatformCodeInsightFixtureTestCase
import com.jetbrains.typofixer.TypoResolver
import com.jetbrains.typofixer.searcher
import com.jetbrains.typofixer.typoFixerComponent
import java.io.File

/**
 * @author bronti.
 */

class LocalCombinedIndexTest : LightPlatformCodeInsightFixtureTestCase() {

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
            '(',
            "class Some { void someMethod() { someMethod(<caret>)")

    fun testField() = doTest(
            "class Some { int someField; void someMethod() { someFiel<caret>",
            ' ',
            "class Some { int someField; void someMethod() { someField <caret>")

    fun testPackage() = doTest(
            "package some.packagge; import some.packkagge<caret>",
            '.',
            "package some.packagge; import some.packagge.<caret>")

    private fun doTest(input: String, typed: Char, output: String) {
        myFixture.configureByText("Foo.java", input)
        DumbService.getInstance(project).waitForSmartMode()
        val resolver = TypoResolver.getInstanceIgnoreIsActive(typed, myFixture.editor, myFixture.file, false)
        myFixture.type(typed)
        resolver?.waitForResolve()
        myFixture.checkResult(output)
    }

    override fun setUp() {
        super.setUp()
        val searcher = project!!.searcher

        val testDataDir = File("testData")
        val dependency = File(testDataDir, "projectfortesting-1.0-SNAPSHOT.jar")
        PsiTestUtil.addLibrary(myFixture.module, dependency.canonicalPath)

        DumbService.getInstance(project).waitForSmartMode()
        searcher.getIndex().canRefreshGlobal = false
        project.typoFixerComponent.isActive = false
        searcher.forceGlobalIndexRefreshing()
    }
}
