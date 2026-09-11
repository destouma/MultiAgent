package com.multiagent.intellij.service

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.multiagent.intellij.core.llm.LlmClient
import com.multiagent.intellij.core.llm.LlmClientFactory
import com.multiagent.intellij.core.llm.ProviderSettings
import com.multiagent.intellij.core.model.AppSettings
import com.multiagent.intellij.core.model.Conversation
import com.multiagent.intellij.core.model.ConversationKind
import com.multiagent.intellij.core.model.Persona
import com.multiagent.intellij.core.persistence.ConversationStore
import com.multiagent.intellij.core.service.ChatService
import com.multiagent.intellij.core.service.CheckpointService
import com.multiagent.intellij.core.service.ConfigService
import com.multiagent.intellij.core.service.PersonaRegistry
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Application-level owner of the forked core services - this plugin's analogue of
 * desktop-java's `ChatViewModel`, minus the JavaFX `Observable*` plumbing (plain fields;
 * callers marshal `ChatService.Listener` callbacks onto the EDT themselves - see
 * `MultiAgentChatPanel`).
 *
 * Everything lives under `PathManager.getConfigPath()/multiagent/` - a sibling of
 * desktop-java's `%APPDATA%/MultiAgentJava/`, not the same directory (the two clients are
 * independent forks; sharing a config/db file would couple them right back together).
 *
 * Config/personas/db stay application-wide (one server, one persona set, one db - same as
 * desktop-java), but conversations are bound one-per-project (Phase 2's "auto-bind to the
 * open Project"): each project's tool window gets its own conversation, keyed by the
 * project's base path, with that path as the conversation's `workspacePath` so ChatService
 * routes it through `ToolLoopRunner` automatically.
 */
@Service
class MultiAgentService : Disposable {

    private val baseDir: Path = Path.of(PathManager.getConfigPath(), "multiagent")

    val configService: ConfigService = ConfigService(baseDir.resolve("config.json"))
    val personaRegistry: PersonaRegistry = PersonaRegistry(baseDir.resolve("personas"))
    val store: ConversationStore = ConversationStore(baseDir.resolve("chats.db"))
    val chatService: ChatService = ChatService(store)
    val checkpointService: CheckpointService = CheckpointService(store)

    @Volatile
    private var cachedClient: LlmClient? = null
    @Volatile
    private var cachedClientKey: String? = null

    private val conversationsByProjectKey = ConcurrentHashMap<String, Conversation>()

    /** Finds or creates the one conversation bound to this project's folder. */
    fun conversationForProject(project: Project): Conversation {
        val key = project.basePath ?: project.locationHash
        conversationsByProjectKey[key]?.let { return it }
        val workspacePath = project.basePath
        val conversation = store.listConversations().firstOrNull { it.workspacePath == workspacePath }
            ?: store.createConversation(project.name, ConversationKind.CHAT, workspacePath)
        conversationsByProjectKey[key] = conversation
        return conversation
    }

    fun settings(): AppSettings = configService.ensureDefaultServer()

    /** Personas from `<config>/multiagent/personas/`; falls back to the built-in "General" persona when empty. */
    fun persona(): Persona = personaRegistry.list().first()

    /** Cached per (providerType, baseUrl, apiKey); rebuilt only when those actually change. */
    fun client(): LlmClient {
        val s = settings()
        val key = "${s.providerType}|${s.baseUrl}|${s.apiKey}"
        cachedClient?.let { if (cachedClientKey == key) return it }
        val created = LlmClientFactory.create(s.providerType, ProviderSettings(s.baseUrl, s.apiKey))
        cachedClient = created
        cachedClientKey = key
        return created
    }

    /** Call after a Settings change so the next send()/health-check picks up the new server. */
    fun invalidateClient() {
        cachedClient = null
        cachedClientKey = null
    }

    override fun dispose() {
        chatService.shutdown()
        store.close()
    }
}
