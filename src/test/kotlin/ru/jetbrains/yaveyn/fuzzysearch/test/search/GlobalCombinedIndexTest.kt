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

class GlobalCombinedIndexTest : LightPlatformCodeInsightFixtureTestCase() {

    private val testDataDirPath = "./testData/"
    private val dependencyForTestingPath = "projectfortesting-1.0-SNAPSHOT.jar"
    private val classForTestingPath = "src/project/testing/uniquepackagename/UniqueLikeASnowflake.java"

    fun testClass()  = doTest(
            "class Some { Strin<caret> }",
            ' ',
            "class Some { String <caret> }"
    )

    fun testMethod() = doTest(
            "class Some { void someMethod() { System.out.printl<caret>",
            ' ',
            "class Some { void someMethod() { System.out.println <caret>"
    )

    fun testStaticField() = doTest(
            "class Some { void someMethod() { System.outt<caret>",
            '.',
            "class Some { void someMethod() { System.out.<caret>"
    )

    fun testPackage() = doTest(
            "import java.langg<caret>",
            '.',
            "import java.lang.<caret>"
    )

    fun testClassFromDependency() = doTest(
            "class Some { UniqueLikeASnowflakee<caret>}",
            ' ',
            "class Some { UniqueLikeASnowflake <caret>}"
    )

    fun testMethodFromDependency() = doTest(
            "class Some { void func() { new UniqueLikeASnowflake().publicMethodDanceWithMew<caret>}}",
            '(',
            "class Some { void func() { new UniqueLikeASnowflake().publicMethodDanceWithMe(<caret>}}"
    )

    fun testFieldFromDependency()  = doTest(
            "class Some { void func() { new UniqueLikeASnowflake().privateFieldOhSoPrivatt<caret>}}",
            ' ',
            "class Some { void func() { new UniqueLikeASnowflake().privateFieldOhSoPrivate <caret>}}"
    )

    fun testPackageFromDependency() = doTest(
            "import project.testing.uniquepackagenameololoo<caret>",
            ';',
            "import project.testing.uniquepackagenameololo;<caret>"
    )

    fun testClassFromOtherFile() = doTest(
            "class Some { UniqueLikeASnowflakee<caret>}",
            ' ',
            "class Some { UniqueLikeASnowflake <caret>}"
    )

    fun testMethodFromOtherFile() = doTest(
            "class Some { void func() { new UniqueLikeASnowflake().publicMethodDanceWithMew<caret>}}",
            '(',
            "class Some { void func() { new UniqueLikeASnowflake().publicMethodDanceWithMe(<caret>}}"
    )

    fun testFieldFromOtherFile() = doTest(
            "class Some { void func() { new UniqueLikeASnowflake().privateFieldOhSoPrivatt<caret>}}",
            ' ',
            "class Some { void func() { new UniqueLikeASnowflake().privateFieldOhSoPrivate <caret>}}"
    )

    fun testPackageFromOtherFile() = doTest(
            "import project.testing.uniquepackagename<caret>",
            ';',
            "import project.testing.uniquepackagename;<caret>"
    )

    private fun doTest(input: String, typed: Char, output: String) {
        myFixture.testDataPath = testDataDirPath
        myFixture.copyFileToProject(classForTestingPath)
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