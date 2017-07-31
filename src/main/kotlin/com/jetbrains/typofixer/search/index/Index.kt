package com.jetbrains.typofixer.search.index


import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.util.ProgressIndicatorUtils
import com.intellij.openapi.progress.util.ReadTask
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache
import com.jetbrains.typofixer.TypoFixerComponent
import com.jetbrains.typofixer.lang.TypoFixerLanguageSupport
import com.jetbrains.typofixer.search.signature.Signature
import org.jetbrains.annotations.TestOnly
import java.util.*


/**
 * @author bronti.
 */
private typealias IndexMap = HashMap<Int, HashSet<String>>

class Index(val signature: Signature) {

    // todo: look into JavaKeywordCompletion
    private val localIndex  = IndexMap()
    private val globalIndex = IndexMap()

    private fun addAllToIndex(index: IndexMap, words: List<String>)  = words.forEach { index.add(it) }

    fun getLocalSize()  = localIndex.map { it.value.size }.sum()
    fun getGlobalSize() = synchronized(globalIndex) { globalIndex.map { it.value.size } }.sum()
    fun getSize() = getLocalSize() + getGlobalSize()

    // internal use only
    var timesGlobalRefreshRequested = 0
    private set

    @Volatile
    private var lastGlobalRefreshingTask: CollectProjectNames? = null

    fun isUsable() = lastGlobalRefreshingTask == null

    fun get(signature: Int)   = localIndex.getWithDefault(signature) + synchronized(globalIndex) { globalIndex.getWithDefault(signature) }
    fun contains(str: String) = localIndex.doContains(str) || synchronized(globalIndex) { globalIndex.doContains(str) }

    // not meant to be called concurrently
    fun refreshLocal(psiFile: PsiFile?) {
        localIndex.clear()
        psiFile ?: return
        val collector = TypoFixerLanguageSupport.getSupport(psiFile.language)?.getLocalDictionaryCollector() ?: return
        addAllToIndex(localIndex, collector.keyWords())
        addAllToIndex(localIndex, collector.localIdentifiers(psiFile))
    }

    fun refreshGlobal(project: Project) {
        ++timesGlobalRefreshRequested
        val refreshingTask = CollectProjectNames(project)
        synchronized(globalIndex) {
            lastGlobalRefreshingTask = refreshingTask
            globalIndex.clear()
        }
        project.getComponent(TypoFixerComponent::class.java).onSearcherStatusMaybeChanged()
        DumbService.getInstance(project).smartInvokeLater {
            if (project.isInitialized) {
                ProgressIndicatorUtils.scheduleWithWriteActionPriority(refreshingTask)
            }
        }
    }

    // todo: refactor
    inner private class CollectProjectNames(val project: Project) : ReadTask() {

        private fun isCurrentRefreshingTask() = this === lastGlobalRefreshingTask

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

            fun checkedCollect(isCollected: Boolean, toCollect: Array<String>, markCollected: () -> Unit) {
                if (!shouldCollect() || isCollected) return
                synchronized(globalIndex) {
                    if (shouldCollect() && !isCollected) {
                        addAllToIndex(globalIndex, toCollect.toList())
                    }
                    else return@checkedCollect
                }
                markCollected()
            }

            checkedCollect(methodNamesCollected, cache.allMethodNames) { methodNamesCollected = true }
            checkedCollect(fieldNamesCollected, cache.allFieldNames) { fieldNamesCollected = true }

            // todo: language specific (?) (kotlin bug)
            checkedCollect(classNamesCollected, cache.allClassNames) { classNamesCollected = true }

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
                synchronized(globalIndex) {
                    if (shouldCollect()) addAllToIndex(globalIndex, packageNames)
                    packageNames.clear()
                }
            }

            // todo: check that index is refreshing after each stub index refreshment
            if (shouldCollect()) {
                synchronized(globalIndex) {
                    if (isCurrentRefreshingTask()) lastGlobalRefreshingTask = null
                }
            }

            if (this@Index.isUsable()) {
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
        fun waitForGlobalRefreshing() {
            while (!done) {
                doCollect(null)
            }
        }
    }

    fun clear() {
        localIndex.clear()
        globalIndex.clear()
    }

    // todo: refactor (?)
    private fun IndexMap.getWithDefault(signature: Int) = this[signature] ?: hashSetOf()

    private fun IndexMap.add(str: String): Boolean {
        val signature = signature.get(str)
        this[signature] = this.getWithDefault(signature)
        return this[signature]!!.add(str)
    }

    private fun IndexMap.doContains(str: String) = this[signature.get(str)]?.contains(str) ?: false

    // can be interrupted by dumb mode
    @TestOnly
    fun waitForGlobalRefreshing(project: Project) {
        val refreshingTask = CollectProjectNames(project)
        globalIndex.clear()
        lastGlobalRefreshingTask = refreshingTask
        DumbService.getInstance(project).runReadActionInSmartMode {
            refreshingTask.waitForGlobalRefreshing()
        }
    }

    @TestOnly
    fun addToIndex(words: List<String>) = addAllToIndex(localIndex, words)
}