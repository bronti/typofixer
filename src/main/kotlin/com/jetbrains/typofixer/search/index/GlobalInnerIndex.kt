package com.jetbrains.typofixer.search.index

import com.intellij.openapi.progress.util.ProgressIndicatorUtils
import com.intellij.openapi.progress.util.ReadTask
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.jetbrains.typofixer.search.signature.Signature
import com.jetbrains.typofixer.typoFixerComponent
import org.jetbrains.annotations.TestOnly
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream


class GlobalInnerIndex(
        val project: Project,
        signature: Signature,
        private val getRefreshingTask: (GlobalInnerIndex) -> GlobalIndexRefreshingTaskBase
) : InnerIndex(signature) {

    private val index = HashMap<Int, IndexEntry>()

    @Volatile
    private var lastRefreshingTask: GlobalIndexRefreshingTaskBase? = null

    fun isUsable() = lastRefreshingTask == null
    fun isCurrentRefreshingTask(task: ReadTask) = task === lastRefreshingTask
    fun clearRefreshingTask(task: ReadTask) = synchronized(this) {
        if (isCurrentRefreshingTask(task)) lastRefreshingTask = null
    }

    override fun getSize() = synchronizedAccess { index.entries.sumBy { it.value.getSize() } }
    override fun getAll(signatures: Set<Int>) = synchronizedAccess { super.getAll(signatures) }

    override fun addAll(strings: Set<String>) = synchronized(this@GlobalInnerIndex) { super.addAll(strings) }

    fun refresh() {
        val refreshingTask = getRefreshingTask(this)
        synchronized(this@GlobalInnerIndex) {
            lastRefreshingTask = refreshingTask
            index.clear()
        }
        project.typoFixerComponent.onSearcherStatusMaybeChanged()
        DumbService.getInstance(project).smartInvokeLater {
            if (project.isInitialized) {
                ProgressIndicatorUtils.scheduleWithWriteActionPriority(refreshingTask)
            }
        }
    }

    fun startEntriesCompression() {
        var toSynchronize: List<IndexEntry>? = null
        synchronized(this) { toSynchronize = index.values.toList() }
        val doCompress = Thread { toSynchronize!!.forEach { it.compress() } }
        doCompress.priority = Thread.MIN_PRIORITY
        doCompress.start()
    }

    override fun getWithDefault(signature: Int) = index[signature]?.getAll() ?: emptySequence()

    override fun addAll(signature: Int, strings: Set<String>) {
        if (index[signature] == null) {
            index[signature] = IndexEntry()
        }
        index[signature]!!.addAll(strings)
    }

    private fun <T> synchronizedAccess(doGet: () -> T): T {
        if (!isUsable()) throw TriedToAccessIndexWhileItIsRefreshing()
        synchronized(this@GlobalInnerIndex) {
            if (isUsable()) return doGet()
            else throw TriedToAccessIndexWhileItIsRefreshing()
        }
    }


    class TriedToAccessIndexWhileItIsRefreshing : RuntimeException()


    // todo: make it lazy
    private inner class IndexEntry {

        private var wordCount: Int = 0

        @Volatile
        var isCompressed = false
            private set

        @Volatile
        var compressionInitiated = AtomicBoolean(false)

        private var content: HashSet<String>? = hashSetOf()
        private var bytes: ByteArray? = null

        fun addAll(strings: Set<String>) {
            content!!.addAll(strings)
            wordCount = content!!.size
        }

        fun compress() {
            if (!compressionInitiated.compareAndSet(/* expect = */false, /* update = */true)) return

            val outputBytes = ByteArrayOutputStream()
            val outputStream = ObjectOutputStream(GZIPOutputStream(outputBytes))
            content!!.forEach { outputStream.writeObject(it) }
            outputStream.close()
            val newBytes = outputBytes.toByteArray()
            // todo: can do without lock
            synchronized(this@IndexEntry) {
                if (!isCompressed) {
                    bytes = newBytes
                    content = null
                    isCompressed = true
                }
            }
        }

        fun getAll(): Sequence<String> {
            synchronized(this@IndexEntry) {
                if (!isCompressed) {
                    return content!!.asSequence().constrainOnce()
                }
            }
            if (!isCompressed) throw IllegalStateException()
            val inputStream = ObjectInputStream(GZIPInputStream(ByteArrayInputStream(bytes!!)))


            return generateSequence { inputStream.readObject() as String }.take(wordCount)
        }

        fun getSize() = wordCount

        fun contains(str: String) = getAll().contains(str)
    }

    @TestOnly
    fun waitForRefreshing() {
        val refreshingTask = getRefreshingTask(this)
        synchronized(this@GlobalInnerIndex) {
            lastRefreshingTask = refreshingTask
            index.clear()
        }
        DumbService.getInstance(project).runReadActionInSmartMode {
            refreshingTask.waitForRefreshing()
        }
    }

    @TestOnly
    override fun contains(str: String) = synchronizedAccess { index[signature.get(str)]?.contains(str) == true }
}