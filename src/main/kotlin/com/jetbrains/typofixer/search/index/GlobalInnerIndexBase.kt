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
import java.util.*


abstract class GlobalInnerIndexBase(val project: Project, signature: Signature) : InnerIndex(signature) {

    @Volatile
    protected var lastRefreshingTask: CollectProjectNamesBase? = null
    private val index = HashMap<Int, HashSet<String>>()

    override fun getSize() = synchronized(this) { index.entries.sumBy { it.value.size } }
    override fun clear() = index.clear()
    fun isUsable() = lastRefreshingTask == null

    protected abstract fun getRefreshingTask(): CollectProjectNamesBase

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

    override fun getAll(signatures: Set<Int>) = synchronized(this) {
        if (isUsable()) super.getAll(signatures)
        else throw TriedToAccessIndexWhileItIsRefreshing()
    }

    override fun addAll(strings: Set<String>) = synchronized(this) { super.addAll(strings) }

    override fun getWithDefault(signature: Int): HashSet<String> {
        val result = index[signature] ?: return hashSetOf()
        return result
    }

    override fun addAll(signature: Int, strings: Set<String>) {
        index[signature] = getWithDefault(signature)
        index[signature]!!.addAll(strings)
    }


    class TriedToAccessIndexWhileItIsRefreshing : RuntimeException()


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