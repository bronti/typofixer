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
import com.jetbrains.typofixer.search.Searcher
import com.jetbrains.typofixer.search.signature.Signature
import org.jetbrains.annotations.TestOnly
import java.util.*


/**
 * @author bronti.
 */
private typealias IndexMap = HashMap<Int, HashSet<String>>

class Index(val signature: Signature) {

    enum class WordType {
        KEYWORD, LOCAL, GLOBAL
    }

    // not concurrent
    private val keywordsIndex = IndexMap()
    // not concurrent
    private val localIdentifiersIndex = IndexMap()
    // concurrent
    private val globalIndex = IndexMap()

    private fun addAllToIndex(index: IndexMap, words: List<String>) = words.forEach { index.add(it) }

    private fun getSizeOf(index: IndexMap) = index.map { it.value.size }.sum()
    fun getLocalSize() = getSizeOf(localIdentifiersIndex) + getSizeOf(keywordsIndex)
    fun getGlobalSize() = synchronized(globalIndex) { getSizeOf(globalIndex) }
    fun getSize() = getLocalSize() + getGlobalSize()

    // internal use only
    var timesGlobalRefreshRequested = 0
        private set

    @Volatile
    private var lastGlobalRefreshingTask: CollectProjectNames? = null

    fun isUsable() = lastGlobalRefreshingTask == null

//    fun get(signature: Int) = hashMapOf(
//            WordType.KEYWORD to keywordsIndex.getWithDefault(signature),
//            WordType.LOCAL to localIdentifiersIndex.getWithDefault((signature)),
//            WordType.GLOBAL to synchronized(globalIndex) { globalIndex.getWithDefault(signature) }
//    )

//    fun getAll(signatures: List<Set<Int>>) = signatures.map { hashMapOf(
//            WordType.KEYWORD to keywordsIndex.getAll(it.toList()),
//            WordType.LOCAL to localIdentifiersIndex.getAll((it.toList())),
//            WordType.GLOBAL to synchronized(globalIndex) { globalIndex.getAll(it.toList()) }
//    )}

    fun getAll(type: WordType, signatures: Set<Int>) = when (type) {
            WordType.KEYWORD -> keywordsIndex.getAll(signatures)
            WordType.LOCAL -> localIdentifiersIndex.getAll(signatures)
            WordType.GLOBAL -> synchronized(globalIndex) { globalIndex.getAll(signatures) }
    }

//    fun getKeywords(signatures: List<Set<Int>>) = signatures.map { keywordsIndex.getAll(it.toList()) }
//    fun getLocal(signatures: List<Set<Int>>)  = signatures.map { localIdentifiersIndex.getAll(it.toList()) }
//    fun getGlobal(signatures: List<Set<Int>>) = signatures.map { globalIndex.getAll(it.toList()) }

    fun contains(str: String) = keywordsIndex.doContains(str) ||
            localIdentifiersIndex.doContains(str) ||
            synchronized(globalIndex) { globalIndex.doContains(str) }

    // not meant to be called concurrently
    fun refreshLocal(psiFile: PsiFile?) {
        psiFile ?: return
        val collector = TypoFixerLanguageSupport.getSupport(psiFile.language)?.getLocalDictionaryCollector() ?: return
        doRefreshLocal(keywordsIndex, collector.keyWords())
        doRefreshLocal(localIdentifiersIndex, collector.localIdentifiers(psiFile))
    }

    private fun doRefreshLocal(index: IndexMap, words: List<String>) {
        index.clear()
        addAllToIndex(index, words)
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

    private fun IndexMap.getWithDefault(signature: Int) = this[signature] ?: hashSetOf()

    private fun IndexMap.getAll(signatures: Set<Int>) = signatures.flatMap { getWithDefault(it) }

    private fun IndexMap.add(str: String): Boolean {
        val signature = signature.get(str)
        this[signature] = this.getWithDefault(signature)
        return this[signature]!!.add(str)
    }

    private fun IndexMap.doContains(str: String) = this[signature.get(str)]?.contains(str) ?: false

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
                    } else return@checkedCollect
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
    fun addToIndex(words: List<String>) = addAllToIndex(localIdentifiersIndex, words)


    @TestOnly
    fun getAltogether(signatures: Set<Int>) = WordType.values().fold(HashSet<String>() as Set<String>) { acc, type -> acc + getAll(type, signatures) }
}