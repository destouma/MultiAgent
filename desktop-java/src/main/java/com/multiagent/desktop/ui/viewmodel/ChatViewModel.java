package com.multiagent.desktop.ui.viewmodel;

import com.multiagent.desktop.llm.ErrorCode;
import com.multiagent.desktop.llm.LlmClient;
import com.multiagent.desktop.llm.LlmClientFactory;
import com.multiagent.desktop.llm.ProviderSettings;
import com.multiagent.desktop.model.AppSettings;
import com.multiagent.desktop.model.CheckpointDiff;
import com.multiagent.desktop.model.ChatMessage;
import com.multiagent.desktop.model.Conversation;
import com.multiagent.desktop.model.ConversationKind;
import com.multiagent.desktop.model.FolderEntry;
import com.multiagent.desktop.model.HealthStatus;
import com.multiagent.desktop.model.ModelInfo;
import com.multiagent.desktop.model.Persona;
import com.multiagent.desktop.model.SearchResult;
import com.multiagent.desktop.model.ServerProfile;
import com.multiagent.desktop.persistence.ConversationStore;
import com.multiagent.desktop.service.CheckpointService;
import com.multiagent.desktop.service.ChatService;
import com.multiagent.desktop.service.ConfigService;
import com.multiagent.desktop.service.ExportFormat;
import com.multiagent.desktop.service.OrchestratorService;
import com.multiagent.desktop.service.PersonaRegistry;

import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MVVM state for the main window - the Java analogue of desktop/src/store/chatStore.ts +
 * settingsStore.ts. Only one conversation is shown at a time (no split view yet), but each
 * conversation is still a fully separate, isolated entity: its own server/model pin
 * (Conversation.serverId/model - no single "current client"/"current model" field), and
 * its own live generation state (ConversationSession: streaming flag, partial token
 * buffer, last error, tool-activity log). Switching the active conversation never resets
 * another conversation's in-flight state - it swaps which session's state the exposed
 * properties currently mirror, restoring exactly where that chat was left (including
 * still-in-progress streaming text) rather than a blank/shared default.
 */
public class ChatViewModel {
    private final ConversationStore store;
    private final PersonaRegistry personaRegistry;
    private final ConfigService configService;
    private final ChatService chatService;
    private final OrchestratorService orchestratorService;
    private final CheckpointService checkpointService;

    private final Map<String, LlmClient> clientsByServerId = new ConcurrentHashMap<>();

    /** Per-conversation live state, so two chats generating at once never share/clobber each other's UI state. */
    private static final class ConversationSession {
        final StringBuilder streamingBuffer = new StringBuilder();
        final List<WorkspaceOpEntry> workspaceOps = new ArrayList<>();
        boolean streaming = false;
        String errorMessage = "";
        String orchestratorStatus = "";
    }

    private final Map<String, ConversationSession> sessions = new ConcurrentHashMap<>();

    /** All session reads/writes happen on the FX Application Thread (every caller wraps in Platform.runLater), so no external synchronization is needed here. */
    private ConversationSession sessionFor(String conversationId) {
        return sessions.computeIfAbsent(conversationId, id -> new ConversationSession());
    }

    private final ObservableList<Conversation> conversations = FXCollections.observableArrayList();
    private final ObservableList<ChatMessage> messages = FXCollections.observableArrayList();
    private final ObservableList<Persona> personas = FXCollections.observableArrayList();
    private final ObservableList<ModelInfo> models = FXCollections.observableArrayList();
    private final ObservableList<FolderEntry> folders = FXCollections.observableArrayList();
    private final ObservableList<WorkspaceOpEntry> workspaceOps = FXCollections.observableArrayList();

    private final ObjectProperty<Conversation> activeConversation = new SimpleObjectProperty<>();
    private final ObjectProperty<Persona> activePersona = new SimpleObjectProperty<>();
    private final ObjectProperty<ServerProfile> activeServer = new SimpleObjectProperty<>();
    private final StringProperty activeModel = new SimpleStringProperty("");
    private final StringProperty streamingContent = new SimpleStringProperty("");
    private final BooleanProperty streaming = new SimpleBooleanProperty(false);
    private final StringProperty errorMessage = new SimpleStringProperty("");
    private final StringProperty orchestratorStatus = new SimpleStringProperty("");
    private final ObjectProperty<HealthStatus> health = new SimpleObjectProperty<>();

