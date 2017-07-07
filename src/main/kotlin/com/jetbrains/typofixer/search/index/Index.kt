package com.jetbrains.typofixer.search.index

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import com.intellij.psi.PsiFile
import com.intellij.psi.impl.java.stubs.index.JavaFieldNameIndex
import com.intellij.psi.impl.java.stubs.index.JavaMethodNameIndex
import com.intellij.psi.impl.java.stubs.index.JavaShortClassNameIndex
import com.jetbrains.typofixer.lang.TypoFixerLanguageSupport
import com.jetbrains.typofixer.search.signature.Signature

/**
 * @author bronti.
 */
class Index(val signature: Signature) {

    private val localIndex = HashMap<Int, HashSet<String>>()
    private val globalIndex = HashMap<Int, HashSet<String>>()

    private fun HashMap<Int, HashSet<String>>.getWithDefault(signature: Int) = this[signature] ?: hashSetOf()
    private fun HashMap<Int, HashSet<String>>.add(str: String) {
        val signature = signature.get(str)
        this[signature] = this.getWithDefault(signature)
        this[signature]!!.add(str)
    }

    fun get(signature: Int): Set<String> = localIndex.getWithDefault(signature) + globalIndex.getWithDefault(signature)

    fun refreshLocal(psiFile: PsiFile) {
        localIndex.clear()
        val collector = TypoFixerLanguageSupport.Extension.getSupport(psiFile.language).getLocalDictionaryCollector()
        updateLocal(collector.keyWords())
        updateLocal(collector.localIdentifiers(psiFile))
    }

    // not private because of tests (todo: do something about it)
    fun updateLocal(words: List<String>) = words.forEach { localIndex.add(it) }

    fun refreshGlobal(project: Project) {
        globalIndex.clear()
        projectIdentifiers(project).forEach { globalIndex.add(it) }
    }
}

private fun projectIdentifiers(project: Project): List<String> {
    return ApplicationManager.getApplication().runReadAction(Computable<List<String>> {
        val classNames = JavaShortClassNameIndex.getInstance().getAllKeys(project)
        val methodNames = JavaMethodNameIndex.getInstance().getAllKeys(project)
        val fieldsNames = JavaFieldNameIndex.getInstance().getAllKeys(project)
        classNames + methodNames + fieldsNames
    })
}