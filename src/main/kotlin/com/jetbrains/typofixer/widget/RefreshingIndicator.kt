package com.jetbrains.typofixer.widget

import com.intellij.openapi.wm.CustomStatusBarWidget
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.util.Consumer
import com.jetbrains.typofixer.search.DLSearcher
import com.jetbrains.typofixer.search.Searcher
import java.awt.event.MouseEvent
import javax.swing.JLabel


/**
 * @author bronti.
 */

class RefreshingIndicator(val searcher: DLSearcher) : CustomStatusBarWidget, StatusBarWidget.WidgetPresentation {
    // todo: make icons
    // todo: make popup tip

    private val ACTIVE_TEXT = "active"
    private val NOT_ACTIVE_TEXT = "index refreshing"

    private val myLabel = JLabel(ACTIVE_TEXT)

    override fun getTooltipText() = "Typo Fixer index refreshing indicator"

    override fun getClickConsumer(): Consumer<MouseEvent>? = null

    override fun getComponent() = myLabel

    override fun ID() = "Typo Fixer status bar"

    fun update() {
        if (!searcher.project.isInitialized) return
        myLabel.text = when (searcher.getStatus()) {
            Searcher.Status.INDEX_REFRESHING -> NOT_ACTIVE_TEXT + " (${searcher.getStatistics().second})"
            Searcher.Status.DUMB_MODE -> NOT_ACTIVE_TEXT + " (${searcher.getStatistics().second})"
            Searcher.Status.ACTIVE -> ACTIVE_TEXT + " ${searcher.getStatistics()}"
        }
    }

    override fun getPresentation(platformType: StatusBarWidget.PlatformType): StatusBarWidget.WidgetPresentation {
        return this
    }

    override fun install(statusBar: StatusBar) {

    }

    override fun dispose() {
    }
}