    /**
     * ChatService/OrchestratorService/CheckpointService are shared across ALL ChatViewModel
     * instances (one per split-view pane) rather than created per-instance - they're keyed
     * internally by conversation id, so sharing them is what lets two panes generate
     * concurrently without each pane spinning up its own executor/thread pool.
     */
    public ChatViewModel(ConversationStore store, PersonaRegistry personaRegistry, ConfigService configService,
                          ChatService chatService, OrchestratorService orchestratorService,
                          CheckpointService checkpointService) {
        this.store = store;
        this.personaRegistry = personaRegistry;
        this.configService = configService;
        this.chatService = chatService;
        this.orchestratorService = orchestratorService;
        this.checkpointService = checkpointService;
        configService.ensureDefaultServer();
    }

    public void bootstrap() {
        personas.setAll(personaRegistry.list());
        refreshConversationsAndFolders();
        if (!conversations.isEmpty()) {
            selectConversation(conversations.get(0));
        }
    }

    // --- Split-view sibling sync -------------------------------------------------------
    // Mirrors chatStore.ts's small in-module "sibling list": useChatStore (primary) and
    // useSecondaryChatStore (split-view pane) are two separate instances sharing the same
    // backend, so a create/delete/rename/folder change in either pane must be pushed into
    // the other immediately - otherwise the second pane's mutations would only surface in
    // the sidebar (bound to the primary instance) on its own next unrelated action.

    private final List<ChatViewModel> siblings = new ArrayList<>();

    /** Call once, symmetrically, after constructing both panes' ChatViewModel instances. */
    public void addSibling(ChatViewModel other) {
        if (!siblings.contains(other)) {
            siblings.add(other);
        }
    }

    private void refreshConversationsAndFolders() {
        conversations.setAll(store.listConversations());
        folders.setAll(store.listFolders());
    }

    private void notifySiblings() {
        for (ChatViewModel sibling : siblings) {
            sibling.refreshConversationsAndFolders();
            Conversation siblingActive = sibling.activeConversation.get();
            if (siblingActive != null) {
                Conversation refreshed = store.getConversation(siblingActive.getId());
                if (refreshed != null) {
                    sibling.activeConversation.set(refreshed);
                }
            }
        }
    }

    /** Registers a folder (via "Open folder") so it can be picked when creating a workspace-bound chat. */
    public void openFolder(String path) {
        store.addFolder(path);
        folders.setAll(store.listFolders());
        notifySiblings();
    }

    /** Unregisters a folder - conversations bound to it fall back to "No folder" (their workspacePath is cleared), not deleted. */
    public void removeFolder(String path) {
        store.removeFolder(path);
        folders.setAll(store.listFolders());
        conversations.setAll(store.listConversations());
        Conversation active = activeConversation.get();
        if (active != null) {
            Conversation refreshed = store.getConversation(active.getId());
            if (refreshed != null) {
                activeConversation.set(refreshed);
            }
        }
        notifySiblings();
    }

    public void newConversation() {
        newConversationInFolder(null);
    }

    public void newConversationInFolder(String workspacePath) {
        createAndSelect(ConversationKind.CHAT, workspacePath);
    }

    public void newOrchestratorConversation() {
        newOrchestratorConversationInFolder(null);
    }

    public void newOrchestratorConversationInFolder(String workspacePath) {
        createAndSelect(ConversationKind.ORCHESTRATOR, workspacePath);
    }

    private void createAndSelect(ConversationKind kind, String workspacePath) {
        Conversation created = store.createConversation(null, kind, workspacePath);
        conversations.add(0, created);
        selectConversation(created);
        notifySiblings();
    }

    /** Resolves which saved server a conversation uses: its own pin, or the app's active connection. */
    private ServerProfile resolveServerFor(Conversation conversation) {
        AppSettings settings = configService.getSettings();
        String serverId = conversation != null ? conversation.getServerId() : null;
        if (serverId != null) {
            Optional<ServerProfile> pinned = settings.getServers().stream()
                    .filter(server -> server.getId().equals(serverId))
                    .findFirst();
            if (pinned.isPresent()) {
                return pinned.get();
            }
        }
        String activeId = settings.getActiveServerId();
        return settings.getServers().stream()
                .filter(server -> server.getId().equals(activeId))
                .findFirst()
                .orElseGet(() -> settings.getServers().get(0));
    }

    private String resolveModelFor(Conversation conversation) {
        if (conversation != null && conversation.getModel() != null && !conversation.getModel().isBlank()) {
            return conversation.getModel();
        }
        return configService.getSettings().getModel();
    }

