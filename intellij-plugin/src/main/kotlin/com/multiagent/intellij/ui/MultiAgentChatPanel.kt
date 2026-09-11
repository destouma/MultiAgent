package com.multiagent.intellij.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollBar
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.multiagent.intellij.core.model.ChatMessage
import com.multiagent.intellij.core.model.MessageRole
import com.multiagent.intellij.core.service.ChatService
import com.multiagent.intellij.service.MultiAgentService
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.KeyEvent
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.SwingUtilities

/**
 * Phase 1: a single streaming conversation - message list + composer, no workspace tools yet
 * (Phase 2). Talks to [MultiAgentService] (the forked `core`) and marshals every
 * [ChatService.Listener] callback onto the EDT itself, since `ChatService.send` calls back
 * from its own background executor.
 */
class MultiAgentChatPanel(private val project: Project?) : JPanel(BorderLayout()) {

    private val service = ApplicationManager.getApplication().getService(MultiAgentService::class.java)

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
    private val modelCombo = JComboBox<String>().apply { isEditable = true }
    private val healthLabel = JBLabel("checking...")
    private val statusLabel = JBLabel(" ")

    private var streamingArea: JBTextArea? = null

    init {
        border = JBUI.Borders.empty(4)
        add(buildTopBar(), BorderLayout.NORTH)
        add(scrollPane, BorderLayout.CENTER)
        add(buildComposer(), BorderLayout.SOUTH)

        loadHistory()
        refreshHealthAndModels()
    }

    private fun buildTopBar(): JComponent {
        // Two fixed-height rows rather than one wrapping FlowLayout row: FlowLayout's
        // preferred-height calculation assumes a single line, so in a narrow docked tool
        // window a wrapped second line gets clipped instead of growing the bar.
        val top = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }

        val row1 = JPanel(FlowLayout(FlowLayout.LEFT, 6, 2))
        row1.add(JBLabel("Model:"))
        modelCombo.preferredSize = Dimension(180, modelCombo.preferredSize.height)
        row1.add(modelCombo)
        val refresh = JButton("↻").apply {
            toolTipText = "Refresh models / health"
            addActionListener { refreshHealthAndModels() }
        }
        row1.add(refresh)
        row1.maximumSize = Dimension(Int.MAX_VALUE, row1.preferredSize.height)
        top.add(row1)

        val row2 = JPanel(FlowLayout(FlowLayout.LEFT, 6, 2))
        row2.add(healthLabel)
        val settings = JButton("Settings...").apply {
            addActionListener {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, "MultiAgent")
            }
        }
        row2.add(settings)
        row2.maximumSize = Dimension(Int.MAX_VALUE, row2.preferredSize.height)
        top.add(row2)

        return top
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
        val messages: List<ChatMessage> = service.store.getMessages(service.conversation.id)
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

    private fun scrollToBottom() {
        SwingUtilities.invokeLater {
            val bar: JBScrollBar = scrollPane.verticalScrollBar as JBScrollBar
            bar.value = bar.maximum
        }
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
        val client = service.client()
        val persona = service.persona()

        service.chatService.send(
            client, service.conversation, text, persona,
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
            }
        }
    }

    private fun onEdt(action: () -> Unit) {
        ApplicationManager.getApplication().invokeLater(action)
    }
}
