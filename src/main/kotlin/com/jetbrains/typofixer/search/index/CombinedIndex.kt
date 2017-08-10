package com.jetbrains.typofixer.search.index


import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.jetbrains.typofixer.TypoFixerComponent
import com.jetbrains.typofixer.lang.TypoFixerLanguageSupport
import com.jetbrains.typofixer.search.signature.Signature
import org.jetbrains.annotations.TestOnly


/**
 * @author bronti.
 */
class CombinedIndex(val project: Project, val signature: Signature) {

    enum class WordType {
        KEYWORD, LOCAL_IDENTIFIER, KOTLIN_SPECIFIC_FIELD, GLOBAL;

        fun isLocal() = this == KEYWORD || this == LOCAL_IDENTIFIER
        fun isGlobal() = !isLocal()
    }

    private val WordType.index
        get() = when (this) {
            WordType.KEYWORD -> keywordsIndex
            WordType.LOCAL_IDENTIFIER -> localIdentifiersIndex
            WordType.KOTLIN_SPECIFIC_FIELD -> kotlinSpecificFieldsIndex
            WordType.GLOBAL -> globalIndex
        }

    private fun getDictCollector(file: PsiFile) = TypoFixerLanguageSupport.getSupport(file.language)?.getLocalDictionaryCollector()

    // not concurrent
    private val keywordsIndex = LocalInnerIndex(signature) { getDictCollector(it.containingFile)?.keyWords(it)?.toSet() ?: setOf() }
    // not concurrent
    private val localIdentifiersIndex = LocalInnerIndex(signature) { getDictCollector(it.containingFile)?.localIdentifiers(it.containingFile)?.toSet() ?: setOf() }
    // concurrent
    private val globalIndex = GlobalInnerIndex(project, signature)
    // concurrent
    private val kotlinSpecificFieldsIndex = InnerIndexForKotlinSpecificFields(project, signature)

    private val indices = WordType.values().map { it.index }

    fun getLocalSize() = localIdentifiersIndex.getSize() + keywordsIndex.getSize()

    fun getGlobalSize(): Int {
        assert(ApplicationManager.getApplication().isInternal)
        return globalIndex.getSize() + kotlinSpecificFieldsIndex.getSize()
    }

    // internal use only (can works slowly for globalIndex!!!!)
    fun getSize(): Int {
        assert(ApplicationManager.getApplication().isInternal)
        return getLocalSize() + getGlobalSize()
    }

    fun isUsable() = globalIndex.isUsable()

    var canRefreshGlobal = true

    // internal use only
    var timesGlobalRefreshRequested = 0
        private set

    fun getAll(type: WordType, signatures: Set<Int>) = type.index.getAll(signatures)

    // not meant to be called concurrently
    fun refreshLocal(psiElement: PsiElement?) {
        val psiFile = psiElement?.containingFile ?: return
        keywordsIndex.refresh(psiFile)
        localIdentifiersIndex.refresh(psiFile)
        project.getComponent(TypoFixerComponent::class.java).onSearcherStatusMaybeChanged()
    }

    fun refreshLocalWithKeywords(words: List<String>) {
        keywordsIndex.refreshWithWords(words)
        localIdentifiersIndex.clear()
    }

    fun refreshGlobal() {
        if (!canRefreshGlobal) return
        ++timesGlobalRefreshRequested
        globalIndex.refresh()
        kotlinSpecificFieldsIndex.refresh()
    }

    @TestOnly
    fun contains(str: String) = indices.any { it.contains(str) }

    // can be interrupted by dumb mode
    @TestOnly
    fun waitForGlobalRefreshing() {
        globalIndex.waitForRefreshing()
        kotlinSpecificFieldsIndex.waitForRefreshing()
    }

    @TestOnly
    fun addToIndex(words: List<String>) = localIdentifiersIndex.addAll(words.toSet())


    @TestOnly
    fun getAltogether(signatures: Set<Int>) = indices.flatMap { it.getAll(signatures) }
}