    /** Resolves which persona a conversation uses: its own pin, or the first available persona ("general" by sort order). */
    private Persona resolvePersonaFor(Conversation conversation) {
        String personaId = conversation != null ? conversation.getPersonaId() : null;
        if (personaId != null) {
            Optional<Persona> pinned = personaRegistry.get(personaId);
            if (pinned.isPresent()) {
                return pinned.get();
            }
        }
        return !personas.isEmpty() ? personas.get(0) : null;
    }

    private LlmClient clientFor(ServerProfile profile) {
        return clientsByServerId.computeIfAbsent(profile.getId(), id -> LlmClientFactory.create(
                profile.getProviderType(), new ProviderSettings(profile.getBaseUrl(), profile.getApiKey())));
    }

    /** Call after Settings dialog saves changes - server profiles may have new URLs/keys, so drop every cached client. */
    public void applySettingsChange() {
        clientsByServerId.clear();
        refreshForActiveConversation();
    }

    private void refreshForActiveConversation() {
        Conversation conversation = activeConversation.get();
        ServerProfile profile = resolveServerFor(conversation);
        activeServer.set(profile);
        activeModel.set(resolveModelFor(conversation));
        activePersona.set(resolvePersonaFor(conversation));
        refreshModels(profile);
        refreshHealth(profile);
    }

    public void refreshModels() {
        refreshModels(resolveServerFor(activeConversation.get()));
    }

    private void refreshModels(ServerProfile profile) {
        LlmClient client = clientFor(profile);
        runAsync(() -> {
            try {
                var list = client.listModels();
                Platform.runLater(() -> {
                    if (activeServer.get() == profile) {
                        models.setAll(list);
                    }
                });
            } catch (Exception e) {
                Platform.runLater(() -> errorMessage.set(e.getMessage()));
            }
        });
    }

    public void refreshHealth() {
        refreshHealth(resolveServerFor(activeConversation.get()));
    }

    private void refreshHealth(ServerProfile profile) {
        LlmClient client = clientFor(profile);
        runAsync(() -> {
            HealthStatus status = client.checkHealth();
            Platform.runLater(() -> {
                if (activeServer.get() == profile) {
                    health.set(status);
                }
            });
        });
    }

    public void selectConversation(Conversation conversation) {
        activeConversation.set(conversation);
        messages.setAll(store.getMessages(conversation.getId()));

        // Restore THIS conversation's own session state rather than inheriting or blanking
        // whatever the previously viewed chat had - a chat that's still generating shows
        // Stop and its accumulated partial text again; one that isn't stays fully idle/usable,
        // regardless of what any other chat is doing right now.
        ConversationSession session = sessionFor(conversation.getId());
        errorMessage.set(session.errorMessage);
        workspaceOps.setAll(session.workspaceOps);
        streaming.set(session.streaming);
        streamingContent.set(session.streaming ? session.streamingBuffer.toString() : "");
        orchestratorStatus.set(session.orchestratorStatus);

        refreshForActiveConversation();
    }

    public void deleteConversation(Conversation conversation) {
        store.deleteConversation(conversation.getId());
        sessions.remove(conversation.getId());
        conversations.remove(conversation);
        if (conversation.equals(activeConversation.get())) {
            if (!conversations.isEmpty()) {
                selectConversation(conversations.get(0));
            } else {
                activeConversation.set(null);
                messages.clear();
                errorMessage.set("");
                workspaceOps.clear();
                streaming.set(false);
                streamingContent.set("");
                orchestratorStatus.set("");
                refreshForActiveConversation();
            }
        }
        notifySiblings();
    }

    public void renameConversation(Conversation conversation, String title) {
        Conversation updated = store.renameConversation(conversation.getId(), title);
        conversations.setAll(store.listConversations());
        if (updated != null && updated.equals(activeConversation.get())) {
            activeConversation.set(updated);
        }
        notifySiblings();
    }

    public List<SearchResult> search(String term) {
        return store.search(term);
    }

    /** Used by SearchDialog: a search result may point at a conversation not currently loaded in-memory. */
    public void selectConversationById(String conversationId) {
        Conversation match = conversations.stream()
                .filter(c -> c.getId().equals(conversationId))
                .findFirst()
                .orElseGet(() -> store.getConversation(conversationId));
        if (match != null) {
            selectConversation(match);
        }
    }

