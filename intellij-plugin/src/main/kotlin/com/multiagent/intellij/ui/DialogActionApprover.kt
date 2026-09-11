package com.multiagent.intellij.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.multiagent.intellij.core.action.ActionApprover
import com.multiagent.intellij.core.action.PendingAction
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * The "ask before writing/deleting/renaming a file (or running a command / git op)" gate
 * for [com.multiagent.intellij.core.service.ToolLoopRunner]. `approve()` is called from the
 * tool-loop's background thread and must block until the user answers - `invokeAndWait`
 * with [ModalityState.any] does that regardless of whatever modal state the IDE is in.
 */
class DialogActionApprover(private val project: Project) : ActionApprover {
    override fun approve(action: PendingAction): Boolean {
        var approved = false
        ApplicationManager.getApplication().invokeAndWait({
            approved = ActionApprovalDialog(project, action).showAndGet()
        }, ModalityState.any())
        return approved
    }
}

private class ActionApprovalDialog(project: Project, action: PendingAction) : DialogWrapper(project, false) {

    private val panel: JComponent

    init {
        title = "MultiAgent"
        setOKButtonText("Approve")
        setCancelButtonText("Decline")

        val detail = action.detail()
        panel = JPanel(BorderLayout(0, 8)).apply {
            border = JBUI.Borders.empty(4)
            add(JBLabel(action.summary()), BorderLayout.NORTH)
            if (!detail.isNullOrBlank()) {
                val area = JBTextArea(detail).apply {
                    isEditable = false
                    font = Font(Font.MONOSPACED, Font.PLAIN, 12)
                }
                add(JBScrollPane(area).apply { preferredSize = Dimension(600, 320) }, BorderLayout.CENTER)
            }
        }
        init()
    }

    override fun createCenterPanel(): JComponent = panel
}
