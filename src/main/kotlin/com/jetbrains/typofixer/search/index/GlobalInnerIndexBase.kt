package com.jetbrains.typofixer.search.index

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.util.ProgressIndicatorUtils
import com.intellij.openapi.progress.util.ReadTask
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.jetbrains.typofixer.TypoFixerComponent
//todo: TestOnly doesn't work
import com.jetbrains.typofixer.search.signature.Signature
//todo: TestOnly doesn't work
import org.jetbrains.annotations.TestOnly
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.util.*
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream


abstract class GlobalInnerIndexBase(val project: Project, signature: Signature) : InnerIndex(signature) {

    @Volatile
    protected var lastRefreshingTask: CollectProjectNamesBase? = null
    private val index = HashMap<Int, IndexEntry>()

    protected abstract fun getRefreshingTask(): CollectProjectNamesBase

    fun isUsable() = lastRefreshingTask == null

    override fun clear() = index.clear()
    override fun getSize() = synchronizedAccess { index.entries.sumBy { it.value.getSize() } }
    override fun getAll(signatures: Set<Int>) = synchronizedAccess { super.getAll(signatures) }
    override fun addAll(strings: Set<String>) = synchronized(this) { super.addAll(strings) }

    fun refresh() {
        val refreshingTask = getRefreshingTask()
        synchronized(this) {
            lastRefreshingTask = refreshingTask
            index.clear()
        }
        project.getComponent(TypoFixerComponent::class.java).onSearcherStatusMaybeChanged()
        DumbService.getInstance(project).smartInvokeLater {
            if (project.isInitialized) {
                ProgressIndicatorUtils.scheduleWithWriteActionPriority(refreshingTask)
            }
        }
    }

    override fun getWithDefault(signature: Int): HashSet<String> {
        val result = index[signature]?.getAll() ?: return hashSetOf()
        return result
    }

    override fun addAll(signature: Int, strings: Set<String>) {
        if (index[signature] == null) {
            index[signature] = IndexEntry()
        }
        index[signature]!!.addAll(strings)
    }

    private fun <T> synchronizedAccess(doGet: () -> T) = synchronized(this) {
        if (isUsable()) doGet()
        else throw TriedToAccessIndexWhileItIsRefreshing()
    }


    class TriedToAccessIndexWhileItIsRefreshing : RuntimeException()


    // todo: make it lazy
    private class IndexEntry {

        private var wordCount: Int = 0

        // todo: private
        private var outputBytes = ByteArrayOutputStream()
        private val outputStream = ObjectOutputStream(GZIPOutputStream(outputBytes))
        private var bytes: ByteArray? = null

        fun addAll(strings: Set<String>) {
            strings.forEach { outputStream.writeObject(it) }
            wordCount += strings.size
        }

        fun prepareToBeRead() {
            outputStream.close()
            bytes = outputBytes.toByteArray()
        }

        fun getAll(): HashSet<String> {
            val inputStream = ObjectInputStream(GZIPInputStream(ByteArrayInputStream(bytes!!)))
            val result = hashSetOf<String>()
            for (i in 1..wordCount) {
                result.add(inputStream.readObject() as String)
            }
            return result
        }

        fun getSize() = getAll().size

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

        protected fun shouldCollect(indicator: ProgressIndicator?): Boolean {
            indicator?.checkCanceled()
            if (DumbService.isDumb(project) || !isCurrentRefreshingTask()) {
                done = true
            }
            return !done
        }

        protected fun checkedCollect(indicator: ProgressIndicator?, isCollected: Boolean, getToCollect: () -> Set<String>, markCollected: () -> Unit) {
            if (isCollected || !shouldCollect(indicator)) return
            synchronized(this) {
                if (shouldCollect(indicator) && !isCollected) {
                    getToCollect().addAllToIndex()
                    markCollected()
                }
            }
        }

        protected fun onCompletionDone(indicator: ProgressIndicator?) {
            // todo: check that index is refreshing after each stub index refreshment
            if (DumbService.isDumb(project) || shouldCollect(indicator)) {
                synchronized(this) {
                    // todo: here?
                    index.entries.forEach { it.value.prepareToBeRead() }
                    if (isCurrentRefreshingTask()) lastRefreshingTask = null
                }
            }

            if (this@GlobalInnerIndexBase.isUsable()) {
                project.getComponent(TypoFixerComponent::class.java).onSearcherStatusMaybeChanged()
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
        synchronized(this) {
            lastRefreshingTask = refreshingTask
            index.clear()
        }
        DumbService.getInstance(project).runReadActionInSmartMode {
            refreshingTask.waitForRefreshing()
        }
    }

    @TestOnly
    override fun contains(str: String) = synchronized(this) { index[signature.get(str)]?.contains(str) ?: false }
}