    public String exportConversation(Conversation conversation, String format) {
        List<ChatMessage> conversationMessages = store.getMessages(conversation.getId());
        return "json".equals(format)
                ? ExportFormat.toJson(conversation, conversationMessages)
                : ExportFormat.toMarkdown(conversation, conversationMessages, personas);
    }

    public CheckpointDiff diffCheckpoint(String checkpointId) {
        return checkpointService.diff(checkpointId);
    }

    public void revertCheckpoint(String checkpointId) {
        checkpointService.revert(checkpointId);
    }

    /** Pins the ACTIVE conversation (and only that one) to this persona - other conversations are untouched. */
    public void setPersona(Persona persona) {
        Conversation conversation = activeConversation.get();
        if (conversation == null) {
            return;
        }
        Conversation updated = store.setConversationPersona(conversation.getId(),
                persona == null ? null : persona.getId());
        activeConversation.set(updated);
        activePersona.set(persona);
        int index = conversations.indexOf(conversation);
        if (index >= 0) {
            conversations.set(index, updated);
        }
        notifySiblings();
    }

    /** Pins the ACTIVE conversation (and only that one) to this model - other conversations are untouched. */
    public void setModel(String model) {
        Conversation conversation = activeConversation.get();
        if (conversation == null) {
            return;
        }
        Conversation updated = store.setConversationModel(conversation.getId(), model);
        activeConversation.set(updated);
        activeModel.set(model);
        int index = conversations.indexOf(conversation);
        if (index >= 0) {
            conversations.set(index, updated);
        }
        notifySiblings();
    }

    /** Pins the ACTIVE conversation (and only that one) to this server - other conversations are untouched. */
    public void setServer(ServerProfile profile) {
        Conversation conversation = activeConversation.get();
        if (conversation == null) {
            return;
        }
        Conversation updated = store.setConversationServer(conversation.getId(), profile == null ? null : profile.getId());
        activeConversation.set(updated);
        int index = conversations.indexOf(conversation);
        if (index >= 0) {
            conversations.set(index, updated);
        }
        refreshForActiveConversation();
        notifySiblings();
    }

    /**
     * Returns false when nothing was actually dispatched (blank text, or no model chosen) -
     * the composer uses this to decide whether it's safe to clear the input, so a failed
     * send never wipes what the user typed.
     */
    public boolean sendMessage(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        if (activeConversation.get() == null) {
            newConversation();
        }
        // Resolved fresh from this specific conversation's own persisted server/model/
        // persona, never from a shared mutable field, so a send always targets exactly the
        // conversation the composer belongs to - even if another conversation gets selected
        // before this one finishes streaming.
        Conversation conversation = activeConversation.get();
        ServerProfile profile = resolveServerFor(conversation);
        LlmClient client = clientFor(profile);
        String model = resolveModelFor(conversation);
        Persona persona = resolvePersonaFor(conversation);

        if (model == null || model.isBlank()) {
            sessionFor(conversation.getId()).errorMessage = "Select a model first.";
            errorMessage.set("Select a model first.");
            return false;
        }

        String conversationId = conversation.getId();
        ConversationSession session = sessionFor(conversationId);
        session.errorMessage = "";
        session.streamingBuffer.setLength(0);
        session.workspaceOps.clear();
        session.streaming = true;
        session.orchestratorStatus = "";
        // This send is always for the currently active conversation (see the resolution
        // comment above), so it's safe to mirror straight into the visible properties too.
        errorMessage.set("");
        streamingContent.set("");
        workspaceOps.clear();
        streaming.set(true);
        orchestratorStatus.set("");

        ChatService.Listener listener = new ChatService.Listener() {
            // Every callback writes into THIS conversation's own session first - that's
            // what makes two chats generating at once fully isolated - and only mirrors
            // into the visible properties when this conversation is still the one on
            // screen, so a reply finishing for chat A while chat B is shown can't
            // clobber chat B's state.
            @Override
            public void onToken(String conversationId, String messageId, String delta) {
                Platform.runLater(() -> {
                    ConversationSession session = sessionFor(conversationId);
                    session.streamingBuffer.append(delta);
                    if (isActiveConversation(conversationId)) {
                        streamingContent.set(session.streamingBuffer.toString());
                    }
                });
            }

            @Override
            public void onDone(String conversationId, ChatMessage message) {
                Platform.runLater(() -> {
                    ConversationSession session = sessionFor(conversationId);
                    session.streaming = false;
                    session.streamingBuffer.setLength(0);
                    if (isActiveConversation(conversationId)) {
                        streaming.set(false);
                        streamingContent.set("");
                        refreshMessages(conversationId);
                    }
                    touchConversationOrder(conversationId);
                });
            }

            @Override
            public void onError(String conversationId, String messageId, ErrorCode code, String message) {
                Platform.runLater(() -> {
                    ConversationSession session = sessionFor(conversationId);
                    session.streaming = false;
                    session.streamingBuffer.setLength(0);
                    session.errorMessage = message;
                    if (isActiveConversation(conversationId)) {
                        streaming.set(false);
                        streamingContent.set("");
                        errorMessage.set(message);
                        refreshMessages(conversationId);
                    }
                });
            }

            @Override
            public void onWorkspaceOp(String conversationId, String messageId, String op, String path,
                                       String status, String detail, String checkpointId) {
                Platform.runLater(() -> {
                    ConversationSession session = sessionFor(conversationId);
                    WorkspaceOpEntry entry = new WorkspaceOpEntry(op, path, status, detail, checkpointId);
                    session.workspaceOps.add(entry);
                    if (isActiveConversation(conversationId)) {
                        workspaceOps.add(entry);
                    }
                });
            }

            @Override
            public void onStep(String conversationId, String phase, String personaId, String label) {
                Platform.runLater(() -> {
                    ConversationSession session = sessionFor(conversationId);
                    session.orchestratorStatus = label;
                    if (isActiveConversation(conversationId)) {
                        orchestratorStatus.set(label);
                    }
                });
            }

            @Override
            public void onMessagesUpdated(String conversationId) {
                Platform.runLater(() -> refreshMessages(conversationId));
            }
        };

        if (conversation.getKind() == ConversationKind.ORCHESTRATOR) {
            orchestratorService.send(client, conversation, text, model, profile.getMaxHistory(), listener);
        } else {
            chatService.send(client, conversation, text, persona, model, profile.getMaxHistory(), listener);
        }

        // The user message is persisted synchronously inside ChatService/OrchestratorService's
        // send() before it returns (only the model call itself runs on a background thread),
        // so it's safe to reflect it immediately rather than waiting for a token/done callback.
        refreshMessages(conversation.getId());
        return true;
    }

