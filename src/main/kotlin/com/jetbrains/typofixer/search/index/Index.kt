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

    var usable = true
        private set

    var localSize = 0
        private set
    var globalSize = 0
        private set

    val size: Int
        get() = localSize + globalSize

    fun get(signature: Int): Set<String>
            = localIndex.getWithDefault(signature) + globalIndex.getWithDefault(signature)
    fun contains(str: String) = localIndex.contains(str) || globalIndex.contains(str)

    fun refreshLocal(psiFile: PsiFile?) {
        clearLocal()
        psiFile ?: return
        val collector = TypoFixerLanguageSupport.getSupport(psiFile.language)?.getLocalDictionaryCollector()
        collector ?: return
        updateLocal(collector.keyWords())
        updateLocal(collector.localIdentifiers(psiFile))
    }

    // not private because of tests (todo: do something about it)
    fun updateLocal(words: List<String>) {
        words.forEach { addToLocalIndex(it) }
    }

    // todo: what should happen is is called two times simultaneously (?)
    fun refreshGlobal(project: Project) {
        // todo: concurrency
        usable = false
        clearGlobal()
        DumbService.getInstance(project).smartInvokeLater {
            if (project.isInitialized) {
                ProgressIndicatorUtils.scheduleWithWriteActionPriority(CollectProjectNames(project))
            }
        }
    }


    inner private class CollectProjectNames(val project: Project) : ReadTask() {

        var methodNamesCollected = false
        var fieldNamesCollected = false
        var classNamesCollected = false
        var classesToCollectPackageNames = ArrayList<String>()
        var done = false

        override fun runBackgroundProcess(indicator: ProgressIndicator): Continuation? {
            return DumbService.getInstance(project).runReadActionInSmartMode(Computable<Continuation?> {
                doCollect()
                null
            })
        }

        private fun doCollect() {
            if (!project.isInitialized) return

            val cache = PsiShortNamesCache.getInstance(project)

            if (!methodNamesCollected) {
                addAllToGlobalIndex(cache.allMethodNames.toList())
                methodNamesCollected = true
            }

            if (!fieldNamesCollected) {
                addAllToGlobalIndex(cache.allFieldNames.toList())
                fieldNamesCollected = true
            }

            if (!classNamesCollected) {
                classesToCollectPackageNames.addAll(cache.allClassNames)
                classNamesCollected = true
            }

            while (classesToCollectPackageNames.isNotEmpty()) {
                val name = classesToCollectPackageNames.last()
                JavaShortClassNameIndex.getInstance()
                        .get(name, project, GlobalSearchScope.allScope(project))
                        .flatMap { (it.qualifiedName ?: it.name ?: "").split(".") }
                        .forEach { addToGlobalIndex(it) }
                classesToCollectPackageNames.removeAt(classesToCollectPackageNames.size - 1)
            }
            usable = true
            done = true
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