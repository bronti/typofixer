package com.jetbrains.typofixer.search

import com.intellij.ProjectTopics
import com.intellij.openapi.components.AbstractProjectComponent
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
import com.jetbrains.typofixer.search.distance.DamerauLevenshteinDistanceTo
import com.jetbrains.typofixer.search.index.Index
import com.jetbrains.typofixer.search.signature.SimpleSignature

/**
 * @author bronti.
 */

abstract class Searcher(project: Project) : AbstractProjectComponent(project) {
    abstract fun findClosest(str: String, psiFile: PsiFile?): String?
    abstract fun findClosestWithInfo(str: String, psiFile: PsiFile?): Pair<String?, Int>
    abstract fun search(str: String, psiFile: PsiFile?, precise: Boolean = false): Map<Int, List<String>>
    abstract fun forceIndexRefreshing(): Unit
}

class DLSearcher(project: Project) : Searcher(project) {

    companion object {
        // signature with length + char frequency
        val VERSION = 2
    }

    private val maxError = 2
    private val signature = SimpleSignature()
    private val distanceTo = { it: String -> DamerauLevenshteinDistanceTo(it, maxError) }
    // todo: make private
    val index = Index(signature)
    private var updateNeeded = true
    private var psiModificationCount = 0L

    private val simpleSearch = DLSearchAlgorithm(maxError, distanceTo, index)
    private val preciceSearch = DLPreciseSearchAlgorithm(maxError, distanceTo, index)

    // todo: make private
    fun getSearch(precise: Boolean) = if (precise) preciceSearch else simpleSearch
    private fun canSearch() = !DumbService.isDumb(myProject)

    override fun findClosest(str: String, psiFile: PsiFile?): String? {
        return if (canSearch()) {
            index.refreshLocal(psiFile)
            getSearch(false).findClosest(str)
        } else null
    }

    override fun findClosestWithInfo(str: String, psiFile: PsiFile?): Pair<String?, Int> {
        return if (canSearch()) {
            index.refreshLocal(psiFile)
            getSearch(false).findClosestWithInfo(str)
        } else Pair(null, 0)
    }

    override fun forceIndexRefreshing() {
        index.refreshGlobal(myProject)
    }

    override fun search(str: String, psiFile: PsiFile?, precise: Boolean): Map<Int, List<String>> {
        return if (canSearch()) {
            index.refreshLocal(psiFile)
            getSearch(precise).search(str)
        } else mapOf()
    }

    private fun updateIndex() {
        DumbService.getInstance(myProject).smartInvokeLater {
            index.refreshGlobal(myProject)
            updateNeeded = false
        }
    }

    // todo: in background
    override fun initComponent() {

        val connection = myProject.messageBus.connect(myProject)

        DumbService.getInstance(myProject).smartInvokeLater {
            connection.subscribe(ProjectTopics.PROJECT_ROOTS, object : ModuleRootListener {
                override fun rootsChanged(event: ModuleRootEvent) {
                    updateIndex()
                }
            })

            connection.subscribe(PsiModificationTracker.TOPIC, PsiModificationTracker.Listener {
                val count = PsiModificationTracker.SERVICE.getInstance(myProject).outOfCodeBlockModificationCount
                if (psiModificationCount != count) {
                    psiModificationCount = count
                    updateNeeded = true
                }
            })

            connection.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) {
                    if (updateNeeded) updateIndex()
                }

                // todo:
                override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
                }

                override fun fileClosed(source: FileEditorManager, file: VirtualFile) {
                }
            })
        }

        updateIndex()
    }
}