package com.jetbrains.typofixer.search.index

//todo: TestOnly doesn't work
//todo: TestOnly doesn't work
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
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


abstract class GlobalInnerIndexBase(val project: Project, signature: Signature) : InnerIndex(signature) {

    private val index = HashMap<Int, IndexEntry>()

    @Volatile
    protected var lastRefreshingTask: CollectProjectNamesBase? = null
    protected abstract fun getRefreshingTask(): CollectProjectNamesBase

    fun isUsable() = lastRefreshingTask == null

    override fun getSize() = synchronizedAccess { index.entries.sumBy { it.value.getSize() } }
    override fun getAll(signatures: Set<Int>) = synchronizedAccess { super.getAll(signatures) }

    override fun addAll(strings: Set<String>) = synchronized(this@GlobalInnerIndexBase) { super.addAll(strings) }

    fun refresh() {
        val refreshingTask = getRefreshingTask()
        synchronized(this@GlobalInnerIndexBase) {
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

    override fun getWithDefault(signature: Int) = index[signature]?.getAll() ?: emptySequence()

    override fun addAll(signature: Int, strings: Set<String>) {
        if (index[signature] == null) {
            index[signature] = IndexEntry()
        }
        index[signature]!!.addAll(strings)
    }

    private fun <T> synchronizedAccess(doGet: () -> T): T {
        if (!isUsable()) throw TriedToAccessIndexWhileItIsRefreshing()
        synchronized(this@GlobalInnerIndexBase) {
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


    inner abstract protected class CollectProjectNamesBase : ReadTask() {

        var done = false
            protected set

        override fun runBackgroundProcess(indicator: ProgressIndicator): Continuation? {
            ApplicationManager.getApplication().runReadAction { performCollection(indicator) }
            return null
        }

        override fun onCanceled(p0: ProgressIndicator) {
            if (!done) {
                ProgressIndicatorUtils.scheduleWithWriteActionPriority(this)
            }
        }

        private fun performCollection(indicator: ProgressIndicator?) {
            if (project.isInitialized) {
                doCollect(indicator)
            }
            onCompletionDone(indicator)
        }

        abstract fun doCollect(indicator: ProgressIndicator?)

        protected fun isCurrentRefreshingTask() = this === lastRefreshingTask

        // once false -> always false after that
        protected fun shouldCollect(indicator: ProgressIndicator?): Boolean {
            indicator?.checkCanceled()
            if (DumbService.isDumb(project) || !isCurrentRefreshingTask()) {
                done = true
            }
            return !done
        }

        protected fun checkedCollect(indicator: ProgressIndicator?, isCollected: Boolean, getToCollect: () -> Set<String>, markCollected: () -> Unit) {
            if (isCollected || !shouldCollect(indicator)) return
            synchronized(this@GlobalInnerIndexBase) {
                if (shouldCollect(indicator) && !isCollected) {
                    getToCollect().addAllToIndex()
                    markCollected()
                }
            }
        }

        protected fun onCompletionDone(indicator: ProgressIndicator?) {
            var toSynchronize: List<IndexEntry>? = null
            synchronized(this@GlobalInnerIndexBase) {
                if (shouldCollect(indicator)) {
                    // todo: toLis -> toList rolles back
                    toSynchronize = index.values.toList()
                }
            }
            if (shouldCollect(indicator)) {
                val doCompress = Thread { toSynchronize!!.forEach { it.compress() } }
                doCompress.priority = Thread.MIN_PRIORITY
                doCompress.start()
            }

            // todo: check that index is refreshing after each stub index refreshment
            if (DumbService.isDumb(project) || shouldCollect(indicator)) {
                // todo: synchronized rolls back
                synchronized(this@GlobalInnerIndexBase) {
                    if (isCurrentRefreshingTask()) lastRefreshingTask = null
                }
            }

            if (this@GlobalInnerIndexBase.isUsable()) {
                project.typoFixerComponent.onSearcherStatusMaybeChanged()
            }

            done = true
        }

        // can be interrupted by dumb mode
        @TestOnly
        fun waitForRefreshing() {
            while (!done) {
                performCollection(null)
            }
        }
    }

    @TestOnly
    fun waitForRefreshing() {
        val refreshingTask = getRefreshingTask()
        synchronized(this@GlobalInnerIndexBase) {
            lastRefreshingTask = refreshingTask
            index.clear()
        }
        DumbService.getInstance(project).runReadActionInSmartMode {
            refreshingTask.waitForRefreshing()
        }
    }

    @TestOnly
    override fun contains(str: String) = synchronizedAccess { index[signature.get(str)]?.contains(str) ?: false }
}