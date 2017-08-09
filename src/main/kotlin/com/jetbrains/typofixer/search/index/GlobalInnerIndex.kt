package com.jetbrains.typofixer.search.index

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.util.ProgressIndicatorUtils
import com.intellij.openapi.progress.util.ReadTask
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaDirectoryService
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiDirectory
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache
import com.jetbrains.typofixer.TypoFixerComponent
import com.jetbrains.typofixer.search.signature.Signature
import org.jetbrains.annotations.TestOnly
import java.util.*


class GlobalInnerIndex(val project: Project, signature: Signature) : InnerIndex(signature) {

    override fun getSize() = synchronized(this) { index.entries.sumBy { it.value.size } }

    override fun clear() = index.clear()

    private val index = HashMap<Int, HashSet<String>>()

    @Volatile
    private var lastRefreshingTask: CollectProjectNames? = null

    fun isUsable() = lastRefreshingTask == null

    fun refresh() {
        val refreshingTask = CollectProjectNames(project)
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

    inner private class CollectProjectNames(val project: Project) : ReadTask() {

        private fun isCurrentRefreshingTask() = this === lastRefreshingTask

        private var methodNamesCollected = false
        private var fieldNamesCollected = false
        private var classNamesCollected = false
        private val dirsToCollectPackages = ArrayList<PsiDirectory>()
        private val packageNames = ArrayList<String>()
        var done = false
            private set

        override fun runBackgroundProcess(indicator: ProgressIndicator): Continuation? {
            ApplicationManager.getApplication().runReadAction {
                doCollect(indicator)
            }
            return null
        }

        private fun doCollect(indicator: ProgressIndicator?) {
            if (!project.isInitialized) return

            val cache = PsiShortNamesCache.getInstance(project)

            fun shouldCollect(): Boolean {
                indicator?.checkCanceled()
                if (DumbService.isDumb(project) || !isCurrentRefreshingTask()) {
                    done = true
                }
                return !done
            }

            fun checkedCollect(isCollected: Boolean, getToCollect: () -> Array<String>, markCollected: () -> Unit) {
                if (!shouldCollect() || isCollected) return
                synchronized(this) {
                    if (shouldCollect() && !isCollected) {
                        addAll(getToCollect().toSet())
                    } else return@checkedCollect
                }
                markCollected()
            }

            checkedCollect(methodNamesCollected, { cache.allMethodNames }) { methodNamesCollected = true }
            checkedCollect(fieldNamesCollected, { cache.allFieldNames }) { fieldNamesCollected = true }

            // todo: language specific (?) (kotlin bug)
            checkedCollect(classNamesCollected, { cache.allClassNames }) { classNamesCollected = true }

            val initialPackage = JavaPsiFacade.getInstance(project).findPackage("")
            val javaDirService = JavaDirectoryService.getInstance()
            val scope = GlobalSearchScope.allScope(project)

            dirsToCollectPackages.addAll(
                    initialPackage?.getDirectories(scope)?.flatMap { it.subdirectories.toList() } ?: emptyList()
            )

            while (shouldCollect() && dirsToCollectPackages.isNotEmpty()) {
                val subDir = dirsToCollectPackages.last()
                val subPackage = javaDirService.getPackage(subDir)
                val subPackageName = subPackage?.name
                if (subPackageName != null && subPackageName.isNotBlank()) {
                    packageNames.add(subPackageName)
                }
                dirsToCollectPackages.removeAt(dirsToCollectPackages.size - 1)
                if (subPackage != null) {
                    // todo: filter resources (?)
                    dirsToCollectPackages.addAll(subDir.subdirectories)
                }
            }

            if (shouldCollect() && packageNames.isNotEmpty()) {
                synchronized(this) {
                    if (shouldCollect()) addAll(packageNames.toSet())
                    packageNames.clear()
                }
            }

            // todo: check that index is refreshing after each stub index refreshment
            if (shouldCollect()) {
                synchronized(this) {
                    if (isCurrentRefreshingTask()) lastRefreshingTask = null
                }
            }

            if (this@GlobalInnerIndex.isUsable()) {
                project.getComponent(TypoFixerComponent::class.java).onSearcherStatusMaybeChanged()
            }
            done = true
        }

        override fun onCanceled(p0: ProgressIndicator) {
            if (!done) {
                ProgressIndicatorUtils.scheduleWithWriteActionPriority(this)
            }
        }

        // can be interrupted by dumb mode
        @TestOnly
        fun waitForRefreshing() {
            while (!done) {
                doCollect(null)
            }
        }
    }

    @TestOnly
    fun waitForRefreshing() {
        val refreshingTask = CollectProjectNames(project)
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