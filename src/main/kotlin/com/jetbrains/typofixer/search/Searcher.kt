package com.jetbrains.typofixer.search

import com.intellij.ProjectTopics
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
import com.jetbrains.typofixer.search.index.Index
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

    abstract fun findClosest(str: String, psiFile: PsiFile?): String?
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
        // 7: less concurrent index
        val VERSION = 8
    }

    private val maxError = 2
    private val signature = ComplexSignature()

    private val index = Index(signature)

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

                // todo:
                override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
                }

                override fun fileClosed(source: FileEditorManager, file: VirtualFile) {
                }
            })
        }
    }

    override fun getStatus() = if (DumbService.isDumb(project)) Status.DUMB_MODE else if (index.isUsable()) Status.ACTIVE else Status.INDEX_REFRESHING
    private fun canSearch() = getStatus() == Status.ACTIVE

    private fun getSearch(precise: Boolean) = if (precise) preciceSearch else simpleSearch

    override fun findClosest(str: String, psiFile: PsiFile?): String? {
        return if (canSearch()) {
            index.refreshLocal(psiFile)
            getSearch(false).findClosest(str).word
        } else null
    }

    private fun updateIndex() {
        lastPsiModificationCount = freshPsiModificationCount()
        index.refreshGlobal(project)
    }

    // internal use only
    fun getStatistics() = Pair(index.getSize(), index.timesGlobalRefreshRequested)

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
        index.waitForGlobalRefreshing(project)
    }

    @TestOnly
    fun forceLocalIndexRefreshing(psiFile: PsiFile?) {
        index.refreshLocal(psiFile)
    }
}