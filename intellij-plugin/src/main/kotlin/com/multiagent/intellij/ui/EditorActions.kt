package com.multiagent.intellij.ui

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager

private const val TOOL_WINDOW_ID = "MultiAgent"

/** Selected text + a `path (line N[-M])` label, or null when there's nothing selected. */
private data class Selection(val text: String, val path: String, val lines: IntRange)

private fun selectionOf(e: AnActionEvent): Selection? {
    val editor = e.getData(CommonDataKeys.EDITOR) ?: return null
    val model = editor.selectionModel
    if (!model.hasSelection()) return null
    val text = model.selectedText ?: return null
    val project = e.project ?: return null
    val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
    val basePath = project.basePath
    val path = if (file != null && basePath != null && file.path.startsWith("$basePath/")) {
        file.path.removePrefix("$basePath/")
    } else {
        file?.name ?: "selection"
    }
    val startLine = editor.document.getLineNumber(model.selectionStart) + 1
    val endLine = editor.document.getLineNumber(model.selectionEnd) + 1
    return Selection(text, path, startLine..endLine)
}

private fun Selection.format(): String {
    val lineLabel = if (lines.first == lines.last) "line ${lines.first}" else "lines ${lines.first}-${lines.last}"
    return "`$path` ($lineLabel):\n```\n$text\n```"
}

/** Activates the MultiAgent tool window (creating its content if needed) then runs [action] on its chat panel. */
private fun withChatPanel(project: Project, action: (MultiAgentChatPanel) -> Unit) {
    val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID) ?: return
    toolWindow.activate({
        val panel = toolWindow.contentManager.contents.firstOrNull()?.component as? MultiAgentChatPanel
        panel?.let(action)
    }, true)
}

/** Editor right-click / Tools menu: drop the current selection into the composer without sending. */
class AddSelectionToChatAction : AnAction() {
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.getData(CommonDataKeys.EDITOR)?.selectionModel?.hasSelection() == true
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val selection = selectionOf(e) ?: return
        withChatPanel(project) { it.appendToComposer(selection.format()) }
    }
}

/** Editor right-click / Tools menu: send the selection with an "explain this" prompt immediately. */
class ExplainSelectionAction : AnAction() {
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.getData(CommonDataKeys.EDITOR)?.selectionModel?.hasSelection() == true
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val selection = selectionOf(e) ?: return
        withChatPanel(project) { it.sendNow("Explain this code:\n\n${selection.format()}") }
    }
}
