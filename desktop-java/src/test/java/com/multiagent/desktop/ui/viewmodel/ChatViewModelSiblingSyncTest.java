package com.multiagent.desktop.ui.viewmodel;

import com.multiagent.desktop.model.Conversation;
import com.multiagent.desktop.model.FolderEntry;
import com.multiagent.desktop.model.ProjectEntry;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Split view (Phase 5) runs two ChatViewModel instances sharing one backend, mirroring
 * chatStore.ts's primary/secondary pair. Without the sibling-sync mechanism, a
 * conversation created in one pane would never appear in the other's list - these tests
 * lock in that it does, in both directions.
 */
class ChatViewModelSiblingSyncTest {
    private ConversationStore store;
    private ChatService chatService;
    private ChatViewModel primary;
    private ChatViewModel secondary;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        store = new ConversationStore(tempDir.resolve("chats.db"));
        PersonaRegistry personas = new PersonaRegistry();
        ConfigService configService = new ConfigService(tempDir.resolve("config.json"));
        chatService = new ChatService(store);
        OrchestratorService orchestratorService = new OrchestratorService(store, personas);
        CheckpointService checkpointService = new CheckpointService(store);

        primary = new ChatViewModel(store, personas, configService, chatService, orchestratorService, checkpointService);
        secondary = new ChatViewModel(store, personas, configService, chatService, orchestratorService, checkpointService);
        primary.addSibling(secondary);
        secondary.addSibling(primary);
        primary.bootstrap();
        secondary.bootstrap();
    }

    @AfterEach
    void tearDown() {
        chatService.shutdown();
        store.close();
    }

    @Test
    void aConversationCreatedInThePrimaryPaneAppearsInTheSecondarysList() {
        primary.newConversation();
        Conversation created = primary.activeConversationProperty().get();

        assertTrue(secondary.conversations().stream().anyMatch(c -> c.getId().equals(created.getId())));
    }

    @Test
    void aConversationCreatedInTheSecondaryPaneAppearsInThePrimarysList() {
        secondary.newConversation();
        Conversation created = secondary.activeConversationProperty().get();

        assertTrue(primary.conversations().stream().anyMatch(c -> c.getId().equals(created.getId())));
    }

    @Test
    void renamingInOnePaneIsReflectedInTheOthersList() {
        primary.newConversation();
        Conversation created = primary.activeConversationProperty().get();

        primary.renameConversation(created, "Renamed from primary");

        Conversation seenBySecondary = secondary.conversations().stream()
                .filter(c -> c.getId().equals(created.getId()))
                .findFirst()
                .orElseThrow();
        assertEquals("Renamed from primary", seenBySecondary.getTitle());
    }

    @Test
    void deletingInOnePaneRemovesItFromTheOthersList() {
        primary.newConversation();
        Conversation created = primary.activeConversationProperty().get();
        assertTrue(secondary.conversations().stream().anyMatch(c -> c.getId().equals(created.getId())));

        primary.deleteConversation(created);

        assertTrue(secondary.conversations().stream().noneMatch(c -> c.getId().equals(created.getId())));
    }

    @Test
    void openingAFolderInOnePaneAddsItToTheOthersFolderList(@TempDir Path folder) {
        secondary.openFolder(folder.toString());
        assertTrue(primary.folders().stream().anyMatch(f -> f.path().equals(folder.toString())));
    }

    @Test
    void addingAProjectInOnePaneAppearsInTheOthersProjectList() {
        secondary.addProject("Acme Corp");
        assertTrue(primary.projects().stream().anyMatch(p -> p.name().equals("Acme Corp")));
    }

    @Test
    void assigningAFolderToAProjectInOnePaneIsReflectedInTheOthersFolderList(@TempDir Path folder) {
        primary.openFolder(folder.toString());
        primary.addProject("Acme Corp");
        ProjectEntry project = secondary.projects().stream()
                .filter(p -> p.name().equals("Acme Corp"))
                .findFirst()
                .orElseThrow();

        secondary.assignFolderToProject(folder.toString(), project.id());

        FolderEntry seenByPrimary = primary.folders().stream()
                .filter(f -> f.path().equals(folder.toString()))
                .findFirst()
                .orElseThrow();
        assertEquals(project.id(), seenByPrimary.projectId());
    }
}
