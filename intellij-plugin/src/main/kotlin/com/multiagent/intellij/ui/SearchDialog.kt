package com.multiagent.intellij.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.multiagent.intellij.core.model.SearchResult
import com.multiagent.intellij.core.persistence.ConversationStore
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * Conversation search (Phase 3), scoped to this project's own conversations - a plugin tool
 * window only ever cares about one project's chats, unlike desktop-java's global topbar
 * search across every folder. Backed directly by [ConversationStore.search] (title hits rank
 * first, then message-content hits with a snippet); double-click or Enter picks a result.
 */
class SearchDialog(
    project: Project,
    private val store: ConversationStore,
    private val workspacePath: String?
) : DialogWrapper(project, false) {

    private val field = JBTextField()
    private val listModel = DefaultListModel<SearchResult>()
    private val list = JBList(listModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = ResultRenderer()
    }

    var selected: SearchResult? = null
        private set

    init {
        title = "Search Conversations"
        field.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = runSearch()
            override fun removeUpdate(e: DocumentEvent) = runSearch()
            override fun changedUpdate(e: DocumentEvent) = runSearch()
        })
        list.addListSelectionListener { isOKActionEnabled = list.selectedValue != null }
        list.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                if (e.clickCount == 2 && list.selectedValue != null) doOKAction()
            }
        })
        isOKActionEnabled = false
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(0, 8))
        panel.border = JBUI.Borders.empty(4)
        panel.add(field, BorderLayout.NORTH)
        val scroll = JBScrollPane(list)
        scroll.preferredSize = Dimension(440, 280)
        panel.add(scroll, BorderLayout.CENTER)
        return panel
    }

    override fun getPreferredFocusedComponent(): JComponent = field

    private fun runSearch() {
        val term = field.text
        listModel.clear()
        if (term.isBlank()) return
        store.search(term).filter { it.workspacePath() == workspacePath }.forEach { listModel.addElement(it) }
    }

    override fun doOKAction() {
        selected = list.selectedValue
        super.doOKAction()
    }

    private class ResultRenderer : ListCellRenderer<SearchResult> {
        private val label = JBLabel().apply { border = JBUI.Borders.empty(3, 6) }

        override fun getListCellRendererComponent(
            list: JList<out SearchResult>, value: SearchResult, index: Int,
            isSelected: Boolean, cellHasFocus: Boolean
        ): Component {
            label.text = buildString {
                append(value.title() ?: "Untitled")
                if (!value.matchedInTitle() && !value.snippet().isNullOrBlank()) {
                    append(" — ").append(value.snippet())
                }
            }
            label.isOpaque = true
            label.background = if (isSelected) list.selectionBackground else list.background
            label.foreground = if (isSelected) list.selectionForeground else list.foreground
            return label
        }
    }
}
