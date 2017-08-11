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
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiModificationTracker
import com.jetbrains.typofixer.search.index.CombinedIndex
import com.jetbrains.typofixer.search.signature.ComplexSignature
import org.jetbrains.annotations.TestOnly

/**
 * @author bronti.
 */

abstract class Searcher {

    enum class Status {
        DUMB_MODE,
        INDEX_REFRESHING,
        ACTIVE
    }

    abstract fun findClosest(element: PsiElement?, str: String, wordTypes: Array<CombinedIndex.WordType>, isTooLate: () -> Boolean): SearchAlgorithm.SearchResult
    abstract fun findClosestAmongKeywords(str: String, keywords: List<String>, isTooLate: () -> Boolean): SearchAlgorithm.SearchResult
    abstract fun search(str: String, psiFile: PsiFile?, precise: Boolean = false): Map<Int, List<String>>
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
        val VERSION = 11
    }

    private val maxError = 2
    private val signature = ComplexSignature()

    private val index = CombinedIndex(project, signature)

    private var lastPsiModificationCount = 0L
    private fun freshPsiModificationCount() = PsiModificationTracker.SERVICE.getInstance(project).outOfCodeBlockModificationCount

    private val simpleSearch = DLSearchAlgorithm(maxError, index)
    private val preciceSearch = DLPreciseSearchAlgorithm(maxError, index)

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

    override fun getStatus() = when {
        DumbService.isDumb(project) -> Status.DUMB_MODE
        index.isUsable() -> Status.ACTIVE
        else -> Status.INDEX_REFRESHING
    }

    private fun canSearch() = getStatus() == Status.ACTIVE

    private fun getSearch(precise: Boolean) = if (precise) preciceSearch else simpleSearch

    override fun findClosest(element: PsiElement?, str: String, wordTypes: Array<CombinedIndex.WordType>, isTooLate: () -> Boolean): SearchAlgorithm.SearchResult {
        return if (canSearch()) {
            // todo: isTooLate into refreshLocal?
            index.refreshLocal(element)
            getSearch(false).findClosest(str, isTooLate, wordTypes)
        } else getSearch(false).EMPTY_RESULT
    }

    override fun findClosestAmongKeywords(str: String, keywords: List<String>, isTooLate: () -> Boolean): SearchAlgorithm.SearchResult {
        return if (canSearch()) {
            // todo: isTooLate into refreshLocal?
            index.refreshLocalWithKeywords(keywords)
            getSearch(false).findClosest(str, isTooLate, arrayOf(CombinedIndex.WordType.KEYWORD))
        } else getSearch(false).EMPTY_RESULT
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

    @TestOnly
    override fun search(str: String, psiFile: PsiFile?, precise: Boolean): Map<Int, List<String>> {
        return if (canSearch()) {
            index.refreshLocal(psiFile)
            getSearch(precise).search(str)
        } else mapOf()
    }

    @TestOnly
    fun getIndex() = index

    @TestOnly
    fun findClosestWithInfo(str: String, psiFile: PsiFile?): Pair<String?, Pair<Int, Int>> {
        return if (canSearch()) {
            index.refreshLocal(psiFile)
            getSearch(false).findClosestWithInfo(str)
        } else Pair(null, Pair(-1, -1))
    }

    @TestOnly
    fun forceGlobalIndexRefreshing() {
        index.waitForGlobalRefreshing()
    }

    @TestOnly
    fun forceLocalIndexRefreshing(psiFile: PsiFile?) {
        index.refreshLocal(psiFile)
    }
}