package com.jetbrains.typofixer

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.AbstractProjectComponent
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.WindowManager
import com.jetbrains.typofixer.search.DLSearcher
import com.jetbrains.typofixer.widget.RefreshingIndicator

/**
 * @author bronti.
 */
class TypoFixerComponent(project: Project) : AbstractProjectComponent(project) {
    private var mySearcher: DLSearcher? = null
    private var myStatusBarWidget: RefreshingIndicator? = null

    private val appManager = ApplicationManager.getApplication()

    val searcher: DLSearcher
        get() = mySearcher!!

    override fun initComponent() {
        mySearcher = DLSearcher(myProject)
        if (appManager.isInternal) {
            myStatusBarWidget = RefreshingIndicator(searcher)
        }
    }

    override fun projectOpened() {
        val statusBar = WindowManager.getInstance().getStatusBar(myProject)

        if (appManager.isInternal) {
            statusBar.addWidget(myStatusBarWidget!!, "before Position")
            Disposer.register(myProject, myStatusBarWidget!!)
            Disposer.register(myProject, Disposable { statusBar.removeWidget(myStatusBarWidget!!.ID()) })

            onSearcherStatusMaybeChanged()
        }
    }

    fun onSearcherStatusMaybeChanged() {
        if (appManager.isInternal && myStatusBarWidget != null) {
            myStatusBarWidget!!.update()
            WindowManager.getInstance().getStatusBar(myProject)?.updateWidget(myStatusBarWidget!!.ID())
        }
    }
}