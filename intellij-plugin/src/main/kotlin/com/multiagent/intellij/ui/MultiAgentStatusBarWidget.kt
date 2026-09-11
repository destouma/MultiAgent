package com.multiagent.intellij.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.util.Consumer
import com.multiagent.intellij.service.MultiAgentService
import java.awt.Component
import java.awt.event.MouseEvent

/** Registered as a `statusBarWidgetFactory` - see plugin.xml. One widget instance per project window. */
class MultiAgentStatusBarWidgetFactory : StatusBarWidgetFactory {
    override fun getId(): String = ID
    override fun getDisplayName(): String = "MultiAgent"
    override fun isAvailable(project: Project): Boolean = true
    override fun createWidget(project: Project): StatusBarWidget = MultiAgentStatusBarWidget(project)

    companion object {
        const val ID = "MultiAgent.StatusBar"
    }
}

/**
 * Shows the last health/model check any [MultiAgentChatPanel] performed - cheap read of
 * [MultiAgentService]'s cached fields rather than its own network call, since a status bar
 * widget's `getText()` must return instantly. `MultiAgentChatPanel.refreshHealthAndModels`
 * calls `StatusBar.updateWidget(ID)` after every check to make this repaint.
 */
private class MultiAgentStatusBarWidget(private val project: Project) :
    StatusBarWidget, StatusBarWidget.TextPresentation {

    override fun ID(): String = MultiAgentStatusBarWidgetFactory.ID
    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this
    override fun dispose() {}

    override fun getText(): String {
        val service = ApplicationManager.getApplication().getService(MultiAgentService::class.java)
        val model = service.lastModelText.ifBlank { "no model" }
        return "MultiAgent: ${service.lastHealthText} · $model"
    }

    override fun getAlignment(): Float = Component.CENTER_ALIGNMENT
    override fun getTooltipText(): String = "Click to open the MultiAgent tool window"
    override fun getClickConsumer(): Consumer<MouseEvent> = Consumer {
        ToolWindowManager.getInstance(project).getToolWindow("MultiAgent")?.activate(null)
    }
}
