package ru.jetbrains.yaveyn.fuzzysearch.test.search.replacement

import com.intellij.openapi.project.DumbService
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.LightPlatformCodeInsightFixtureTestCase
import com.jetbrains.typofixer.TypoResolver
import com.jetbrains.typofixer.searcher
import com.jetbrains.typofixer.typoFixerComponent
import org.junit.Ignore
import java.io.File

@Ignore
open class BaseReplacementTest : LightPlatformCodeInsightFixtureTestCase() {

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

    protected fun doTest(input: String, typed: Char, output: String, isKotlin: Boolean) {
        val fileName = "Foo." + if (isKotlin) "kt" else "java"
        myFixture.configureByText(fileName, input)
        DumbService.getInstance(project).waitForSmartMode()
        val resolver = TypoResolver.getInstanceIgnoreIsActive(typed, myFixture.editor, myFixture.file, false)
        myFixture.type(typed)
        resolver?.waitForResolve()
        myFixture.checkResult(output)
    }
}