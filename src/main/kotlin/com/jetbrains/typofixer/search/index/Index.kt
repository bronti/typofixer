package com.jetbrains.typofixer.search.index

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import com.intellij.psi.PsiFile
import com.intellij.psi.impl.java.stubs.index.JavaFieldNameIndex
import com.intellij.psi.impl.java.stubs.index.JavaMethodNameIndex
import com.intellij.psi.impl.java.stubs.index.JavaShortClassNameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.jetbrains.typofixer.lang.TypoFixerLanguageSupport
import com.jetbrains.typofixer.search.signature.Signature

/**
 * @author bronti.
 */
class Index(val signature: Signature) {

    private val localIndex = HashMap<Int, HashSet<String>>()
    // todo: make private
    val globalIndex = HashMap<Int, HashSet<String>>()

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

    var localSize = 0
        private set
    var globalSize = 0
        private set

    val size: Int
        get() = localSize + globalSize

    fun get(signature: Int): Set<String> = localIndex.getWithDefault(signature) + globalIndex.getWithDefault(signature)
    fun contains(str: String) = localIndex.contains(str) || globalIndex.contains(str)

    fun refreshLocal(psiFile: PsiFile?) {
        clearLocal()
        psiFile ?: return
        val collector = TypoFixerLanguageSupport.Extension.getSupport(psiFile.language).getLocalDictionaryCollector()
        updateLocal(collector.keyWords())
        updateLocal(collector.localIdentifiers(psiFile))
    }

    // not private because of tests (todo: do something about it)
    fun updateLocal(words: List<String>) {
        words.forEach { if (localIndex.add(it)) ++localSize }
    }

    fun refreshGlobal(project: Project) {
        clearGlobal()
        val identifiers = projectIdentifiers(project)
        identifiers.forEach { if (globalIndex.add(it)) ++globalSize }
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
}

private fun projectIdentifiers(project: Project): List<String> {
    return ApplicationManager.getApplication().runReadAction(Computable<List<String>> {
        val methodNames = JavaMethodNameIndex.getInstance().getAllKeys(project)
        val fieldsNames = JavaFieldNameIndex.getInstance().getAllKeys(project)

        val packages = mutableSetOf<String>()
        val shortClassNameIndex = JavaShortClassNameIndex.getInstance()

        // todo: optimize??? (make new index?)
        val shortClassNames = shortClassNameIndex.getAllKeys(project)
        for (name in shortClassNames) {
            shortClassNameIndex
                    .get(name, project, GlobalSearchScope.allScope(project))
                    .flatMap { it.qualifiedName!!.split(".") }
                    .forEach { packages.add(it) }
        }
        // todo: debug packages
        methodNames + fieldsNames + packages //126k
    })
}