package com.multiagent.intellij.ui

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.wm.WindowManager
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollBar
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.multiagent.intellij.core.llm.ErrorCode
import com.multiagent.intellij.core.model.ChatMessage
import com.multiagent.intellij.core.model.Conversation
import com.multiagent.intellij.core.model.ConversationKind
import com.multiagent.intellij.core.model.MessageRole
import com.multiagent.intellij.core.model.Persona
import com.multiagent.intellij.core.service.ChatService
import com.multiagent.intellij.core.service.ExportFormat
import com.multiagent.intellij.core.service.SpecialistModels
import com.multiagent.intellij.service.MultiAgentService
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.event.KeyEvent
import javax.swing.Box
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.DefaultListCellRenderer
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
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

    /**
     * A plain [JPanel]'s `getMaximumSize()` is unbounded regardless of content (Swing's
     * default, since [BorderLayout] reports [Int.MAX_VALUE] as its `maximumLayoutSize`) -
     * inside [messagesPanel]'s `BoxLayout.Y_AXIS`, that meant the *first* message row
     * absorbed all the tool window's leftover vertical space instead of the rows stacking
     * tightly, leaving a large blank gap under a short message. Every row added to
     * `messagesPanel` (bubbles, status lines, tool-op rows) uses this instead.
     */
    private class TightRowPanel(layout: java.awt.LayoutManager) : JPanel(layout) {
        override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
    }

    /**
     * Renders a combo entry as its conversation title. A plain combo, not a tab strip - see
     * README.md's "Real-IDE testing" section: a `JBTabbedPane` here rendered as just its "more
     * tabs" overflow dropdown with no visible label at all, in this tool window's actual width,
     * even down to a single sibling button - never root-caused, and blocking actually picking a
     * chat. A combo degrades to an ellipsis instead of disappearing entirely when it's too narrow.
     */
    private class ConversationRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean
        ): Component {
            val text = (value as? Conversation)?.title ?: "Untitled"
            return super.getListCellRendererComponent(list, text, index, isSelected, cellHasFocus)
        }
    }

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
    private val chatCombo = JComboBox<Conversation>().apply { renderer = ConversationRenderer() }
    private val personaLabel = JBLabel("Persona:")
    private val personaCombo = JComboBox<Persona>()
    private val specialistsButton = JButton("Specialists...").apply {
        addActionListener { openSpecialistModelsDialog() }
    }
    private val modelCombo = JComboBox<String>().apply { isEditable = true }
    private val healthLabel = JBLabel("checking...")
    private val statusLabel = JBLabel(" ")
    private val includeActiveFileCheckBox = JBCheckBox("Include active file").apply {
        toolTipText = "Prepend the editor's current selection (or just the open file's path) to the next message you send"
    }

    private var streamingArea: JBTextArea? = null
    private var currentOpRow: ToolOpRow? = null
    private var updatingCombos = false

    init {
        border = JBUI.Borders.empty(4)
        add(buildTopBar(), BorderLayout.NORTH)
        add(scrollPane, BorderLayout.CENTER)
        add(buildComposer(), BorderLayout.SOUTH)

        refreshChatPicker()
        refreshPersonaCombo()
        loadHistory()
        refreshHealthAndModels()
    }

    private fun buildTopBar(): JComponent {
        // One fixed-height row per concern rather than letting any row wrap: a wrapped second
        // line gets clipped in a narrow docked tool window instead of growing the bar (see
        // the BoxLayout.X_AXIS row below for the same reason - no FlowLayout wrap risk on the
        // two rows most likely to overflow).
        val top = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }

        val row0 = JPanel(BorderLayout(6, 0))
        chatCombo.addActionListener {
            if (updatingCombos) return@addActionListener
            val conv = chatCombo.selectedItem as? Conversation ?: return@addActionListener
            if (conv.id != conversation.id) switchTo(conv)
        }
        row0.add(chatCombo, BorderLayout.CENTER)
        // A single overflow button rather than one icon per action (New/Delete/Search/Export).
        row0.add(JButton().apply {
            icon = AllIcons.Actions.More
            toolTipText = "Chat actions"
            addActionListener { event -> showChatMenu(event.source as JComponent) }
        }, BorderLayout.EAST)
        row0.maximumSize = Dimension(Int.MAX_VALUE, row0.preferredSize.height)
        top.add(row0)

        val row1 = JPanel().apply { layout = BoxLayout(this, BoxLayout.X_AXIS) }
        row1.add(JBLabel("Model:"))
        row1.add(Box.createHorizontalStrut(6))
        modelCombo.preferredSize = Dimension(240, modelCombo.preferredSize.height)
        modelCombo.maximumSize = modelCombo.preferredSize
        row1.add(modelCombo)
        row1.add(Box.createHorizontalStrut(4))
        row1.add(JButton("↻").apply {
            toolTipText = "Refresh models / health"
            addActionListener { refreshHealthAndModels() }
        })
        row1.add(Box.createHorizontalStrut(12))
        row1.add(healthLabel)
        row1.add(Box.createHorizontalGlue())
        row1.add(JButton().apply {
            icon = AllIcons.General.Settings
            toolTipText = "MultiAgent settings"
            addActionListener { ShowSettingsUtil.getInstance().showSettingsDialog(project, "MultiAgent") }
        })
        row1.maximumSize = Dimension(Int.MAX_VALUE, row1.preferredSize.height)
        top.add(row1)

        val row2 = JPanel(FlowLayout(FlowLayout.LEFT, 6, 2))
        row2.add(personaLabel)
        personaCombo.preferredSize = Dimension(150, personaCombo.preferredSize.height)
        personaCombo.addActionListener {
            if (updatingCombos) return@addActionListener
            val persona = personaCombo.selectedItem as? Persona ?: return@addActionListener
            if (persona.id != conversation.personaId) {
                conversation = service.store.setConversationPersona(conversation.id, persona.id)
            }
        }
        row2.add(personaCombo)
        row2.add(specialistsButton)
        row2.maximumSize = Dimension(Int.MAX_VALUE, row2.preferredSize.height)
        top.add(row2)

        val workspacePath = conversation.workspacePath
        if (!workspacePath.isNullOrBlank()) {
            val row3 = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0))
            row3.add(JBLabel("Workspace: $workspacePath").apply {
                foreground = Color.GRAY
                font = font.deriveFont(font.size2D - 1f)
                toolTipText = workspacePath
            })
            row3.maximumSize = Dimension(Int.MAX_VALUE, row3.preferredSize.height)
            top.add(row3)
        }

        return top
    }

    /** Ordered by creation, not `updatedAt` - otherwise every sent message would reorder the list since `addMessage` bumps `updatedAt`. */
    private fun refreshChatPicker() {
        updatingCombos = true
        try {
            chatCombo.removeAllItems()
            val items = service.conversationsForProject(project).sortedBy { it.createdAt }
            items.forEach { chatCombo.addItem(it) }
            chatCombo.selectedItem = items.firstOrNull { it.id == conversation.id }
        } finally {
            updatingCombos = false
        }
    }

    private fun refreshPersonaCombo() {
        val isOrchestrator = conversation.kind == ConversationKind.ORCHESTRATOR
        // "orchestrator" is a coordinator persona, not a normal chat persona (mirrors
        // desktop-java: it's offered only as an orchestrator chat's Coordinator).
        personaLabel.text = if (isOrchestrator) "Coordinator:" else "Persona:"
        specialistsButton.isVisible = isOrchestrator

        updatingCombos = true
        try {
            personaCombo.removeAllItems()
            val all = service.personas()
            val items = if (isOrchestrator) all else all.filter { it.id != "orchestrator" }
            items.forEach { personaCombo.addItem(it) }
            val pinnedId = conversation.personaId
            val fallbackId = if (isOrchestrator) "orchestrator" else null
            personaCombo.selectedItem = items.firstOrNull { it.id == pinnedId }
                ?: items.firstOrNull { it.id == fallbackId }
                ?: items.firstOrNull()
        } finally {
            updatingCombos = false
        }
    }

    /** The tool window's single "⋮" overflow button - see the comment at its call site in [buildTopBar]. */
    private fun showChatMenu(invoker: JComponent) {
        val menu = JPopupMenu()
        menu.add(JMenuItem("New Chat").apply { addActionListener { createAndSwitch(ConversationKind.CHAT) } })
        menu.add(JMenuItem("New Orchestrator").apply { addActionListener { createAndSwitch(ConversationKind.ORCHESTRATOR) } })
        menu.addSeparator()
        menu.add(JMenuItem("Delete This Chat").apply { addActionListener { deleteCurrentConversation() } })
        menu.addSeparator()
        menu.add(JMenuItem("Search Conversations…").apply { addActionListener { openSearch() } })
        menu.add(JMenuItem("Export as Markdown").apply { addActionListener { exportConversation(markdown = true) } })
        menu.add(JMenuItem("Export as JSON").apply { addActionListener { exportConversation(markdown = false) } })
        menu.show(invoker, 0, invoker.height)
    }

    private fun createAndSwitch(kind: ConversationKind) {
        val created = service.newConversationForProject(project, kind)
        refreshChatPicker()
        switchTo(created)
    }

    /** Per-specialist model overrides for an orchestrator conversation - every non-"orchestrator" persona is always a candidate specialist, this only overrides which model it runs on. */
    private fun openSpecialistModelsDialog() {
        val specialists = service.personas().filter { it.id != "orchestrator" }
        val current = SpecialistModels.parse(conversation.specialistModels)
        val dialog = SpecialistModelsDialog(project, specialists, current)
        if (dialog.showAndGet()) {
            val json = SpecialistModels.write(dialog.result())
            conversation = service.store.setConversationSpecialistModels(conversation.id, json)
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
        refreshChatPicker()
        refreshPersonaCombo()
        // A brand-new conversation's model is always NULL (ConversationStore.createConversation)
        // - fall back to the app-wide default model (AppSettings.model, "fallback ... for
        // conversations without their own") the same way refreshHealthAndModels() already does
        // on plain startup. Without this, switching to (or creating) a chat with no model of
        // its own left the combo showing whatever the *previous* conversation's model happened
        // to be, or blank on the very first switch - either way onSend() would then send that
        // wrong or empty model string to the server. Observed live: an empty model produced
        // incoherent output (unrelated Rust/Node scaffolding as raw unparsed tool-call text).
        val resolvedModel = newConversation.model?.takeIf { it.isNotBlank() } ?: service.settings().model
        if (resolvedModel.isNotBlank()) {
            setModelComboText(resolvedModel)
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

    /** Scoped to this project's own conversations - see [SearchDialog]. */
    private fun openSearch() {
        val dialog = SearchDialog(project, service.store, project.basePath)
        if (!dialog.showAndGet()) return
        val result = dialog.selected ?: return
        if (result.conversationId() == conversation.id) return
        val target = service.conversationsForProject(project).firstOrNull { it.id == result.conversationId() }
        target?.let { switchTo(it) }
    }

    /** Mirrors desktop-java's per-conversation Export menu, backed by the same [ExportFormat]. */
    private fun exportConversation(markdown: Boolean) {
        val messages = service.store.getMessages(conversation.id)
        val content = if (markdown) ExportFormat.toMarkdown(conversation, messages, service.personas())
        else ExportFormat.toJson(conversation, messages)
        val extension = if (markdown) "md" else "json"
        val descriptor = FileSaverDescriptor(
            "Export Conversation", "Save this conversation as ${if (markdown) "Markdown" else "JSON"}", extension
        )
        val baseDir = conversation.workspacePath?.let { LocalFileSystem.getInstance().findFileByPath(it) }
        val wrapper = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
            .save(baseDir, "${ExportFormat.slugifyTitle(conversation.title ?: "conversation")}.$extension")
        val file = wrapper?.file ?: return
        runCatching { file.writeText(content) }
            .onFailure { e -> Messages.showErrorDialog(project, e.message ?: e.toString(), "Export Failed") }
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
        val statusRow = JPanel(BorderLayout())
        statusRow.add(statusLabel, BorderLayout.CENTER)
        statusRow.add(includeActiveFileCheckBox, BorderLayout.EAST)
        val south = JPanel(BorderLayout())
        south.add(statusRow, BorderLayout.NORTH)
        south.add(bottom, BorderLayout.CENTER)
        return south
    }

    private fun loadHistory() {
        val messages: List<ChatMessage> = service.store.getMessages(conversation.id)
        val isOrchestrator = conversation.kind == ConversationKind.ORCHESTRATOR
        for (m in messages) {
            val label = if (isOrchestrator && m.role == MessageRole.ASSISTANT) service.personaById(m.personaId)?.name else null
            addBubble(m.role, m.content, label)
        }
        scrollToBottom()
    }

    private fun addBubble(role: MessageRole, text: String, label: String? = null): JBTextArea {
        val area = JBTextArea(text).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            border = JBUI.Borders.empty(6, 8)
            background = if (role == MessageRole.USER) Color(0x1D, 0x4E, 0x89, 0x22) else background
        }
        val row = TightRowPanel(BorderLayout())
        row.border = JBUI.Borders.empty(2, 4)
        val labelText = label ?: if (role == MessageRole.USER) "You" else "Assistant"
        val labelComponent = JBLabel(labelText).apply {
            font = font.deriveFont(font.size2D - 1f)
            foreground = Color.GRAY
        }
        val wrapper = JPanel(BorderLayout())
        wrapper.add(labelComponent, BorderLayout.NORTH)
        wrapper.add(area, BorderLayout.CENTER)
        row.add(wrapper, BorderLayout.CENTER)
        messagesPanel.add(row)
        messagesPanel.revalidate()
        messagesPanel.repaint()
        return area
    }

    /** A plain, non-interactive progress line for orchestrator step transitions (planning/specialist/synthesizing). */
    private fun addStatusRow(text: String) {
        val label = JBLabel(text).apply {
            foreground = Color.GRAY
            font = font.deriveFont(font.size2D - 1f)
        }
        val row = TightRowPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(1, 12)
            add(label, BorderLayout.CENTER)
        }
        messagesPanel.add(row)
        messagesPanel.revalidate()
        messagesPanel.repaint()
    }

    private class ToolOpRow(val panel: JPanel, val label: JBLabel, val buttons: JPanel)

    /** One collapsible-free row per tool call: "running" adds it, "ok"/"error" updates it in place. */
    private fun addToolOpRow(): ToolOpRow {
        val label = JBLabel().apply {
            foreground = Color.GRAY
            font = font.deriveFont(font.size2D - 1f)
        }
        val buttons = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0))
        val row = TightRowPanel(BorderLayout()).apply {
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
        var text = input.text.trim()
        if (text.isEmpty()) return
        if (includeActiveFileCheckBox.isSelected) {
            activeFileContext(project)?.let { context -> text = "$context\n\n$text" }
        }
        input.text = ""
        sendButton.isEnabled = false

        val isOrchestrator = conversation.kind == ConversationKind.ORCHESTRATOR
        addBubble(MessageRole.USER, text)
        // A plain chat gets its answer bubble immediately (instant "typing" feedback); an
        // orchestrator turn creates it lazily on the first synthesis token (see the shared
        // onToken handler below), so planning/specialist rows render in their natural order
        // above it instead of above an empty bubble that was created too early.
        streamingArea = if (isOrchestrator) null else addBubble(MessageRole.ASSISTANT, "")
        scrollToBottom()

        val model = (modelCombo.editor.item as? String)?.trim().orEmpty()
        if (model.isNotEmpty() && model != conversation.model) {
            conversation = service.store.setConversationModel(conversation.id, model)
            refreshChatPicker()
        }
        val client = service.client()

        // Re-bound on every send (rather than once at panel creation) so the most recently
        // active project's tool window is the one whose dialogs the approval gate targets,
        // since the approver is a single application-wide field on both send paths.
        service.setActionApprover(DialogActionApprover(project))

        val listener = object : ChatService.Listener {
            override fun onToken(conversationId: String, messageId: String, delta: String) {
                onEdt {
                    if (streamingArea == null) streamingArea = addBubble(MessageRole.ASSISTANT, "")
                    streamingArea?.append(delta)
                    scrollToBottom()
                }
            }

            override fun onDone(conversationId: String, message: ChatMessage) {
                onEdt {
                    if (streamingArea == null) streamingArea = addBubble(MessageRole.ASSISTANT, "")
                    streamingArea?.text = message.content
                    streamingArea = null
                    sendButton.isEnabled = true
                    scrollToBottom()
                }
            }

            override fun onError(conversationId: String, messageId: String, code: ErrorCode, message: String) {
                onEdt {
                    if (streamingArea == null) streamingArea = addBubble(MessageRole.ASSISTANT, "")
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

            override fun onStep(conversationId: String, phase: String, personaId: String, label: String) {
                onEdt {
                    val icon = when (phase) {
                        "planning" -> "🧭"
                        "specialist" -> "🔬"
                        "synthesizing" -> "🔄"
                        "done" -> "✅"
                        else -> "•"
                    }
                    addStatusRow("$icon $label")
                    scrollToBottom()
                }
            }

            override fun onMessagesUpdated(conversationId: String) {
                onEdt {
                    val last = service.store.getMessages(conversation.id).lastOrNull() ?: return@onEdt
                    if (last.role == MessageRole.ASSISTANT) {
                        addBubble(MessageRole.ASSISTANT, last.content, service.personaById(last.personaId)?.name)
                        scrollToBottom()
                    }
                }
            }
        }

        if (isOrchestrator) {
            val specialistModels = SpecialistModels.parse(conversation.specialistModels)
            service.orchestratorService.send(
                client, conversation, text, model,
                service.settings().maxHistory, specialistModels, 0, listener
            )
        } else {
            val persona = personaCombo.selectedItem as? Persona ?: service.persona()
            service.chatService.send(
                client, conversation, text, persona,
                model, null, service.settings().maxHistory, 0, null, listener
            )
        }
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
                // Always resolve to *something* and go through setModelComboText - even when
                // nothing was known ahead of time (current blank, e.g. no default Model set in
                // Settings), addItem() above auto-selects its first entry through JComboBox's
                // own machinery, which bypasses setModelComboText - and its caret reset -
                // entirely. Read back whatever ended up selected and re-apply it explicitly so
                // the caret fix always runs, not just when a value was already known.
                val resolved = current.ifEmpty { (modelCombo.editor.item as? String)?.trim().orEmpty() }
                if (resolved.isNotEmpty()) {
                    setModelComboText(resolved)
                }
                service.lastHealthText = healthLabel.text
                service.lastModelText = resolved
                WindowManager.getInstance().getStatusBar(project)?.updateWidget(MultiAgentStatusBarWidgetFactory.ID)
            }
        }
    }

    /** Depth-first search for the actual editable text field inside a combo box editor - see [setModelComboText]. */
    private fun findTextComponent(component: java.awt.Component): javax.swing.text.JTextComponent? {
        if (component is javax.swing.text.JTextComponent) return component
        if (component is java.awt.Container) {
            for (child in component.components) {
                findTextComponent(child)?.let { return it }
            }
        }
        return null
    }

    /**
     * Sets the editable model combo's text and resets its caret to the start. Plain
     * `editor.item = text` leaves the caret wherever it last was (the end, for a fresh
     * editor), so a long model id shows its *tail* in the visible field instead of the
     * more legible prefix - e.g. "...GGUF-Q4_K_M" instead of "DeepSeek-Coder-V2-Lite...".
     *
     * Two earlier attempts here (a plain synchronous reset, then a `SwingUtilities.invokeLater`
     * re-assertion) both had **zero** observed effect in real-IDE testing, not partial effect -
     * which points at a wrong assumption rather than a timing race: `editor.editorComponent`
     * under IntelliJ's LaF is very likely a composite wrapper around the real text field, not
     * the field itself, so the earlier `as? JTextComponent` cast was silently failing and every
     * caret reset was a no-op. Search the component tree instead of assuming its shape, and set
     * a tooltip on the combo itself as a fallback that works regardless of whether the caret
     * trick ever lands - hovering always reveals the full id.
     */
    private fun setModelComboText(text: String) {
        modelCombo.editor.item = text
        modelCombo.toolTipText = text
        fun resetCaret() {
            findTextComponent(modelCombo.editor.editorComponent)?.let { field ->
                runCatching { field.caretPosition = 0 }
            }
        }
        resetCaret()
        SwingUtilities.invokeLater {
            resetCaret()
            SwingUtilities.invokeLater { resetCaret() }
        }
    }

    private fun onEdt(action: () -> Unit) {
        ApplicationManager.getApplication().invokeLater(action)
    }

    companion object {
        private val FILE_MUTATING_OPS = setOf("write_file", "delete_file", "rename_file")
    }
}
