package com.jetbrains.typofixer.search.index


import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.jetbrains.typofixer.TypoFixerComponent
import com.jetbrains.typofixer.lang.TypoFixerLanguageSupport
import com.jetbrains.typofixer.search.signature.Signature
import org.jetbrains.annotations.TestOnly
import java.util.*


/**
 * @author bronti.
 */
class CombinedIndex(val project: Project, val signature: Signature) {

    enum class WordType { KEYWORD, LOCAL, GLOBAL }

    private fun getDictCollector(file: PsiFile) = TypoFixerLanguageSupport.getSupport(file.language)?.getLocalDictionaryCollector()

    // not concurrent
    private val keywordsIndex = LocalIndex(signature) { getDictCollector(it.containingFile)?.keyWords(it)?.toSet() ?: setOf() }
    // not concurrent
    private val localIdentifiersIndex = LocalIndex(signature) { getDictCollector(it.containingFile)?.localIdentifiers(it.containingFile)?.toSet() ?: setOf() }
    // concurrent
    private val globalIndex = GlobalIndex(project, signature)

    fun getLocalSize() = localIdentifiersIndex.getSize() + keywordsIndex.getSize()

    // slow!!
    fun getGlobalSize() = synchronized(globalIndex) { globalIndex.getSize() }

    // internal use only (works slowly for globalIndex!!!!
    fun getSize() = getLocalSize() + getGlobalSize()

    fun isUsable() = canRefreshGlobal && globalIndex.isUsable()

    // when index is not active global refresh cannot be performed
    var canRefreshGlobal = true
//        get() = synchronized(globalIndex) { field }
//        set(value) = synchronized(globalIndex) { field = value }

    // internal use only
    var timesGlobalRefreshRequested = 0
        private set


    fun getAll(type: WordType, signatures: Set<Int>) = when (type) {
        WordType.KEYWORD -> keywordsIndex.getAll(signatures)
        WordType.LOCAL -> localIdentifiersIndex.getAll(signatures)
        WordType.GLOBAL -> globalIndex.getAll(signatures)
    }

    // not meant to be called concurrently
    fun refreshLocal(psiElement: PsiElement?) {
        val psiFile = psiElement?.containingFile ?: return
        keywordsIndex.refresh(psiFile)
        localIdentifiersIndex.refresh(psiFile)
        project.getComponent(TypoFixerComponent::class.java).onSearcherStatusMaybeChanged()
    }

    fun refreshGlobal() {
        if (!canRefreshGlobal) return
        ++timesGlobalRefreshRequested
        globalIndex.refresh()
    }

    @TestOnly
    fun contains(str: String) = keywordsIndex.contains(str) ||
            localIdentifiersIndex.contains(str) ||
            globalIndex.contains(str)

    // can be interrupted by dumb mode
    @TestOnly
    fun waitForGlobalRefreshing() {
        globalIndex.waitForRefreshing()
    }

    @TestOnly
    fun addToIndex(words: List<String>) = localIdentifiersIndex.addAll(words.toSet())


    @TestOnly
    fun getAltogether(signatures: Set<Int>) = WordType.values().fold(HashSet<String>() as Set<String>) { acc, type -> acc + getAll(type, signatures) }
}

