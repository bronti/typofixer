package com.jetbrains.typofixer.search

import com.intellij.ProjectTopics
import com.intellij.openapi.components.AbstractProjectComponent
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootEvent
import com.intellij.openapi.roots.ModuleRootListener
import com.intellij.psi.util.PsiModificationTracker
import com.jetbrains.typofixer.search.distance.DamerauLevenshteinDistanceTo
import com.jetbrains.typofixer.search.index.Index
import com.jetbrains.typofixer.search.signature.SimpleSignature

/**
 * @author bronti.
 */

// todo: merge with Searcher (?)
abstract class SearcherProvider(project: Project) : AbstractProjectComponent(project) {
    abstract fun getSearcher(): Searcher
}

class DLSearcherProvider(project: Project) : SearcherProvider(project) {

    private val maxError = 2
    private val signature = SimpleSignature()
    private val distanceTo = { it: String -> DamerauLevenshteinDistanceTo(it, maxError) }
    private val index = Index(signature)
    private var updateNeeded = false
    private var outOfCodeBlockModificationCount = 0L

    override fun getSearcher() = DLSearcher(maxError, distanceTo, index)

    private fun updateIndex() {
        DumbService.getInstance(myProject).smartInvokeLater {
            index.refreshGlobal(myProject)
            updateNeeded = false
        }
    }

    // todo: in background
    override fun initComponent() {
        updateIndex()

        myProject.messageBus.connect(myProject).subscribe(ProjectTopics.PROJECT_ROOTS, object : ModuleRootListener {
            override fun rootsChanged(event: ModuleRootEvent) = updateIndex()
        })

        myProject.messageBus.connect(myProject).subscribe(PsiModificationTracker.TOPIC, PsiModificationTracker.Listener {
            val count = PsiModificationTracker.SERVICE.getInstance(myProject).outOfCodeBlockModificationCount
            if (outOfCodeBlockModificationCount != count) {
                outOfCodeBlockModificationCount = count
                updateNeeded = true
            }
        })

        myProject.messageBus.connect(myProject).subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun selectionChanged(event: FileEditorManagerEvent) {
                if (updateNeeded) updateIndex()
            }
        })
    }
}