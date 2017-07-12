package ru.jetbrains.yaveyn.fuzzysearch.test.search.big

import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.builders.JavaModuleFixtureBuilder
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import com.intellij.testFramework.fixtures.JavaTestFixtureFactory
import com.jetbrains.typofixer.search.DLSearcher
import org.junit.Test
import java.io.File


/**
 * @author bronti.
 */
class BigSearchTest {

    private val testDataDir = File("testData")
    private val currentTestResultsDir = File(File(testDataDir, "testResults"), DLSearcher.VERSION.toString())
    private var resultLoggingNeeded = !currentTestResultsDir.exists()

    private val precisionResults = File(currentTestResultsDir, "precision.txt")

    private val myProject: Project
    private val projectBuilder = IdeaTestFixtureFactory.getFixtureFactory().createFixtureBuilder("prjct")
    private val myFixture = JavaTestFixtureFactory.getFixtureFactory().createCodeInsightFixture(projectBuilder.fixture)
    private val searcher: DLSearcher

    private val ANSI_RESET = "\u001B[0m"
    private val ANSI_RED = "\u001B[31m"

    private val words = listOf("k", "l", "z", "in", "on", "zp", "jva", "tst", "zqp", "java", "goto", "hzwe", "langg", "retrun", "Stirng", "biggest", "morebiggest")

    init {
        myFixture.testDataPath = testDataDir.canonicalPath
        projectBuilder.addModule(JavaModuleFixtureBuilder::class.java)
        myFixture.setUp()

        myProject = myFixture.project

        val dependencies = testDataDir.walk().filter { it.isFile && it.extension == "jar" }.toList()
        dependencies.forEach { PsiTestUtil.addLibrary(myFixture.module, it.canonicalPath) }

        searcher = myProject.getComponent(DLSearcher::class.java)
        searcher.forceIndexRefreshing()
        // 560422
        println("index size: ${searcher.index.size}")

        if (resultLoggingNeeded) {
            currentTestResultsDir.mkdir()
            precisionResults.createNewFile()
        }
    }

    @Test
    fun testPrecision() {
        // todo: clear index (??)
        // todo: generate words
        // todo: different lengths
        words.forEach { doPrecisionTest(it, listOf(1.0, 1.0, 0.8, 0.8)) }
        resultLoggingNeeded = false
    }

    private fun doPrecisionTest(str: String, precs: List<Double>) {
        val (preciseResult, result) = DumbService.getInstance(myProject).runReadActionInSmartMode(Computable {
            val psiFile = null
            val preciseResult = searcher.search(str, psiFile, true)
            val result = searcher.search(str, psiFile)
            Pair(preciseResult, result)
        })

        assert(searcher.index.contains("UniqueLikeASnowflake"))
        assert(searcher.index.contains("privateMethod666"))

//        println("index size: ${searcher.index.size}")
        checkPrecision(preciseResult, result, str, precs)
    }

    private fun checkPrecision(preciseResult: Map<Int, List<String>>, result: Map<Int, List<String>>, word: String, precs: List<Double>) {
        fun handle(getSize: (Map<Int, List<String>>) -> Int,
                   expectedPrecision: Double,
                   toOutput: (String) -> String): String {
            val size = getSize(result)
            val preciseSize = getSize(preciseResult)
            val precision =
                    if (preciseSize == size) 1.0
                    else if (preciseSize == 0) throw IllegalStateException()
                    else size.toDouble() / preciseSize
            val rawOutput = toOutput("${"%.2f".format(precision)} ($size / $preciseSize)")
            print(if (precision < expectedPrecision) (ANSI_RED + rawOutput + ANSI_RESET) else rawOutput)
            return rawOutput
//            assert(precision >= expectedPrecision)
        }

        print(word + ": ")
        val output =
                word + ": " +
                        handle({ it[0]?.size ?: 0 }, precs[0], { "$it, " }) +
                        handle({ it[1]?.size ?: 0 }, precs[1], { "$it, " }) +
                        handle({ it[2]?.size ?: 0 }, precs[2], { "$it. " }) +
                        handle({ simplifySearchResult(it).size }, precs[3], { "total: $it." })
        println()
        if (resultLoggingNeeded) {
            precisionResults.appendText(output + "\n")
        }
    }

    private fun simplifySearchResult(result: Map<Int, List<String>>) = result.entries.flatMap { it.value }
}
