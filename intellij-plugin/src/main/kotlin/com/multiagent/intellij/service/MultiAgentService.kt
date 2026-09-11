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
import java.nio.file.Files
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
 * desktop-java), but conversations are bound to a project by folder (Phase 2's "auto-bind
 * to the open Project"): every conversation created from a given project's tool window
 * gets that project's base path as its `workspacePath`, so ChatService routes it through
 * `ToolLoopRunner` automatically. A project can have several conversations (Phase 3); which
 * one is "active" per project is tracked here in memory only (resets to the most recently
 * updated one on IDE restart - same cost as desktop-java not remembering the last-open tab).
 */
@Service
class MultiAgentService : Disposable {

    private val baseDir: Path = Path.of(PathManager.getConfigPath(), "multiagent")

    val configService: ConfigService = ConfigService(baseDir.resolve("config.json"))
    val personaRegistry: PersonaRegistry = PersonaRegistry(baseDir.resolve("personas"))
    val store: ConversationStore = ConversationStore(baseDir.resolve("chats.db"))
    val chatService: ChatService = ChatService(store)
    val checkpointService: CheckpointService = CheckpointService(store)

    init {
        seedBuiltInPersonas()
    }

    /**
     * PersonaRegistry's own bundled-personas lookup can't work under the IntelliJ Platform:
     * its class's CodeSource is null under PluginClassLoader (verified empirically - see
     * build.gradle.kts), and the JVM's cwd under `runIde` is the unpacked IDE distribution's
     * own directory. So instead: the personas JSON files are bundled as classpath resources
     * (Gradle's processResources), and copied into PersonaRegistry's writable user directory once, the
     * first time each one is missing there - after that they're normal user-owned files (same
     * "the writable dir wins" override story as desktop-java, just seeded once instead of
     * pre-existing). A later repo-root persona update won't reach an existing install; that's
     * an acceptable, common tradeoff (e.g. how editors seed default settings/snippets once).
     */
    private fun seedBuiltInPersonas() {
        val dir = personaRegistry.userPersonaDir()
        for (id in BUILT_IN_PERSONA_IDS) {
            val target = dir.resolve("$id.json")
            if (Files.exists(target)) continue
            val resource = javaClass.getResourceAsStream("/personas/$id.json") ?: continue
            resource.use { input ->
                Files.createDirectories(dir)
                Files.copy(input, target)
            }
        }
    }

    @Volatile
    private var cachedClient: LlmClient? = null
    @Volatile
    private var cachedClientKey: String? = null

    /** Cheap, EDT-only cache of the last health/model check, for the status bar widget - see MultiAgentStatusBarWidget. */
    @Volatile
    var lastHealthText: String = "checking..."
    @Volatile
    var lastModelText: String = ""

    private val activeConversationId = ConcurrentHashMap<String, String>()

    private fun projectKey(project: Project): String = project.basePath ?: project.locationHash

    /** Every conversation bound to this project's folder, most recently updated first. */
    fun conversationsForProject(project: Project): List<Conversation> {
        val workspacePath = project.basePath
        return store.listConversations().filter { it.workspacePath == workspacePath }
    }

    /** The active conversation for this project - the one last selected, or the most recently updated, or a fresh one if none exist yet. */
    fun activeConversationForProject(project: Project): Conversation {
        val existing = conversationsForProject(project)
        val key = projectKey(project)
        activeConversationId[key]?.let { id -> existing.firstOrNull { it.id == id } }?.let { return it }
        val chosen = existing.firstOrNull() ?: store.createConversation(project.name, ConversationKind.CHAT, project.basePath)
        activeConversationId[key] = chosen.id
        return chosen
    }

    fun setActiveConversation(project: Project, conversation: Conversation) {
        activeConversationId[projectKey(project)] = conversation.id
    }

    fun newConversationForProject(project: Project): Conversation {
        val conversation = store.createConversation(null, ConversationKind.CHAT, project.basePath)
        setActiveConversation(project, conversation)
        return conversation
    }

    /** Deletes the conversation and returns the project's new active one (creating a fresh one if that was the last). */
    fun deleteConversation(project: Project, conversationId: String): Conversation {
        store.deleteConversation(conversationId)
        activeConversationId.remove(projectKey(project))
        return activeConversationForProject(project)
    }

    fun settings(): AppSettings = configService.ensureDefaultServer()

    /** Personas from `<config>/multiagent/personas/`; falls back to the built-in "General" persona when empty. */
    fun personas(): List<Persona> = personaRegistry.list()

    fun persona(): Persona = personas().first()

    fun personaById(id: String?): Persona? = id?.let { i -> personas().firstOrNull { it.id == i } }

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

    companion object {
        private val BUILT_IN_PERSONA_IDS = listOf("general", "researcher", "coder", "critic", "orchestrator")
    }
}
