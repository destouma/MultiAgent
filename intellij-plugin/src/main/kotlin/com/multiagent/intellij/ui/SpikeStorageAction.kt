package com.multiagent.intellij.ui

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.ui.Messages
import com.multiagent.intellij.core.model.ConversationKind
import com.multiagent.intellij.core.model.MessageRole
import com.multiagent.intellij.core.persistence.ConversationStore
import java.nio.file.Path

/**
 * Phase-0 risk spike (see TODO.md "Risks / spikes"): the one real unknown is whether
 * sqlite-jdbc's bundled native library extracts and loads under the plugin classloader +
 * the IDE sandbox. This opens the forked [ConversationStore] against a db under the plugin
 * config dir and round-trips one conversation + message. If the dialog shows the row back,
 * the spike passed and Phase 1 (persistence) is unblocked.
 */
class SpikeStorageAction : AnAction() {

    private val log = logger<SpikeStorageAction>()

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching {
                val dbPath: Path = Path.of(PathManager.getConfigPath(), "multiagent", "spike-chats.db")
                ConversationStore(dbPath).use { store ->
                    val convo = store.createConversation("storage spike", ConversationKind.CHAT, null)
                    store.addMessage(convo.id, MessageRole.USER, "hello from the plugin classloader", null)
                    val messages = store.getMessages(convo.id)
                    "OK — db at $dbPath\nconversation=${convo.id}\nmessages=${messages.size}: " +
                        messages.joinToString { "${it.role}:${it.content}" }
                }
            }
            ApplicationManager.getApplication().invokeLater {
                result.onSuccess {
                    log.info("storage spike: $it")
                    Messages.showInfoMessage(project, it, "MultiAgent Storage Spike")
                }.onFailure {
                    log.warn("storage spike failed", it)
                    Messages.showErrorDialog(project, "${it::class.simpleName}: ${it.message}", "MultiAgent Storage Spike Failed")
                }
            }
        }
    }
}
