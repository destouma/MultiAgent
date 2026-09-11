package com.multiagent.intellij.ui

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.wm.WindowManager
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollBar
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.multiagent.intellij.core.model.ChatMessage
import com.multiagent.intellij.core.model.Conversation
import com.multiagent.intellij.core.model.MessageRole
import com.multiagent.intellij.core.model.Persona
import com.multiagent.intellij.core.service.ChatService
import com.multiagent.intellij.service.MultiAgentService
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.event.KeyEvent
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * A conversation - chat picker, message list, composer - auto-bound to the open [Project]'s
 * folder (Phase 2: [Conversation.getWorkspacePath] = `project.basePath`, which makes
 * `ChatService.send` route through `ToolLoopRunner` and its workspace/git/run_command
 * tools). A project can hold several conversations (Phase 3); switching the chat picker
 * swaps `conversation` and reloads the message list in place - the panel itself is a
 * per-project singleton (one per tool window), not per-conversation. Talks to
 * [MultiAgentService] (the forked `core`) and marshals every [ChatService.Listener] callback
 * onto the EDT itself, since `ChatService.send` calls back from its own background executor.
 */
class MultiAgentChatPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val service = ApplicationManager.getApplication().getService(MultiAgentService::class.java)
    private var conversation: Conversation = service.activeConversationForProject(project)

    private val messagesPanel = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
    private val scrollPane = JBScrollPane(messagesPanel).apply {
        verticalScrollBarPolicy = JBScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
        border = BorderFactory.createEmptyBorder()
    }
    private val input = JBTextArea(3, 20).apply {
        lineWrap = true
        wrapStyleWord = true
        emptyText.text = "Message... (Enter to send, Shift+Enter for newline)"
    }
    private val sendButton = JButton("Send")
    private val conversationCombo = JComboBox<Conversation>()
    private val personaCombo = JComboBox<Persona>()
    private val modelCombo = JComboBox<String>().apply { isEditable = true }
    private val healthLabel = JBLabel("checking...")
    private val statusLabel = JBLabel(" ")

    private var streamingArea: JBTextArea? = null
    private var currentOpRow: ToolOpRow? = null
    private var updatingCombos = false

    init {
        border = JBUI.Borders.empty(4)
        add(buildTopBar(), BorderLayout.NORTH)
        add(scrollPane, BorderLayout.CENTER)
        add(buildComposer(), BorderLayout.SOUTH)

        refreshConversationCombo()
        refreshPersonaCombo()
        loadHistory()
        refreshHealthAndModels()
    }

    private fun buildTopBar(): JComponent {
        // One fixed-height FlowLayout row per concern rather than letting any row wrap:
        // FlowLayout's preferred-height calculation assumes a single line, so in a narrow
        // docked tool window a wrapped second line gets clipped instead of growing the bar.
        val top = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }

        val row0 = JPanel(FlowLayout(FlowLayout.LEFT, 6, 2))
        row0.add(JBLabel("Chat:"))
        conversationCombo.preferredSize = Dimension(150, conversationCombo.preferredSize.height)
        conversationCombo.addActionListener {
            if (updatingCombos) return@addActionListener
            (conversationCombo.selectedItem as? Conversation)?.let { if (it.id != conversation.id) switchTo(it) }
        }
        row0.add(conversationCombo)
        row0.add(JButton("+").apply {
            toolTipText = "New chat"
            addActionListener {
                val created = service.newConversationForProject(project)
                refreshConversationCombo()
                switchTo(created)
            }
        })
        row0.add(JButton("✕").apply {
            toolTipText = "Delete this chat"
            addActionListener { deleteCurrentConversation() }
        })
        row0.maximumSize = Dimension(Int.MAX_VALUE, row0.preferredSize.height)
        top.add(row0)

        val row1 = JPanel(FlowLayout(FlowLayout.LEFT, 6, 2))
        row1.add(JBLabel("Model:"))
        modelCombo.preferredSize = Dimension(150, modelCombo.preferredSize.height)
        row1.add(modelCombo)
        val refresh = JButton("↻").apply {
            toolTipText = "Refresh models / health"
            addActionListener { refreshHealthAndModels() }
        }
        row1.add(refresh)
        row1.maximumSize = Dimension(Int.MAX_VALUE, row1.preferredSize.height)
        top.add(row1)

        val row2 = JPanel(FlowLayout(FlowLayout.LEFT, 6, 2))
        row2.add(JBLabel("Persona:"))
        personaCombo.preferredSize = Dimension(150, personaCombo.preferredSize.height)
        personaCombo.addActionListener {
            if (updatingCombos) return@addActionListener
            val persona = personaCombo.selectedItem as? Persona ?: return@addActionListener
            if (persona.id != conversation.personaId) {
                conversation = service.store.setConversationPersona(conversation.id, persona.id)
            }
        }
        row2.add(personaCombo)
        row2.maximumSize = Dimension(Int.MAX_VALUE, row2.preferredSize.height)
        top.add(row2)

        val row3 = JPanel(FlowLayout(FlowLayout.LEFT, 6, 2))
        row3.add(healthLabel)
        val settings = JButton("Settings...").apply {
            addActionListener {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, "MultiAgent")
            }
        }
        row3.add(settings)
        row3.maximumSize = Dimension(Int.MAX_VALUE, row3.preferredSize.height)
        top.add(row3)

        val workspacePath = conversation.workspacePath
        if (!workspacePath.isNullOrBlank()) {
            val row4 = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0))
            row4.add(JBLabel("Workspace: $workspacePath").apply {
                foreground = Color.GRAY
                font = font.deriveFont(font.size2D - 1f)
                toolTipText = workspacePath
            })
            row4.maximumSize = Dimension(Int.MAX_VALUE, row4.preferredSize.height)
            top.add(row4)
        }

        return top
    }

    private fun refreshConversationCombo() {
        updatingCombos = true
        try {
            conversationCombo.removeAllItems()
            val items = service.conversationsForProject(project)
            items.forEach { conversationCombo.addItem(it) }
            // Select the actual list item (by id), not a separately-fetched Conversation instance:
            // Conversation has no equals()/hashCode(), so JComboBox's popup-highlight/indexOf lookup
            // needs object identity with one of the items just added, not just a matching title.
            conversationCombo.selectedItem = items.firstOrNull { it.id == conversation.id } ?: items.firstOrNull()
        } finally {
            updatingCombos = false
        }
    }

    private fun refreshPersonaCombo() {
        updatingCombos = true
        try {
            personaCombo.removeAllItems()
            val items = service.personas()
            items.forEach { personaCombo.addItem(it) }
            val pinnedId = conversation.personaId
            personaCombo.selectedItem = items.firstOrNull { it.id == pinnedId } ?: items.firstOrNull()
        } finally {
            updatingCombos = false
        }
    }

    /** Swaps the active conversation in place: reloads history, re-seeds persona/model from the new chat's pins. */
    private fun switchTo(newConversation: Conversation) {
        conversation = newConversation
        service.setActiveConversation(project, newConversation)
        currentOpRow = null
        streamingArea = null
        messagesPanel.removeAll()
        messagesPanel.revalidate()
        messagesPanel.repaint()
        refreshConversationCombo()
        refreshPersonaCombo()
        if (!newConversation.model.isNullOrBlank()) {
            modelCombo.editor.item = newConversation.model
        }
        loadHistory()
    }

    private fun deleteCurrentConversation() {
        val confirmed = Messages.showYesNoDialog(
            project, "Delete \"${conversation.title}\"? This cannot be undone.",
            "MultiAgent", "Delete", "Cancel", Messages.getWarningIcon()
        ) == Messages.YES
        if (!confirmed) return
        val next = service.deleteConversation(project, conversation.id)
        switchTo(next)
    }

    private fun buildComposer(): JComponent {
        input.addKeyListener(object : java.awt.event.KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER && !e.isShiftDown) {
                    e.consume()
                    onSend()
                }
            }
        })
        sendButton.addActionListener { onSend() }

        val bottom = JPanel(BorderLayout(4, 4))
        bottom.border = JBUI.Borders.emptyTop(4)
        bottom.add(JBScrollPane(input), BorderLayout.CENTER)
        val right = JPanel()
        right.layout = BoxLayout(right, BoxLayout.Y_AXIS)
        right.add(sendButton)
        bottom.add(right, BorderLayout.EAST)
        val south = JPanel(BorderLayout())
        south.add(statusLabel, BorderLayout.NORTH)
        south.add(bottom, BorderLayout.CENTER)
        return south
    }

    private fun loadHistory() {
        val messages: List<ChatMessage> = service.store.getMessages(conversation.id)
        for (m in messages) {
            addBubble(m.role, m.content)
        }
        scrollToBottom()
    }

    private fun addBubble(role: MessageRole, text: String): JBTextArea {
        val area = JBTextArea(text).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            border = JBUI.Borders.empty(6, 8)
            background = if (role == MessageRole.USER) Color(0x1D, 0x4E, 0x89, 0x22) else background
        }
        val row = JPanel(BorderLayout())
        row.border = JBUI.Borders.empty(2, 4)
        val label = JBLabel(if (role == MessageRole.USER) "You" else "Assistant").apply {
            font = font.deriveFont(font.size2D - 1f)
            foreground = Color.GRAY
        }
        val wrapper = JPanel(BorderLayout())
        wrapper.add(label, BorderLayout.NORTH)
        wrapper.add(area, BorderLayout.CENTER)
        row.add(wrapper, BorderLayout.CENTER)
        messagesPanel.add(row)
        messagesPanel.revalidate()
        messagesPanel.repaint()
        return area
    }

    private class ToolOpRow(val panel: JPanel, val label: JBLabel, val buttons: JPanel)

    /** One collapsible-free row per tool call: "running" adds it, "ok"/"error" updates it in place. */
    private fun addToolOpRow(): ToolOpRow {
        val label = JBLabel().apply {
            foreground = Color.GRAY
            font = font.deriveFont(font.size2D - 1f)
        }
        val buttons = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0))
        val row = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(1, 12)
            add(label, BorderLayout.CENTER)
            add(buttons, BorderLayout.EAST)
        }
        messagesPanel.add(row)
        messagesPanel.revalidate()
        messagesPanel.repaint()
        return ToolOpRow(row, label, buttons)
    }

    private fun onWorkspaceOp(op: String, path: String, status: String, detail: String?, checkpointId: String?) {
        val row = if (status == "running") addToolOpRow().also { currentOpRow = it }
                   else currentOpRow ?: addToolOpRow()

        val icon = when (status) {
            "running" -> "⏳"
            "ok" -> "✅"
            "error" -> "❌"
            else -> "•"
        }
        val text = buildString {
            append(icon).append(' ').append(op)
            if (path.isNotBlank()) append(' ').append(path)
            if (status == "error" && !detail.isNullOrBlank()) append(": ").append(detail)
        }
        row.label.text = text
        row.label.toolTipText = detail

        row.buttons.removeAll()
        if (checkpointId != null) {
            row.buttons.add(smallButton("View diff") { showCheckpointDiff(checkpointId, path) })
            row.buttons.add(smallButton("Revert") { revertCheckpoint(checkpointId, row) })
        }
        row.buttons.revalidate()
        row.panel.revalidate()
        row.panel.repaint()

        if (status != "running") {
            currentOpRow = null
            if (status == "ok" && op in FILE_MUTATING_OPS) {
                refreshWorkspace()
            }
        }
        scrollToBottom()
    }

    private fun smallButton(text: String, action: () -> Unit): JButton = JButton(text).apply {
        font = font.deriveFont(font.size2D - 1f)
        margin = JBUI.insets(0, 6)
        addActionListener { action() }
    }

    private fun showCheckpointDiff(checkpointId: String, path: String) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching { service.checkpointService.diff(checkpointId) }
            onEdt {
                result.onSuccess { d ->
                    val factory = DiffContentFactory.getInstance()
                    val before = d.before()?.let { factory.create(project, it) } ?: factory.createEmpty()
                    val after = d.after()?.let { factory.create(project, it) } ?: factory.createEmpty()
                    val request = SimpleDiffRequest("MultiAgent: ${d.path()}", before, after, "Before", "After")
                    DiffManager.getInstance().showDiff(project, request)
                }.onFailure { e ->
                    Messages.showErrorDialog(project, e.message ?: e.toString(), "Diff Failed")
                }
            }
        }
    }

    private fun revertCheckpoint(checkpointId: String, row: ToolOpRow) {
        val confirmed = Messages.showYesNoDialog(
            project, "Revert this change on disk?", "MultiAgent", "Revert", "Cancel", Messages.getWarningIcon()
        ) == Messages.YES
        if (!confirmed) return

        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching { service.checkpointService.revert(checkpointId) }
            onEdt {
                result.onSuccess {
                    refreshWorkspace()
                    row.buttons.removeAll()
                    row.buttons.add(JBLabel("reverted").apply {
                        foreground = Color.GRAY
                        font = font.deriveFont(font.size2D - 1f)
                    })
                    row.buttons.revalidate()
                    row.panel.revalidate()
                    row.panel.repaint()
                }.onFailure { e ->
                    Messages.showErrorDialog(project, e.message ?: e.toString(), "Revert Failed")
                }
            }
        }
    }

    /** So the editor/Project view pick up file changes a tool call (or a revert) made on disk. */
    private fun refreshWorkspace() {
        val path = conversation.workspacePath ?: return
        val file = LocalFileSystem.getInstance().refreshAndFindFileByPath(path) ?: return
        VfsUtil.markDirtyAndRefresh(false, true, true, file)
    }

    private fun scrollToBottom() {
        SwingUtilities.invokeLater {
            val bar: JBScrollBar = scrollPane.verticalScrollBar as JBScrollBar
            bar.value = bar.maximum
        }
    }

    /** Inserts text into the composer without sending - for the editor's "Add Selection" action. */
    fun appendToComposer(text: String) {
        input.text = if (input.text.isBlank()) text else input.text + "\n\n" + text
        input.requestFocusInWindow()
        input.caretPosition = input.text.length
    }

    /** Fills the composer and sends immediately - for the editor's "Explain" action. */
    fun sendNow(text: String) {
        input.text = text
        onSend()
    }

    private fun onSend() {
        val text = input.text.trim()
        if (text.isEmpty()) return
        input.text = ""
        sendButton.isEnabled = false

        addBubble(MessageRole.USER, text)
        val assistantArea = addBubble(MessageRole.ASSISTANT, "")
        scrollToBottom()
        streamingArea = assistantArea

        val model = (modelCombo.editor.item as? String)?.trim().orEmpty()
        if (model.isNotEmpty() && model != conversation.model) {
            conversation = service.store.setConversationModel(conversation.id, model)
            refreshConversationCombo()
        }
        val client = service.client()
        val persona = personaCombo.selectedItem as? Persona ?: service.persona()

        // Re-bound on every send (rather than once at panel creation) so the most recently
        // active project's tool window is the one whose dialogs the approval gate targets,
        // since ChatService's approver is a single application-wide field.
        service.chatService.setActionApprover(DialogActionApprover(project))

        service.chatService.send(
            client, conversation, text, persona,
            model, null, service.settings().maxHistory, 0, null,
            object : ChatService.Listener {
                override fun onToken(conversationId: String, messageId: String, delta: String) {
                    onEdt { streamingArea?.append(delta); scrollToBottom() }
                }

                override fun onDone(conversationId: String, message: ChatMessage) {
                    onEdt {
                        streamingArea?.text = message.content
                        streamingArea = null
                        sendButton.isEnabled = true
                        scrollToBottom()
                    }
                }

                override fun onError(conversationId: String, messageId: String, code: com.multiagent.intellij.core.llm.ErrorCode, message: String) {
                    onEdt {
                        streamingArea?.text = "Error ($code): $message"
                        streamingArea = null
                        sendButton.isEnabled = true
                    }
                }

                override fun onModelStatus(conversationId: String, status: String) {
                    onEdt { statusLabel.text = status }
                }

                override fun onWorkspaceOp(
                    conversationId: String, messageId: String, op: String, path: String,
                    status: String, detail: String?, checkpointId: String?
                ) {
                    onEdt { this@MultiAgentChatPanel.onWorkspaceOp(op, path, status, detail, checkpointId) }
                }
            }
        )
    }

    private fun refreshHealthAndModels() {
        healthLabel.text = "checking..."
        ApplicationManager.getApplication().executeOnPooledThread {
            val client = service.client()
            val health = runCatching { client.checkHealth() }
            val models = runCatching { client.listModels() }.getOrDefault(emptyList())
            onEdt {
                health.fold(
                    onSuccess = { h -> healthLabel.text = if (h.ok()) "● connected" else "● ${h.message()}" },
                    onFailure = { e -> healthLabel.text = "● ${e.message ?: "offline"}" }
                )
                val current = (modelCombo.editor.item as? String)?.trim().orEmpty()
                    .ifEmpty { service.settings().model }
                modelCombo.removeAllItems()
                models.forEach { modelCombo.addItem(it.id()) }
                if (current.isNotEmpty()) {
                    modelCombo.editor.item = current
                }
                service.lastHealthText = healthLabel.text
                service.lastModelText = current
                WindowManager.getInstance().getStatusBar(project)?.updateWidget(MultiAgentStatusBarWidgetFactory.ID)
            }
        }
    }

    private fun onEdt(action: () -> Unit) {
        ApplicationManager.getApplication().invokeLater(action)
    }

    companion object {
        private val FILE_MUTATING_OPS = setOf("write_file", "delete_file", "rename_file")
    }
}
