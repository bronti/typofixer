package com.jetbrains.typofixer

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.AbstractProjectComponent
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.WindowManager
import com.jetbrains.typofixer.search.DLSearcher
import com.jetbrains.typofixer.settings.TypoFixerStatistics
import com.jetbrains.typofixer.widget.RefreshingIndicator

/**
 * @author bronti.
 */
val Project.typoFixerComponent get() = getComponent(TypoFixerComponent::class.java)!!
val Project.statistics get() = typoFixerComponent.statistics
val Project.searcher get() = typoFixerComponent.searcher

class TypoFixerComponent(project: Project) : AbstractProjectComponent(project) {

    var isActive: Boolean = true
    val statistics = TypoFixerStatistics()
    private var isInitialized = false

    lateinit var searcher: DLSearcher private set

    // initialized only in internal mode!!!
    private lateinit var statusBarWidget: RefreshingIndicator

    private val appManager = ApplicationManager.getApplication()

    override fun initComponent() {
        searcher = DLSearcher(myProject)
        if (appManager.isInternal) {
            statusBarWidget = RefreshingIndicator(searcher)
        }
        isInitialized = true
    }

    override fun projectOpened() {
        val statusBar = WindowManager.getInstance().getStatusBar(myProject)

        if (appManager.isInternal) {
            statusBar.addWidget(statusBarWidget, "before Position")
            Disposer.register(myProject, statusBarWidget)
            Disposer.register(myProject, Disposable { statusBar.removeWidget(statusBarWidget.ID()) })

            onSearcherStatusMaybeChanged()
        }
    }

    fun onSearcherStatusMaybeChanged() {
        if (!isInitialized) return
        if (appManager.isInternal) {
            statusBarWidget.update()
            WindowManager.getInstance().getStatusBar(myProject)?.updateWidget(statusBarWidget.ID())
        }
    }
}