package com.jetbrains.typofixer.search

import com.intellij.ProjectTopics
import com.intellij.openapi.components.AbstractProjectComponent
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootEvent
import com.intellij.openapi.roots.ModuleRootListener
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiModificationTracker
import com.jetbrains.typofixer.search.distance.DamerauLevenshteinDistanceTo
import com.jetbrains.typofixer.search.index.Index
import com.jetbrains.typofixer.search.signature.SimpleSignature

/**
 * @author bronti.
 */

abstract class Searcher(project: Project) : AbstractProjectComponent(project) {
    abstract fun findClosestInFile(str: String, psiFile: PsiFile): String?
}

class DLSearcher(project: Project) : Searcher(project) {

    private val maxError = 2
    private val signature = SimpleSignature()
    private val distanceTo = { it: String -> DamerauLevenshteinDistanceTo(it, maxError) }
    private val index = Index(signature)
    private var updateNeeded = true
    private var psiModificationCount = 0L

    private fun canSearch() = !DumbService.isDumb(myProject)

    override fun findClosestInFile(str: String, psiFile: PsiFile): String? {
        return if (canSearch()) {
            index.refreshLocal(psiFile)
            DLSearcherAlgorithm(maxError, distanceTo, index).findClosest(str)
        } else null
    }

    private fun updateIndex() {
        DumbService.getInstance(myProject).smartInvokeLater {
            index.refreshGlobal(myProject)
            updateNeeded = false
        }
    }

    // todo: in background
    override fun initComponent() {
        updateIndex()

        val connection = myProject.messageBus.connect(myProject)

        connection.subscribe(ProjectTopics.PROJECT_ROOTS, object : ModuleRootListener {
            override fun rootsChanged(event: ModuleRootEvent) {
                updateIndex()
                updateNeeded = false
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
                updateNeeded = false
            }
        })
    }
}