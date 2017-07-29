package com.jetbrains.typofixer.search.index

import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.util.ProgressIndicatorUtils
import com.intellij.openapi.progress.util.ReadTask
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache
import com.jetbrains.typofixer.TypoFixerComponent
import com.jetbrains.typofixer.lang.TypoFixerLanguageSupport
import com.jetbrains.typofixer.search.signature.Signature
import org.jetbrains.annotations.TestOnly

/**
 * @author bronti.
 */
class Index(val signature: Signature) {

    // todo: look into JavaKeywordCompletion
    private val localIndex = HashMap<Int, HashSet<String>>()
    private val globalIndex = HashMap<Int, HashSet<String>>()

    private fun addToGlobalIndex(word: String) {
        if (globalIndex.add(word)) ++globalSize
    }

    private fun addAllToGlobalIndex(words: List<String>) {
        words.forEach { addToGlobalIndex(it) }
    }

    private fun addToLocalIndex(word: String) {
        if (localIndex.add(word)) ++localSize
    }

    private fun addAllToLocalIndex(words: List<String>) {
        words.forEach { addToLocalIndex(it) }
    }

    var localSize = 0
        private set
    var globalSize = 0
        private set

    // todo: remove
    var timesGlobalRefreshRequested = 0
        private set

    val size: Int
        get() = localSize + globalSize

    @Volatile
    private var lastGlobalRefreshingTask: CollectProjectNames? = null

    fun isUsable() = lastGlobalRefreshingTask == null

    fun get(signature: Int) = localIndex.getWithDefault(signature) + globalIndex.getWithDefault(signature)
    fun contains(str: String) = localIndex.contains(str) || globalIndex.contains(str)

    fun refreshLocal(psiFile: PsiFile?) {
        clearLocal()
        psiFile ?: return
        val collector = TypoFixerLanguageSupport.getSupport(psiFile.language)?.getLocalDictionaryCollector() ?: return
        addAllToLocalIndex(collector.keyWords())
        addAllToLocalIndex(collector.localIdentifiers(psiFile))
    }

    fun refreshGlobal(project: Project) {
        ++timesGlobalRefreshRequested
        val refreshingTask = CollectProjectNames(project)
        synchronized(this@Index) {
            lastGlobalRefreshingTask = refreshingTask
            clearGlobal()                                   // should be ok
        }
        project.getComponent(TypoFixerComponent::class.java).onSearcherStatusChanged()
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
        private var dirsToCollectPackages = ArrayList<PsiDirectory>()
        var done = false
            private set

        override fun runBackgroundProcess(indicator: ProgressIndicator): Continuation? {
            return DumbService.getInstance(project).runReadActionInSmartMode(Computable<Continuation?> {
                doCollect(indicator)
                null
            })
        }

        private fun doCollect(indicator: ProgressIndicator?) {
            if (!project.isInitialized) return

            val cache = PsiShortNamesCache.getInstance(project)

            fun checkedCollect(isCollected: Boolean, collect: () -> Unit) {
                indicator?.checkCanceled()
                if (isCurrentRefreshingTask() && !isCollected) {
                    collect()
                }
            }

            checkedCollect(methodNamesCollected) {
                addAllToGlobalIndex(cache.allMethodNames.toList())
                methodNamesCollected = true
            }

            checkedCollect(fieldNamesCollected) {
                addAllToGlobalIndex(cache.allFieldNames.toList())
                fieldNamesCollected = true
            }

            // todo: language specific (?) (kotlin bug)
            checkedCollect(classNamesCollected) {
                addAllToGlobalIndex(cache.allClassNames.toList())
                classNamesCollected = true
            }

            val initialPackage = JavaPsiFacade.getInstance(project).findPackage("")
            val javaDirService = JavaDirectoryService.getInstance()
            val scope = GlobalSearchScope.allScope(project)

            dirsToCollectPackages.addAll(
                    initialPackage?.getDirectories(scope)?.flatMap { it.subdirectories.toList() } ?: emptyList()
            )

            while (isCurrentRefreshingTask() && dirsToCollectPackages.isNotEmpty()) {
                indicator?.checkCanceled()
                val subDir = dirsToCollectPackages.last()
                val subPackage = javaDirService.getPackage(subDir)
                val subPackageName = subPackage?.name
                if (subPackageName != null && subPackageName.isNotBlank()) {
                    addToGlobalIndex(subPackageName)
                }
                dirsToCollectPackages.removeAt(dirsToCollectPackages.size - 1)
                if (subPackage != null) {
                    // todo: filter resources (?)
                    // todo: Kotlin (files with > 1 class)
                    // todo: Classkt
                    dirsToCollectPackages.addAll(subDir.subdirectories)
                }
            }

            indicator?.checkCanceled()
            if (isCurrentRefreshingTask()) {
                synchronized(this@Index) {
                    if (isCurrentRefreshingTask()) lastGlobalRefreshingTask = null
                }
            }

            if (this@Index.isUsable()) {
                project.getComponent(TypoFixerComponent::class.java).onSearcherStatusChanged()
            }
            done = true
        }

        override fun onCanceled(p0: ProgressIndicator) {
            if (!done) {
                ProgressIndicatorUtils.scheduleWithWriteActionPriority(this)
            }
        }

        @TestOnly
        fun waitForGlobalRefreshing() {
            while (!done) {
                doCollect(null)
            }
        }
    }

    fun clear() {
        clearLocal()
        clearGlobal()
    }

    private fun clearLocal() {
        localIndex.clear()
        localSize = 0
    }

    private fun clearGlobal() {
        globalIndex.clear()
        globalSize = 0
    }

    private fun HashMap<Int, HashSet<String>>.getWithDefault(signature: Int) = this[signature] ?: hashSetOf()

    private fun HashMap<Int, HashSet<String>>.add(str: String): Boolean {
        val signature = signature.get(str)
        this[signature] = this.getWithDefault(signature)
        return this[signature]!!.add(str)
    }

    private fun HashMap<Int, HashSet<String>>.contains(str: String): Boolean {
        val signature = signature.get(str)
        return if (this[signature] == null) false else this[signature]!!.contains(str)
    }

    @TestOnly
    fun waitForGlobalRefreshing(project: Project) {
        val refreshingTask = CollectProjectNames(project)
        clearGlobal()
        lastGlobalRefreshingTask = refreshingTask
        DumbService.getInstance(project).runReadActionInSmartMode {
            refreshingTask.waitForGlobalRefreshing()
        }
    }

    @TestOnly
    fun addToIndex(words: List<String>) = addAllToLocalIndex(words)
}