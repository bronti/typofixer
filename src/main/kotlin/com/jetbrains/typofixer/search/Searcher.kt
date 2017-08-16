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
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiModificationTracker
import com.jetbrains.typofixer.search.distance.Distance
import com.jetbrains.typofixer.search.index.CombinedIndex
import com.jetbrains.typofixer.search.signature.ComplexSignature

/**
 * @author bronti.
 */

abstract class Searcher {

    enum class Status {
        DUMB_MODE,
        INDEX_REFRESHING,
        ACTIVE
    }

    abstract fun findClosest(element: PsiElement?, str: String, wordTypes: Array<CombinedIndex.WordType>, isTooLate: () -> Boolean): SearchResults
    abstract fun findClosestAmongKeywords(str: String, keywords: Set<String>, isTooLate: () -> Boolean): SearchResults

    abstract val distanceProvider: Distance

    // internal use only
    abstract fun getStatus(): Status
}

open class DLSearcher(val project: Project) : Searcher() {

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
        val VERSION = 13
    }

    private val maxError = 2
    private val signature = ComplexSignature()

    private val index = CombinedIndex(project, signature)

    private var lastPsiModificationCount = 0L
    private fun freshPsiModificationCount() = PsiModificationTracker.SERVICE.getInstance(project).outOfCodeBlockModificationCount

    private val simpleSearch = DLSearchAlgorithm(maxError, index)
    private val preciseSearch = DLPreciseSearchAlgorithm(maxError, index)
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

    override fun findClosest(element: PsiElement?, str: String, wordTypes: Array<CombinedIndex.WordType>, isTooLate: () -> Boolean): SearchResults {
        // todo: isTooLate into refreshLocal?
        index.refreshLocal(element)
        return getSearch(false).findClosest(str, wordTypes, isTooLate)
    }

    override fun findClosestAmongKeywords(str: String, keywords: Set<String>, isTooLate: () -> Boolean): SearchResults {
        // todo: isTooLate into refreshLocal?
        index.refreshLocalWithKeywords(keywords)
        return getSearch(false).findClosest(str, arrayOf(CombinedIndex.WordType.KEYWORD), isTooLate)
    }

    private fun updateIndex() {
        lastPsiModificationCount = freshPsiModificationCount()
        index.refreshGlobal()
    }

    // internal use only
    fun getStatistics(): Pair<Int, Int> {
        assert(ApplicationManager.getApplication().isInternal)
        return Pair(index.getSize(), index.timesGlobalRefreshRequested)
    }

    // internal use only
    override fun getStatus() = when {
        DumbService.isDumb(project) -> Status.DUMB_MODE
        index.isUsable() -> Status.ACTIVE
        else -> Status.INDEX_REFRESHING
    }
//
//    @TestOnly
//    override fun search(str: String, psiFile: PsiFile?, precise: Boolean): Map<Double, List<String>> {
//        return if (isUsable()) {
//            index.refreshLocal(psiFile)
//            getSearch(precise).search(str)
//        } else mapOf()
//    }
//
//    @TestOnly
//    fun getIndex() = index
//
//    @TestOnly
//    fun forceGlobalIndexRefreshing() {
//        index.waitForGlobalRefreshing()
//    }
//
//    @TestOnly
//    fun forceLocalIndexRefreshing(psiFile: PsiFile?) {
//        index.refreshLocal(psiFile)
//    }
}