package ru.jetbrains.yaveyn.fuzzysearch.test.search.quality

import com.intellij.openapi.project.DumbService
import com.intellij.openapi.util.Computable
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.LightPlatformCodeInsightFixtureTestCase
import com.jetbrains.typofixer.search.DLSearcher
import com.jetbrains.typofixer.search.Searcher
import com.jetbrains.typofixer.search.index.CombinedIndex
import com.jetbrains.typofixer.searcher
import org.junit.Ignore
import org.junit.Test
import java.io.File
import kotlin.system.measureTimeMillis


/**
 * @author bronti.
 */
@Ignore
class GlobalQualityTest : LightPlatformCodeInsightFixtureTestCase() {

    private val testDataDir = File("testData")
    private val currentTestResultsDir = File(File(testDataDir, "testResults"), DLSearcher.VERSION.toString())

    private val precisionResults = File(currentTestResultsDir, "precision.txt")
    private val refreshingResults = File(currentTestResultsDir, "refreshing.txt")
    private val timeResults = File(currentTestResultsDir, "time.txt")

    private lateinit var searcher: Searcher

    private val ANSI_RESET = "\u001B[0m"
    private val ANSI_RED = "\u001B[31m"

    override fun setUp() {
        super.setUp()
        myFixture.testDataPath = testDataDir.canonicalPath

        searcher = project.searcher
        searcher.getIndex().canRefreshGlobal = false
        val dependencies = testDataDir.walk().filter { it.isFile && it.extension == "jar" }.sortedBy { it.name }.toList()
//        val dependenciesScope = myModule.getModuleWithDependenciesAndLibrariesScope(false)
        dependencies.forEach {
            //            JavaPsiFacade.getInstance(project).findPackage(it.)
            PsiTestUtil.addLibrary(myFixture.module, it.canonicalPath)
            println(it.name)
        }
        searcher.getIndex().canRefreshGlobal = true

        // todo: (?)
//        if (!DumbService.isDumb(project)) {
//            FileBasedIndex.getResolver().ensureUpToDate(StubUpdatingIndex.INDEX_ID, project, null)
//            FileBasedIndex.getResolver().ensureUpToDate<TodoIndexEntry>(TodoIndex.NAME, project, null)
//        }

        DumbService.getInstance(project).waitForSmartMode()
        searcher.forceGlobalIndexRefreshing()
        // 589671

        println(searcher.getIndex().getGlobalSize())

        assert(searcher.getIndex().contains("UniqueLikeASnowflake"))
        assert(searcher.getIndex().contains("privateMethod666"))

        if (!currentTestResultsDir.exists()) {
            currentTestResultsDir.mkdir()
        }
    }

    @Test
            //    @Ignore
    fun testRefreshGlobalIndex() {
        val times = 20
        val result = measureTimeMillis({ (1..times).forEach { searcher.forceGlobalIndexRefreshing() } }).toDouble() / times.toDouble()
        val resultLoggingNeeded = !refreshingResults.exists()
        if (resultLoggingNeeded) {
            refreshingResults.createNewFile()
            refreshingResults.appendText("$result\n")
            refreshingResults.appendText("index size: ${searcher.getStatistics().first}")
        }
        println("index size: ${searcher.getStatistics().first}")
        println(result)
    }

    @Test
            //    @Ignore
    fun testPrecision() {
        val resultLoggingNeeded = !precisionResults.exists()
        if (resultLoggingNeeded) {
            precisionResults.createNewFile()
            precisionResults.appendText("index size: ${searcher.getStatistics().first}\n")
        }
        println("index size: ${searcher.getStatistics().first}")
        // todo: clear index (??)
        // todo: generate words
        // todo: different lengths
        val words = listOf("k", "l", "z", "in", "on", "zp", "jva", "tst",
                "zqp", "java", "goto", "hzwe", "langg", "retrun", "Stirng")
        words.forEach { doPrecisionTest(it, listOf(1.0, 1.0, 0.8, 0.8), resultLoggingNeeded) }
    }

    @Test
            //    @Ignore
    fun testTime() {
        val resultLoggingNeeded = !timeResults.exists()
        if (resultLoggingNeeded) {
            timeResults.createNewFile()
            timeResults.appendText("index size: ${searcher.getStatistics().first}\n")
        }
        println("index size: ${searcher.getStatistics().first}")
        val chars = ('a'..'z').map { it.toString() }
        val chars2 = chars.flatMap { c1 -> ('a'..'z').map { c2 -> "$c1$c2" } }
        val chars3 = chars2.flatMap { c1 -> ('a'..'z').map { c2 -> "$c1$c2" } }

        fun maxTime(words: List<String>): Long {
            val results = words.map { doTimeTest(it) }
            return results.maxBy { it }!!
        }

        fun flush(title: String, time: Long) {
            val output = "$title. time: $time"
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
                "colorPane", "myFixture", "InsertActionSorter", "NnsertActionSorter", "sertActionSorter",
                "createNewAnnotation", "createNewAnotation", "createNewAonntation")
        words.forEach { flush(it, doTimeTest(it)) }
    }

    private fun doPrecisionTest(str: String, precs: List<Double>, resultLoggingNeeded: Boolean) {
        val (preciseResult, result) = DumbService.getInstance(project).runReadActionInSmartMode(Computable {
            val psiFile = null
            val preciseResult = searcher.findAll(str, psiFile, true)
            val result = searcher.findAll(str, psiFile, false)
            Pair(preciseResult, result)
        })

        checkPrecision(preciseResult, result, str, precs, resultLoggingNeeded)
    }

    private fun doTimeTest(str: String) = DumbService.getInstance(project).runReadActionInSmartMode(Computable {
        measureTimeMillis({
            searcher
                    .findClosest(null, str, CombinedIndex.IndexType.values(), { /* do nothing */ })
                    .sortedBy { project.searcher.distanceProvider.measure(str, it.word) }
                    .toList()
        })
    })

    private fun checkPrecision(preciseResult: Map<Double, List<String>>,
                               result: Map<Double, List<String>>,
                               word: String, precs: List<Double>,
                               resultLoggingNeeded: Boolean) {
        fun handle(getSize: (Map<Double, List<String>>) -> Int,
                   expectedPrecision: Double,
                   toOutput: (String) -> String): String {
            val size = getSize(result)
            val preciseSize = getSize(preciseResult)
            val precision = when (preciseSize) {
                size -> 1.0
                0 -> throw IllegalStateException()
                else -> size.toDouble() / preciseSize
            }
            val rawOutput = toOutput("${"%.2f".format(precision)} ($size / $preciseSize)")
            print(if (precision < expectedPrecision) (ANSI_RED + rawOutput + ANSI_RESET) else rawOutput)
            return rawOutput
//            assert(precision >= expectedPrecision)
        }

        print(word + ": ")
        val output =
                word + ": " +
                        handle({ it[0.0]?.size ?: 0 }, precs[0], { "$it, " }) +
                        handle({ it.entries.filter { Math.round(it.key) == 1L }.flatMap { it.value }.size }, precs[1], { "$it, " }) +
                        handle({ it.entries.filter { Math.round(it.key) == 2L }.flatMap { it.value }.size }, precs[2], { "$it. " }) +
                        handle({ simplifySearchResult(it).size }, precs[3], { "total: $it." })
        println()
        if (resultLoggingNeeded) {
            precisionResults.appendText(output + "\n")
        }
    }

    private fun simplifySearchResult(result: Map<*, List<String>>) = result.entries.flatMap { it.value }
}
