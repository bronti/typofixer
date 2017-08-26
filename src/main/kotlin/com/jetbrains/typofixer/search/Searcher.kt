package com.jetbrains.typofixer.search

import com.intellij.ProjectTopics
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootEvent
import com.intellij.openapi.roots.ModuleRootListener
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiModificationTracker
import com.jetbrains.typofixer.search.distance.Distance
import com.jetbrains.typofixer.search.index.CombinedIndex
import com.jetbrains.typofixer.search.signature.ComplexSignature
import org.jetbrains.annotations.TestOnly

/**
 * @author bronti.
 */

abstract class Searcher {

    abstract val project: Project

    enum class Status {
        DUMB_MODE,
        INDEX_REFRESHING,
        ACTIVE
    }

    abstract fun find(file: PsiFile?, str: String, indexTypes: List<CombinedIndex.IndexType>, checkTime: () -> Unit): SortedSearchResults
    abstract fun findAmongKeywords(str: String, keywords: Set<String>, checkTime: () -> Unit): SortedSearchResults

    abstract val distanceProvider: Distance

    // internal use only
    abstract fun getStatus(): Status

    abstract fun getStatistics(): Pair<Int, Int>

    @TestOnly abstract fun findAll(str: String, psiFile: PsiFile?, precise: Boolean): Map<Double, List<String>>
    @TestOnly abstract fun getIndex(): CombinedIndex
    @TestOnly abstract fun forceGlobalIndexRefreshing()
    @TestOnly abstract fun forceLocalIndexRefreshing(psiFile: PsiFile?)
}

open class DLSearcher(final override val project: Project) : Searcher() {

    companion object {
        // signature with length
        // char frequency
        // improved range
        // clever choosing from index
        // 6: fast package names collecting + bug with shift in signature fixed
        // 7: concurrent index
        // 8: less concurrent index
        // 9: search result prioritizing
        // 10: compressed global index
        // 11: compressing fixed
        // 12: distance is Double (misclicked shift and swap costs lowered)
        // 13: lazy search returning multiple results
        // 14: distance between keys
        val VERSION = 14
    }

    private val maxRoundedError = 2
    private val signature = ComplexSignature()

    private val index = CombinedIndex(project, signature)

    private var lastPsiModificationCount = 0L
    private fun freshPsiModificationCount() = PsiModificationTracker.SERVICE.getInstance(project).outOfCodeBlockModificationCount

    private val simpleSearch = DLSearchAlgorithm(maxRoundedError, index)
    private val preciseSearch = DLPreciseSearchAlgorithm(maxRoundedError, index)
    private fun getSearch(precise: Boolean) = if (precise) preciseSearch else simpleSearch

    override val distanceProvider = simpleSearch.distance

    init {
        val connection = project.messageBus.connect(project)

        DumbService.getInstance(project).smartInvokeLater {
            connection.subscribe(ProjectTopics.PROJECT_ROOTS, object : ModuleRootListener {
                override fun rootsChanged(event: ModuleRootEvent) {
                    updateIndex()
                }
            })

            connection.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) {
                    if (freshPsiModificationCount() != lastPsiModificationCount) {
                        updateIndex()
                    }
                }

                // todo: ?
                override fun fileOpened(source: FileEditorManager, file: VirtualFile) {}

                override fun fileClosed(source: FileEditorManager, file: VirtualFile) {}
            })
        }
    }

    override fun find(file: PsiFile?, str: String, indexTypes: List<CombinedIndex.IndexType>, checkTime: () -> Unit): SortedSearchResults {
        // todo: checkTime into refreshLocal?
        index.refreshLocal(file)
        return getSearch(false).find(str, indexTypes, checkTime)
    }

    override fun findAmongKeywords(str: String, keywords: Set<String>, checkTime: () -> Unit): SortedSearchResults {
        // todo: checkTime into refreshLocal?
        index.refreshLocalWithKeywords(keywords)
        return getSearch(false).find(str, listOf(CombinedIndex.IndexType.KEYWORD), checkTime)
    }

    private fun updateIndex() {
        lastPsiModificationCount = freshPsiModificationCount()
        index.refreshGlobal()
    }

    // internal use only
    override fun getStatistics(): Pair<Int, Int> {
        assert(ApplicationManager.getApplication().isInternal)
        return Pair(index.getSize(), index.timesGlobalRefreshRequested)
    }

    override fun getStatus() = when {
        DumbService.isDumb(project) -> Status.DUMB_MODE
        index.isUsable() -> Status.ACTIVE
        else -> Status.INDEX_REFRESHING
    }

    @TestOnly
    override fun findAll(str: String, psiFile: PsiFile?, precise: Boolean): Map<Double, List<String>> {
        index.refreshLocal(psiFile)
        return getSearch(precise).findAll(str).groupBy { distanceProvider.measure(str, it) }
    }

    @TestOnly
    override fun getIndex() = index

    @TestOnly
    override fun forceGlobalIndexRefreshing() {
        index.waitForGlobalRefreshing()
    }

    @TestOnly
    override fun forceLocalIndexRefreshing(psiFile: PsiFile?) {
        index.refreshLocal(psiFile)
    }
}