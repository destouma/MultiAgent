package com.multiagent.intellij.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

/**
 * Phase 1: a single streaming conversation (see [MultiAgentChatPanel]). Phase 0's placeholder
 * panel is gone now that the storage spike has passed.
 */
class MultiAgentToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = MultiAgentChatPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, null, false)
        toolWindow.contentManager.addContent(content)
    }
}
