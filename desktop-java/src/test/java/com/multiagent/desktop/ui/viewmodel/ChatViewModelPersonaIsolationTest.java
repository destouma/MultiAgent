package com.multiagent.desktop.ui.viewmodel;

import com.multiagent.desktop.model.Conversation;
import com.multiagent.desktop.model.Persona;
import com.multiagent.desktop.persistence.ConversationStore;
import com.multiagent.desktop.service.CheckpointService;
import com.multiagent.desktop.service.ChatService;
import com.multiagent.desktop.service.ConfigService;
import com.multiagent.desktop.service.OrchestratorService;
import com.multiagent.desktop.service.PersonaRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Persona must be pinned per conversation, not a single setting shared across every chat
 * (unlike the Electron app's chatStore.ts, which does keep it as one pane-wide field) -
 * this is a deliberate deviation requested for this client. Switching between two
 * conversations in the SAME ChatViewModel instance must show each one's own persona.
 */
class ChatViewModelPersonaIsolationTest {
    private ConversationStore store;
    private ChatService chatService;
    private ChatViewModel viewModel;
    private Persona researcher;
    private Persona coder;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        store = new ConversationStore(tempDir.resolve("chats.db"));
        PersonaRegistry personas = new PersonaRegistry();
        ConfigService configService = new ConfigService(tempDir.resolve("config.json"));
        chatService = new ChatService(store);
        OrchestratorService orchestratorService = new OrchestratorService(store, personas);
        CheckpointService checkpointService = new CheckpointService(store);

        viewModel = new ChatViewModel(store, personas, configService, chatService, orchestratorService, checkpointService);
        viewModel.bootstrap();

        researcher = personas.get("researcher").orElseThrow();
        coder = personas.get("coder").orElseThrow();
    }

    @AfterEach
    void tearDown() {
        chatService.shutdown();
        store.close();
    }

    @Test
    void settingThePersonaOnOneConversationDoesNotAffectAnother() {
        viewModel.newConversation();
        String chatAId = viewModel.activeConversationProperty().get().getId();
        viewModel.setPersona(researcher);

        viewModel.newConversation();
        String chatBId = viewModel.activeConversationProperty().get().getId();
        viewModel.setPersona(coder);

        // Look up the current object by id (like the TreeView does after a list refresh)
        // rather than reusing the reference captured before setPersona() replaced it -
        // otherwise this would be exercising a stale object, not real UI behavior.
        viewModel.selectConversation(findById(chatAId));
        assertEquals("researcher", viewModel.activePersonaProperty().get().getId());

        viewModel.selectConversation(findById(chatBId));
        assertEquals("coder", viewModel.activePersonaProperty().get().getId());
    }

    private Conversation findById(String id) {
        return viewModel.conversations().stream()
                .filter(c -> c.getId().equals(id))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void aFreshConversationDoesNotInheritThePreviousConversationsPersona() {
        viewModel.newConversation();
        viewModel.setPersona(researcher);

        viewModel.newConversation();
        Conversation fresh = viewModel.activeConversationProperty().get();

        // A brand new conversation falls back to the default persona, not whatever was
        // last selected elsewhere - that's the exact bug being guarded against here.
        assertNotEquals("researcher", fresh.getPersonaId());
    }

    @Test
    void personaChoiceSurvivesAConversationStoreReload() {
        viewModel.newConversation();
        Conversation chat = viewModel.activeConversationProperty().get();
        viewModel.setPersona(researcher);

        Conversation reloaded = store.getConversation(chat.getId());
        assertEquals("researcher", reloaded.getPersonaId());
    }
}
