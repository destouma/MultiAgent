package com.multiagent.intellij.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBUI
import javax.swing.BoxLayout
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * Phase 0: an empty tool window that just proves the plugin loads and `runIde` works.
 * Phases 1+ replace this panel with the chat thread + composer (see TODO.md).
 */
class MultiAgentToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(12)
            add(JLabel("MultiAgent — scaffold"))
            add(JBUI.Panels.simplePanel())
            add(JLabel("<html><i>Phase 0. Core forked from desktop-java; chat UI comes next.</i></html>"))
        }
        val content = ContentFactory.getInstance().createContent(panel, null, false)
        toolWindow.contentManager.addContent(content)
    }
}
