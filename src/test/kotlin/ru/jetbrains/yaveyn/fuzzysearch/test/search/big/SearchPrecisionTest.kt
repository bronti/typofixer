package ru.jetbrains.yaveyn.fuzzysearch.test.search.big

import com.intellij.openapi.project.DumbService
import com.intellij.openapi.util.Computable
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.LightPlatformCodeInsightFixtureTestCase
import com.jetbrains.typofixer.search.DLSearcher
import org.junit.Test
import java.io.File


/**
 * @author bronti.
 */
class SearchPrecisionTest : LightPlatformCodeInsightFixtureTestCase() {

    private val testDataDirPath = "./testData/"
    private val projectForTestingPath = "projectfortesting-1.0-SNAPSHOT.jar"
    private val jars = testDataDirPath + "jars/"

    @Test
    fun testSimple() {
        doTest("retrun", listOf(File(testDataDirPath + projectForTestingPath)), listOf(1.0, 1.0, 1.0, 1.0))
    }

    @Test
    fun testBig() {
        val dependencies = File(jars).walk().filter { it.isFile && it.extension == "jar" }.toList()
        // todo: clear dependencies (!!!)
        doTest("retrun", dependencies, listOf(1.0, 1.0, 1.0, 1.0))
    }

    private fun doTest(str: String, dependencyPaths: List<File>, precs: List<Double>) {

        myFixture.configureByText("File.java", "")
        val myProject = myFixture.project
        val psiFile = myFixture.file

        dependencyPaths.forEach { PsiTestUtil.addLibrary(myFixture.module, it.canonicalPath) }
        println(myProject.name)
        println(myProject.basePath)

        var indexSize: Int = 0
        val (preciseResult, result) = DumbService.getInstance(myProject).runReadActionInSmartMode(Computable {
            val searcher = myProject.getComponent(DLSearcher::class.java)
            searcher.index.refreshGlobal(myProject)
            indexSize = searcher.index.globalSize
            val preciseResult = searcher.search(str, psiFile, true)
            val result = searcher.search(str, psiFile)
            Pair(preciseResult, result)
        })

        println(myProject.getComponent(DLSearcher::class.java).index.contains("UniqueLikeASnowflake"))
        println(myProject.getComponent(DLSearcher::class.java).index.contains("privateMethod666"))

        println("index size: $indexSize")
        println("index size: ${myProject.getComponent(DLSearcher::class.java).index.globalSize}")
        println("${myProject.getComponent(DLSearcher::class.java).getSearch(true).getCandidates(str).size}")
        println("${myProject.getComponent(DLSearcher::class.java).getSearch(false).getCandidates(str).size}")
        checkPrecision(preciseResult, result, str, precs)
    }

    private fun checkPrecision(preciseResult: Map<Int, List<String>>, result: Map<Int, List<String>>, word: String, precs: List<Double>) {
        fun handle(get: (Map<Int, List<String>>) -> List<String>,
                   expectedPrecision: Double,
                   toOutput: (Double) -> String) {
            val size = get(result).size.toDouble()
            val preciseSize = get(preciseResult).size.toDouble()
            val precision =
                    if (preciseSize == size) 1.0
                    else if (preciseSize == 0.0) throw IllegalStateException()
                    else size / preciseSize
            println(toOutput(precision))
            assert(precision >= expectedPrecision)
        }

        handle({ it[0] ?: listOf() }, precs[0], { "$word, error == 0: $it" })
        handle({ it[1] ?: listOf() }, precs[1], { "$word, error == 1: $it" })
        handle({ it[2] ?: listOf() }, precs[2], { "$word, error == 2: $it" })
        handle({ simplifySearchResult(it) }, precs[3], { "$word, all result: $it" })
    }

    private fun simplifySearchResult(result: Map<Int, List<String>>) = result.entries.flatMap { it.value }
}
