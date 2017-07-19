package ru.jetbrains.yaveyn.fuzzysearch.test.search.quality

import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import com.intellij.testFramework.builders.JavaModuleFixtureBuilder
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import com.intellij.testFramework.fixtures.JavaTestFixtureFactory
import com.jetbrains.typofixer.TypoFixerComponent
import com.jetbrains.typofixer.search.DLSearcher
import org.junit.Ignore
import org.junit.Test
import java.io.File
import kotlin.system.measureTimeMillis


/**
 * @author bronti.
 */
//@Ignore
class GlobalQualityTest {

    private val testDataDir = File("testData")
    private val currentTestResultsDir = File(File(testDataDir, "testResults"), DLSearcher.VERSION.toString())

    private val precisionResults = File(currentTestResultsDir, "precision.txt")
    private val refreshingResults = File(currentTestResultsDir, "refreshing.txt")
    private val timeResults = File(currentTestResultsDir, "time.txt")

    private val myProject: Project
    private val projectBuilder = IdeaTestFixtureFactory.getFixtureFactory().createFixtureBuilder("prjct")
    private val myFixture = JavaTestFixtureFactory.getFixtureFactory().createCodeInsightFixture(projectBuilder.fixture)
    private val searcher: DLSearcher

    private val ANSI_RESET = "\u001B[0m"
    private val ANSI_RED = "\u001B[31m"

    init {
        myFixture.testDataPath = testDataDir.canonicalPath
        projectBuilder.addModule(JavaModuleFixtureBuilder::class.java)
        myFixture.setUp()

        myProject = myFixture.project

        val dependencies = testDataDir.walk().filter { it.isFile && it.extension == "jar" }.toList()
//        dependencies.forEach { PsiTestUtil.addLibrary(myFixture.module, it.canonicalPath) }

        searcher = myProject.getComponent(TypoFixerComponent::class.java).searcher
        searcher.forceGlobalIndexRefreshing()
        // 560422

        if (!currentTestResultsDir.exists()) {
            currentTestResultsDir.mkdir()
        }
    }

    @Test
//    @Ignore
    fun testRefreshGlobalIndex() {
        val times = 50
        val result = measureTimeMillis({ (1..times).forEach { searcher.forceGlobalIndexRefreshing() } }).toDouble() / times.toDouble()
        val resultLoggingNeeded = !refreshingResults.exists()
        if (resultLoggingNeeded) {
            refreshingResults.createNewFile()
            refreshingResults.appendText("$result\n")
            refreshingResults.appendText("index size: ${searcher.index.size}")
        }
        println("index size: ${searcher.index.size}")
        println(result)
    }

    @Test
    @Ignore
    fun testPrecision() {
        val resultLoggingNeeded = !precisionResults.exists()
        if (resultLoggingNeeded) {
            precisionResults.createNewFile()
            precisionResults.appendText("index size: ${searcher.index.size}\n")
        }
        println("index size: ${searcher.index.size}")
        // todo: clear index (??)
        // todo: generate words
        // todo: different lengths
        val words = listOf("k", "l", "z", "in", "on", "zp", "jva", "tst",
                "zqp", "java", "goto", "hzwe", "langg", "retrun", "Stirng")
        words.forEach { doPrecisionTest(it, listOf(1.0, 1.0, 0.8, 0.8), resultLoggingNeeded) }
    }

    @Test
    @Ignore
    fun testTime() {
        val resultLoggingNeeded = !timeResults.exists()
        if (resultLoggingNeeded) {
            timeResults.createNewFile()
            timeResults.appendText("index size: ${searcher.index.size}\n")
        }
        println("index size: ${searcher.index.size}")
        val chars = ('a'..'z').map { it.toString() }
        val chars2 = chars.flatMap { c1 -> ('a'..'z').map { c2 -> "$c1$c2" } }
        val chars3 = chars2.flatMap { c1 -> ('a'..'z').map { c2 -> "$c1$c2" } }

        fun maxTime(words: List<String>): Pair<Long, Pair<Int, Int>> {
            val results = words.map { doTimeTest(it) }
            val maxTime = results.maxBy { it.first }!!.first
            val maxCandidates = Pair(results.maxBy { it.second.first }!!.second.first, results.maxBy { it.second.second }!!.second.second)
            return Pair(maxTime, maxCandidates)
        }

        fun flush(title: String, pr: Pair<Long, Pair<Int, Int>>) {
            val output = "$title. time: ${pr.first} candidates: ${pr.second}"
            println(output)
            if (resultLoggingNeeded) {
                timeResults.appendText(output + "\n")
            }
        }

        flush("1 char length(max)", maxTime(chars))
        flush("2 char length(max)", maxTime(chars2))
        flush("3 char length(max)", maxTime(chars3))

        val words = listOf("java", "howe", "strr", "parn", "oloo", "javv", "java",
                "goto", "hzwe", "langg", "retrun", "Stirng", "biggest",
                "morebiggest", "sequentialLighhtFixturreOneTime",
                "ClassWithVeryUglyName", "worstCaseScennario", "hubaDubaDoodle",
                "pinkHairForever", "imOutOfStupidNames", "bananza", "willBeNextDoctorWhoAWoman",
                "colorPane", "myFixture")
        words.forEach { flush(it, doTimeTest(it)) }
    }

    private fun doPrecisionTest(str: String, precs: List<Double>, resultLoggingNeeded: Boolean) {
        val (preciseResult, result) = DumbService.getInstance(myProject).runReadActionInSmartMode(Computable {
            val psiFile = null
            val preciseResult = searcher.search(str, psiFile, true)
            val result = searcher.search(str, psiFile)
            Pair(preciseResult, result)
        })

        assert(searcher.index.contains("UniqueLikeASnowflake"))
        assert(searcher.index.contains("privateMethod666"))

//        println("index size: ${searcher.index.size}")
        checkPrecision(preciseResult, result, str, precs, resultLoggingNeeded)
    }

    private fun doTimeTest(str: String): Pair<Long, Pair<Int, Int>> {
        var candidates: Pair<Int, Int> = Pair(0, 0)
        val time = DumbService.getInstance(myProject).runReadActionInSmartMode(Computable {
            measureTimeMillis({ candidates = searcher.findClosestWithInfo(str, null).second })
        })
        return Pair(time, candidates)
    }

    private fun checkPrecision(preciseResult: Map<Int, List<String>>,
                               result: Map<Int, List<String>>,
                               word: String, precs: List<Double>,
                               resultLoggingNeeded: Boolean) {
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
