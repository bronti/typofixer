package com.jetbrains.typofixer.search.index


import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.jetbrains.typofixer.search.signature.Signature
import com.jetbrains.typofixer.typoFixerComponent
import org.jetbrains.annotations.TestOnly


/**
 * @author bronti.
 */
class CombinedIndex(val project: Project, val signature: Signature) {

    enum class IndexType {
        KEYWORD,
        LOCAL_IDENTIFIER,
        CLASSNAME,
        KOTLIN_SPECIFIC_FIELD,
        NOT_CLASSNAME;

        fun isLocal() = this == KEYWORD || this == LOCAL_IDENTIFIER
        fun isGlobal() = !isLocal()

        companion object {
            fun globalValues() = values().filter(IndexType::isGlobal)
            fun localValues() = values().filter(IndexType::isLocal)
        }
    }

    private val indexByType = HashMap<IndexType, InnerIndex>()

    init {
        indexByType[IndexType.KEYWORD] = LocalInnerIndex(signature) { collector, element -> collector.keyWords(element) }
        indexByType[IndexType.LOCAL_IDENTIFIER] = LocalInnerIndex(signature) { collector, element -> collector.localIdentifiers(element.containingFile) }

        indexByType[IndexType.CLASSNAME] = GlobalInnerIndex(project, signature, ::ClassNamesCollector)
        indexByType[IndexType.KOTLIN_SPECIFIC_FIELD] = GlobalInnerIndex(project, signature, ::KotlinGettersSettersCollector)
        indexByType[IndexType.NOT_CLASSNAME] = GlobalInnerIndex(project, signature, ::AllNamesExceptClassNamesCollector)
    }

    private val IndexType.index get() = indexByType[this]!!

    private val indices = IndexType.values().map { it.index }
    private val globalIndices = IndexType.globalValues().map { it.index as GlobalInnerIndex }
    private val localIndices = IndexType.localValues().map { it.index as LocalInnerIndex }

    fun getSize() = getLocalSize() + getGlobalSize()
    fun getLocalSize() = localIndices.map { it.getSize() }.sum()
    fun getGlobalSize(): Int {
        return try {
            globalIndices.map { it.getSize() }.sum()
        } catch (e: GlobalInnerIndex.TriedToAccessIndexWhileItIsRefreshing) {
            -1
        }
    }

    fun isUsable() = globalIndices.all(GlobalInnerIndex::isUsable)

    fun getAll(type: IndexType, signatures: Set<Int>) = type.index.getAll(signatures)

    // not meant to be called concurrently
    fun refreshLocal(psiFile: PsiFile?) {
        psiFile ?: return
        localIndices.forEach { it.refresh(psiFile) }
        project.typoFixerComponent.onSearcherStatusMaybeChanged()
    }

    fun refreshLocalWithKeywords(words: Set<String>) {
        localIndices.forEach(LocalInnerIndex::clear)
        (IndexType.KEYWORD.index as LocalInnerIndex).refreshWithWords(words)
    }

    fun refreshGlobal() {
        if (!canRefreshGlobal) return
        ++timesGlobalRefreshRequested
        globalIndices.forEach(GlobalInnerIndex::refresh)
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
        globalIndices.forEach(GlobalInnerIndex::waitForRefreshing)
    }

    @TestOnly
    fun addToIndex(words: List<String>) = (IndexType.LOCAL_IDENTIFIER.index as LocalInnerIndex).addAll(words.toSet())

    @TestOnly
    fun getAltogether(signatures: Set<Int>) = indices.asSequence().flatMap { it.getAll(signatures) }
}

