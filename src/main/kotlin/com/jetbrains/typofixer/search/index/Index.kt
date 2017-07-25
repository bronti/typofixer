package com.jetbrains.typofixer.search.index

import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.util.ProgressIndicatorUtils
import com.intellij.openapi.progress.util.ReadTask
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import com.intellij.psi.PsiFile
import com.intellij.psi.impl.java.stubs.index.JavaShortClassNameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache
import com.jetbrains.typofixer.TypoFixerComponent
import com.jetbrains.typofixer.lang.TypoFixerLanguageSupport
import com.jetbrains.typofixer.search.signature.Signature

/**
 * @author bronti.
 */
class Index(val signature: Signature) {

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

    // not private because of tests (todo: do something about it)
    fun addAllToLocalIndex(words: List<String>) {
        words.forEach { addToLocalIndex(it) }
    }

    var usable = true
        private set

    var localSize = 0
        private set
    var globalSize = 0
        private set

    val size: Int
        get() = localSize + globalSize

    private var lastGlobalRefreshingTask: CollectProjectNames? = null

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
        val refreshingTask = CollectProjectNames(project)
        // todo: concurrency
        usable = false
        project.getComponent(TypoFixerComponent::class.java).onSearcherStatusChanged()
        lastGlobalRefreshingTask = refreshingTask
        clearGlobal()
        DumbService.getInstance(project).smartInvokeLater {
            if (project.isInitialized) {
                ProgressIndicatorUtils.scheduleWithWriteActionPriority(refreshingTask)
            }
        }
    }


    inner private class CollectProjectNames(val project: Project) : ReadTask() {

        private fun isCurrentRefreshingTask() = this === lastGlobalRefreshingTask

        private var methodNamesCollected = false
        private var fieldNamesCollected = false
        private var classNamesCollected = false
        private var classesToCollectPackageNames = ArrayList<String>()
        private var done = false

        override fun runBackgroundProcess(indicator: ProgressIndicator): Continuation? {
            return DumbService.getInstance(project).runReadActionInSmartMode(Computable<Continuation?> {
                doCollect(indicator)
                null
            })
        }

        private fun doCollect(indicator: ProgressIndicator) {
            if (!project.isInitialized) return

            val cache = PsiShortNamesCache.getInstance(project)

            fun canProceed() = !indicator.isCanceled && isCurrentRefreshingTask()

            fun checkedCollect(isCollected: Boolean, collect: () -> Unit) {
                if (!indicator.isCanceled && isCurrentRefreshingTask() && !isCollected) {
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

            checkedCollect(classNamesCollected) {
                classesToCollectPackageNames.addAll(cache.allClassNames)
                classNamesCollected = true
            }

            while (canProceed() && classesToCollectPackageNames.isNotEmpty()) {
                val name = classesToCollectPackageNames.last()
                // todo: language specific (?)
                cache.getClassesByName(name, GlobalSearchScope.allScope(project))
                        .flatMap { (it.qualifiedName ?: it.name ?: "").split(".") }
                        .forEach { addToGlobalIndex(it) }
                addToGlobalIndex(name)
                classesToCollectPackageNames.removeAt(classesToCollectPackageNames.size - 1)
            }
            if (canProceed()) {
                // todo: lock here
                usable = true
                project.getComponent(TypoFixerComponent::class.java).onSearcherStatusChanged()
            }
            if (!indicator.isCanceled) done = true
        }

        override fun onCanceled(p0: ProgressIndicator) {
            if (!done) {
                ProgressIndicatorUtils.scheduleWithWriteActionPriority(this)
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
}