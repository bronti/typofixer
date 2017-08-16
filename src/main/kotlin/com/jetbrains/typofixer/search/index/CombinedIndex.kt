package com.jetbrains.typofixer.search.index


import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.jetbrains.typofixer.search.signature.Signature
import com.jetbrains.typofixer.typoFixerComponent
import org.jetbrains.annotations.TestOnly


/**
 * @author bronti.
 */
class CombinedIndex(val project: Project, val signature: Signature) {

    enum class WordType {
        KEYWORD,
        LOCAL_IDENTIFIER,
        KOTLIN_SPECIFIC_FIELD,
        GLOBAL;

        fun isLocal() = this == KEYWORD || this == LOCAL_IDENTIFIER
        fun isGlobal() = !isLocal()
    }

    // not concurrent
    private val keywordsIndex = LocalInnerIndex(signature) { collector, element -> collector.keyWords(element) }
    // not concurrent
    private val localIdentifiersIndex = LocalInnerIndex(signature) { collector, element -> collector.localIdentifiers(element.containingFile) }
    // concurrent
    private val globalIndex = GlobalInnerIndex(project, signature)
    // concurrent
    private val kotlinSpecificFieldsIndex = InnerIndexForKotlinSpecificFields(project, signature)

    private val WordType.index
        get() = when (this) {
            WordType.KEYWORD -> keywordsIndex
            WordType.LOCAL_IDENTIFIER -> localIdentifiersIndex
            WordType.KOTLIN_SPECIFIC_FIELD -> kotlinSpecificFieldsIndex
            WordType.GLOBAL -> globalIndex
        }
    private val indices = WordType.values().map { it.index }

    fun getSize() = getLocalSize() + getGlobalSize()
    fun getLocalSize() = localIdentifiersIndex.getSize() + keywordsIndex.getSize()
    fun getGlobalSize(): Int {
        return try {
            globalIndex.getSize() + kotlinSpecificFieldsIndex.getSize()
        } catch (e: GlobalInnerIndexBase.TriedToAccessIndexWhileItIsRefreshing) {
            -1
        }
    }

    fun isUsable() = globalIndex.isUsable() && kotlinSpecificFieldsIndex.isUsable()

    fun getAll(type: WordType, signatures: Set<Int>) = type.index.getAll(signatures)

    // not meant to be called concurrently
    fun refreshLocal(psiElement: PsiElement?) {
        val psiFile = psiElement?.containingFile ?: return
        keywordsIndex.refresh(psiFile)
        localIdentifiersIndex.refresh(psiFile)
        project.typoFixerComponent.onSearcherStatusMaybeChanged()
    }

    fun refreshLocalWithKeywords(words: Set<String>) {
        keywordsIndex.refreshWithWords(words)
        localIdentifiersIndex.clear()
    }

    fun refreshGlobal() {
        if (!canRefreshGlobal) return
        ++timesGlobalRefreshRequested
        globalIndex.refresh()
        kotlinSpecificFieldsIndex.refresh()
    }

    // internal use only
    var timesGlobalRefreshRequested = 0
        private set

    // used in tests for temporarily blocking of index refreshing
    var canRefreshGlobal = true

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

//
//    @TestOnly
//    fun getAltogether(signatures: Set<Int>) = indices.flatMap { it.getAll(signatures) }
}