    public void cancelStreaming() {
        Conversation conversation = activeConversation.get();
        if (conversation != null) {
            chatService.cancel(conversation.getId());
            orchestratorService.cancel(conversation.getId());
        }
    }

    private boolean isActiveConversation(String conversationId) {
        Conversation active = activeConversation.get();
        return active != null && active.getId().equals(conversationId);
    }

    private void refreshMessages(String conversationId) {
        if (isActiveConversation(conversationId)) {
            messages.setAll(store.getMessages(conversationId));
        }
    }

    private void touchConversationOrder(String conversationId) {
        conversations.setAll(store.listConversations());
        Conversation updated = store.getConversation(conversationId);
        if (updated != null && updated.getId().equals(activeConversation.get() != null ? activeConversation.get().getId() : null)) {
            activeConversation.set(updated);
        }
        notifySiblings();
    }

    private void runAsync(Runnable runnable) {
        Thread thread = new Thread(runnable, "chat-viewmodel-worker");
        thread.setDaemon(true);
        thread.start();
    }

    public ObservableList<Conversation> conversations() {
        return conversations;
    }

    public ObservableList<ChatMessage> messages() {
        return messages;
    }

    public ObservableList<Persona> personas() {
        return personas;
    }

    public ObservableList<ModelInfo> models() {
        return models;
    }

    public ObservableList<FolderEntry> folders() {
        return folders;
    }

    public ObservableList<WorkspaceOpEntry> workspaceOps() {
        return workspaceOps;
    }

    public ObjectProperty<Conversation> activeConversationProperty() {
        return activeConversation;
    }

    public ObjectProperty<Persona> activePersonaProperty() {
        return activePersona;
    }

    public ObjectProperty<ServerProfile> activeServerProperty() {
        return activeServer;
    }

    public StringProperty activeModelProperty() {
        return activeModel;
    }

    public StringProperty streamingContentProperty() {
        return streamingContent;
    }

    public BooleanProperty streamingProperty() {
        return streaming;
    }

    public StringProperty errorMessageProperty() {
        return errorMessage;
    }

    public StringProperty orchestratorStatusProperty() {
        return orchestratorStatus;
    }

    public ObjectProperty<HealthStatus> healthProperty() {
        return health;
    }

    public ConfigService configService() {
        return configService;
    }

    public void shutdown() {
        chatService.shutdown();
        orchestratorService.shutdown();
    }
}
