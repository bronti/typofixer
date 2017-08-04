package ru.jetbrains.yaveyn.fuzzysearch.test.search

import com.intellij.openapi.project.DumbService
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.LightPlatformCodeInsightFixtureTestCase

/**
 * @author bronti.
 */

class GlobalCombinedIndexTest : LightPlatformCodeInsightFixtureTestCase() {

    private val testDataDirPath = "./testData/"
    private val dependencyForTestingPath = "projectfortesting-1.0-SNAPSHOT.jar"
    private val classForTestingPath = "src/project/testing/uniquepackagename/UniqueLikeASnowflake.java"

    fun testClass() {
        myFixture.configureByText("Foo.java", "class Some { Strin<caret> }")
        DumbService.getInstance(myModule.project).smartInvokeLater {
            myFixture.type(' ')
            myFixture.checkResult("class Some { String <caret> }")
        }
    }

    fun testMethod() {
        myFixture.configureByText("Foo.java", "class Some { void someMethod() { System.out.printl<caret>")
        DumbService.getInstance(myModule.project).smartInvokeLater {
            myFixture.type(' ')
            myFixture.checkResult("class Some { void someMethod() { System.out.println <caret>")
        }
    }

    fun testStaticField() {
        myFixture.configureByText("Foo.java", "class Some { void someMethod() { System.outt<caret>")
        DumbService.getInstance(myModule.project).smartInvokeLater {
            myFixture.type('.')
            myFixture.checkResult("class Some { void someMethod() { System.out.<caret>")
        }
    }

    fun testPackage() {
        myFixture.configureByText("Foo.java", "import java.langg<caret>")
        DumbService.getInstance(myModule.project).smartInvokeLater {
            myFixture.type('.')
            myFixture.checkResult("import java.lang.<caret>")
        }
    }

    fun testClassFromDependency() {
        PsiTestUtil.addLibrary(myModule, testDataDirPath + dependencyForTestingPath)
        myFixture.configureByText("Foo.java", "class Some { UniqueLikeASnowflakee<caret>}")
        DumbService.getInstance(myModule.project).smartInvokeLater {
            myFixture.type(' ')
            myFixture.checkResult("class Some { UniqueLikeASnowflake <caret>}")
        }
    }

    fun testMethodFromDependency() {
        PsiTestUtil.addLibrary(myModule, testDataDirPath + dependencyForTestingPath)
        myFixture.configureByText("Foo.java", "class Some { void func() { new UniqueLikeASnowflake().publicMethodDanceWithMew<caret>}}")
        DumbService.getInstance(myModule.project).smartInvokeLater {
            myFixture.type('(')
            myFixture.checkResult("class Some { void func() { new UniqueLikeASnowflake().publicMethodDanceWithMe(<caret>}}")
        }
    }

    fun testFieldFromDependency() {
        PsiTestUtil.addLibrary(myModule, testDataDirPath + dependencyForTestingPath)
        myFixture.configureByText("Foo.java", "class Some { void func() { new UniqueLikeASnowflake().privateFieldOhSoPrivatt<caret>}}")
        DumbService.getInstance(myModule.project).smartInvokeLater {
            myFixture.type(' ')
            myFixture.checkResult("class Some { void func() { new UniqueLikeASnowflake().privateFieldOhSoPrivate <caret>}}")
        }
    }

    fun testPackageFromDependency() {
        PsiTestUtil.addLibrary(myModule, testDataDirPath + dependencyForTestingPath)
        myFixture.configureByText("Foo.java", "import project.testing.uniquepackagenameololoo<caret>")
        DumbService.getInstance(myModule.project).smartInvokeLater {
            myFixture.type(';')
            myFixture.checkResult("import project.testing.uniquepackagenameololo;<caret>")
        }
    }

    fun testClassFromOtherFile() {
        myFixture.testDataPath = testDataDirPath
        myFixture.copyFileToProject(classForTestingPath)
        myFixture.configureByText("Foo.java", "class Some { UniqueLikeASnowflakee<caret>}")
        DumbService.getInstance(myModule.project).smartInvokeLater {
            myFixture.type(' ')
            myFixture.checkResult("class Some { UniqueLikeASnowflake <caret>}")
        }
    }

    fun testMethodFromOtherFile() {
        myFixture.testDataPath = testDataDirPath
        myFixture.copyFileToProject(classForTestingPath)
        myFixture.configureByText("Foo.java", "class Some { void func() { new UniqueLikeASnowflake().publicMethodDanceWithMew<caret>}}")
        DumbService.getInstance(myModule.project).smartInvokeLater {
            myFixture.type('(')
            myFixture.checkResult("class Some { void func() { new UniqueLikeASnowflake().publicMethodDanceWithMe(<caret>}}")
        }
    }

    fun testFieldFromOtherFile() {
        myFixture.testDataPath = testDataDirPath
        myFixture.copyFileToProject(classForTestingPath)
        myFixture.configureByText("Foo.java", "class Some { void func() { new UniqueLikeASnowflake().privateFieldOhSoPrivatt<caret>}}")
        DumbService.getInstance(myModule.project).smartInvokeLater {
            myFixture.type(' ')
            myFixture.checkResult("class Some { void func() { new UniqueLikeASnowflake().privateFieldOhSoPrivate <caret>}}")
        }
    }

    fun testPackageFromOtherFile() {
        myFixture.testDataPath = testDataDirPath
        myFixture.copyFileToProject(classForTestingPath)
        myFixture.configureByText("Foo.java", "import project.testing.uniquepackagename<caret>")
        DumbService.getInstance(myModule.project).smartInvokeLater {
            myFixture.type(';')
            myFixture.checkResult("import project.testing.uniquepackagename;<caret>")
        }
    }
}