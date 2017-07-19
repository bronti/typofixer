package ru.jetbrains.yaveyn.fuzzysearch.test.search.quality

import com.intellij.openapi.project.DumbService
import com.intellij.openapi.util.Computable
import com.intellij.testFramework.fixtures.LightPlatformCodeInsightFixtureTestCase
import com.jetbrains.typofixer.TypoFixerComponent
import com.jetbrains.typofixer.search.DLSearcher
import org.junit.Test
import java.io.File
import kotlin.system.measureTimeMillis

/**
 * @author bronti.
 */
class LocalQualityTest : LightPlatformCodeInsightFixtureTestCase() {

    private val testDataDir = File("testData")
    private val bigFile = File(testDataDir, "BigTestFile.java")

    private val currentTestResultsDir = File(File(testDataDir, "testResults"), DLSearcher.VERSION.toString())
    private val localTimeResults = File(currentTestResultsDir, "local_index_refreshing.txt")

    init {
        if (!currentTestResultsDir.exists()) {
            currentTestResultsDir.mkdir()
        }
    }

    @Test
    fun testLocalRefreshing() {
        val searcher = project.getComponent(TypoFixerComponent::class.java).searcher
        val resultLoggingNeeded = !localTimeResults.exists()
        if (resultLoggingNeeded) {
            localTimeResults.createNewFile()
            localTimeResults.appendText("index size: ${searcher.index.localSize}\n")
        }
        println("index size: ${searcher.index.localSize}")

        myFixture.configureByFile(bigFile.canonicalPath)

        val times = 1000
        val time = DumbService.getInstance(project).runReadActionInSmartMode(Computable {
            (1..times).map { measureTimeMillis({ searcher.forceLocalIndexRefreshing(myFixture.file) }) }.sum() / times.toDouble()
        })

        val output = time.toString()
        println(output)
        if (resultLoggingNeeded) {
            localTimeResults.appendText(output + "\n")
        }
    }
}