package com.multiagent.desktop.persistence;

import com.multiagent.desktop.model.ChatMessage;
import com.multiagent.desktop.model.Conversation;
import com.multiagent.desktop.model.ConversationKind;
import com.multiagent.desktop.model.FolderEntry;
import com.multiagent.desktop.model.MessageRole;
import com.multiagent.desktop.model.ProjectEntry;
import com.multiagent.desktop.model.SearchResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversationStoreTest {
    private ConversationStore store;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        store = new ConversationStore(tempDir.resolve("chats.db"));
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    @Test
    void createConversationDefaultsToChatKindAndDefaultTitle() {
        Conversation conversation = store.createConversation();
        assertEquals(ConversationKind.CHAT, conversation.getKind());
        assertEquals("New chat", conversation.getTitle());
        assertNull(conversation.getModel());
        assertNull(conversation.getServerId());
    }

    @Test
    void addingFirstUserMessageAutoTitlesFromItsContent() {
        Conversation conversation = store.createConversation();
        store.addMessage(conversation.getId(), MessageRole.USER, "Explain how SSE streaming works", "general");

        Conversation reloaded = store.getConversation(conversation.getId());
        assertEquals("Explain how SSE streaming works", reloaded.getTitle());
    }

    @Test
    void autoTitleDoesNotOverwriteAnAlreadyCustomTitle() {
        Conversation conversation = store.createConversation("My custom title", ConversationKind.CHAT, null);
        store.addMessage(conversation.getId(), MessageRole.USER, "hello", "general");

        Conversation reloaded = store.getConversation(conversation.getId());
        assertEquals("My custom title", reloaded.getTitle());
    }

    @Test
    void autoTitleIsTruncatedTo48CharsAndFallsBackWhenBlank() {
        Conversation conversation = store.createConversation();
        String longContent = "x".repeat(80);
        store.addMessage(conversation.getId(), MessageRole.USER, longContent, "general");
        assertEquals(48, store.getConversation(conversation.getId()).getTitle().length());

        Conversation blankConversation = store.createConversation();
        store.addMessage(blankConversation.getId(), MessageRole.USER, "   ", "general");
        assertEquals("New chat", store.getConversation(blankConversation.getId()).getTitle());
    }

    @Test
    void setConversationModelAndServerPersistIndependently() {
        Conversation a = store.createConversation();
        Conversation b = store.createConversation();

        store.setConversationModel(a.getId(), "model-a");
        store.setConversationServer(a.getId(), "server-a");

        assertEquals("model-a", store.getConversation(a.getId()).getModel());
        assertEquals("server-a", store.getConversation(a.getId()).getServerId());
        // b must be untouched - this is exactly the "two chats, two servers" isolation guarantee.
        assertNull(store.getConversation(b.getId()).getModel());
        assertNull(store.getConversation(b.getId()).getServerId());
    }

    @Test
    void setConversationVisionModelPersistsAndClearsPerConversation() {
        Conversation a = store.createConversation();
        Conversation b = store.createConversation();

        assertNull(store.getConversation(a.getId()).getVisionModel());
        store.setConversationVisionModel(a.getId(), "Qwen2.5-VL-3B");
        assertEquals("Qwen2.5-VL-3B", store.getConversation(a.getId()).getVisionModel());
        assertNull(store.getConversation(b.getId()).getVisionModel());

        store.setConversationVisionModel(a.getId(), null);
        assertNull(store.getConversation(a.getId()).getVisionModel());
    }

    @Test
    void setConversationSpecialistModelsRoundTripsAndClears() {
        Conversation a = store.createConversation();
        Conversation b = store.createConversation();

        store.setConversationSpecialistModels(a.getId(), "{\"coder\":\"code-model\"}");
        assertEquals("{\"coder\":\"code-model\"}", store.getConversation(a.getId()).getSpecialistModels());
        assertNull(store.getConversation(b.getId()).getSpecialistModels());

        store.setConversationSpecialistModels(a.getId(), "");
        assertEquals("", store.getConversation(a.getId()).getSpecialistModels());
    }

    @Test
    void setConversationOrchestratorApplyRoundTripsAndClears() {
        Conversation a = store.createConversation();
        Conversation b = store.createConversation();

        store.setConversationOrchestratorApply(a.getId(), "1");
        assertEquals("1", store.getConversation(a.getId()).getOrchestratorApply());
        assertTrue(store.getConversation(a.getId()).isOrchestratorApply());
        assertFalse(store.getConversation(b.getId()).isOrchestratorApply());

        store.setConversationOrchestratorApply(a.getId(), null);
        assertNull(store.getConversation(a.getId()).getOrchestratorApply());
        assertFalse(store.getConversation(a.getId()).isOrchestratorApply());
    }

    @Test
    void setConversationPersonaPersistsIndependentlyPerConversation() {
        Conversation a = store.createConversation();
        Conversation b = store.createConversation();

        store.setConversationPersona(a.getId(), "researcher");

        assertEquals("researcher", store.getConversation(a.getId()).getPersonaId());
        // b must be untouched - persona is a per-chat pin, not a shared/global setting.
        assertNull(store.getConversation(b.getId()).getPersonaId());
    }

    @Test
    void deleteMessagesFromTruncatesByInsertionOrderIncludingSameTimestampMessages() {
        Conversation conversation = store.createConversation();
        ChatMessage m1 = store.addMessage(conversation.getId(), MessageRole.USER, "one", "general");
        ChatMessage m2 = store.addMessage(conversation.getId(), MessageRole.ASSISTANT, "two", "general");
        ChatMessage m3 = store.addMessage(conversation.getId(), MessageRole.USER, "three", "general");

        boolean deleted = store.deleteMessagesFrom(conversation.getId(), m2.getId());

        assertTrue(deleted);
        List<ChatMessage> remaining = store.getMessages(conversation.getId());
        assertEquals(1, remaining.size());
        assertEquals(m1.getId(), remaining.get(0).getId());
    }

    @Test
    void deletingAConversationRemovesItsMessagesToo() {
        Conversation conversation = store.createConversation();
        store.addMessage(conversation.getId(), MessageRole.USER, "hello", "general");

        boolean existed = store.deleteConversation(conversation.getId());

        assertTrue(existed);
        assertNull(store.getConversation(conversation.getId()));
        assertTrue(store.getMessages(conversation.getId()).isEmpty());
    }

    @Test
    void reopeningTheSameDatabaseFileIsIdempotentAndKeepsData(@TempDir Path tempDir) {
        Path dbPath = tempDir.resolve("reopen.db");
        Conversation conversation;
        try (ConversationStore first = new ConversationStore(dbPath)) {
            conversation = first.createConversation();
        }

        try (ConversationStore second = new ConversationStore(dbPath)) {
            Conversation reloaded = second.getConversation(conversation.getId());
            assertNotNull(reloaded);
            assertEquals(conversation.getTitle(), reloaded.getTitle());
        }
    }

    @Test
    void removingAFolderUnbindsItsConversationsWithoutDeletingThem() {
        String folderPath = "C:\\some\\project";
        store.addFolder(folderPath);
        Conversation bound = store.createConversation("bound chat", ConversationKind.CHAT, folderPath);

        boolean removed = store.removeFolder(folderPath);

        assertTrue(removed);
        assertTrue(store.listFolders().stream().noneMatch(f -> f.path().equals(folderPath)));
        Conversation reloaded = store.getConversation(bound.getId());
        assertNotNull(reloaded);
        assertNull(reloaded.getWorkspacePath());
    }

    @Test
    void removingAFolderThatWasNeverAddedReturnsFalse() {
        assertFalse(store.removeFolder("never-added"));
    }

    @Test
    void addFolderIsIdempotentAndFoldersAreOrderedByAddedTime() throws InterruptedException {
        FolderEntry first = store.addFolder("a");
        Thread.sleep(5);
        FolderEntry second = store.addFolder("b");
        store.addFolder("a");

        List<FolderEntry> folders = store.listFolders();
        assertEquals(2, folders.size());
        assertEquals(first.path(), folders.get(0).path());
        assertEquals(second.path(), folders.get(1).path());
    }

    @Test
    void newFoldersStartWithNoProject() {
        FolderEntry folder = store.addFolder("a");
        assertNull(folder.projectId());
    }

    @Test
    void addProjectThenAssignFolderGroupsIt() {
        ProjectEntry project = store.addProject("Acme Corp");
        store.addFolder("frontend");

        store.setFolderProject("frontend", project.id());

        FolderEntry reloaded = store.listFolders().stream()
                .filter(f -> f.path().equals("frontend"))
                .findFirst().orElseThrow();
        assertEquals(project.id(), reloaded.projectId());
    }

    @Test
    void assigningNullProjectIdUngroupsAFolder() {
        ProjectEntry project = store.addProject("Acme Corp");
        store.addFolder("frontend");
        store.setFolderProject("frontend", project.id());

        store.setFolderProject("frontend", null);

        FolderEntry reloaded = store.listFolders().stream()
                .filter(f -> f.path().equals("frontend"))
                .findFirst().orElseThrow();
        assertNull(reloaded.projectId());
    }

    @Test
    void renameProjectChangesItsNameButKeepsItsId() {
        ProjectEntry project = store.addProject("Old name");

        ProjectEntry renamed = store.renameProject(project.id(), "New name");

        assertEquals(project.id(), renamed.id());
        assertEquals("New name", renamed.name());
    }

    @Test
    void removingAProjectUngroupsItsFoldersWithoutDeletingThem() {
        ProjectEntry project = store.addProject("Acme Corp");
        store.addFolder("frontend");
        store.setFolderProject("frontend", project.id());

        boolean removed = store.removeProject(project.id());

        assertTrue(removed);
        assertTrue(store.listProjects().isEmpty());
        FolderEntry reloaded = store.listFolders().stream()
                .filter(f -> f.path().equals("frontend"))
                .findFirst().orElseThrow();
        assertNull(reloaded.projectId(), "folder should be kept, just ungrouped");
    }

    @Test
    void removingAProjectThatWasNeverAddedReturnsFalse() {
        assertFalse(store.removeProject("never-added"));
    }

    @Test
    void projectsAreOrderedByCreationTime() throws InterruptedException {
        ProjectEntry first = store.addProject("First");
        Thread.sleep(5);
        ProjectEntry second = store.addProject("Second");

        List<ProjectEntry> projects = store.listProjects();
        assertEquals(2, projects.size());
        assertEquals(first.id(), projects.get(0).id());
        assertEquals(second.id(), projects.get(1).id());
    }

    @Test
    void listConversationsOrdersByMostRecentlyUpdatedFirst() throws InterruptedException {
        Conversation older = store.createConversation();
        Thread.sleep(5);
        Conversation newer = store.createConversation();

        List<Conversation> all = store.listConversations();
        assertFalse(all.isEmpty());
        assertEquals(newer.getId(), all.get(0).getId());
        assertEquals(older.getId(), all.get(all.size() - 1).getId());
    }

    @Test
    void searchMatchesConversationTitles() {
        Conversation conversation = store.createConversation("Refactor the login flow", ConversationKind.CHAT, null);

        List<SearchResult> results = store.search("login");

        assertEquals(1, results.size());
        assertEquals(conversation.getId(), results.get(0).conversationId());
        assertTrue(results.get(0).matchedInTitle());
    }

    @Test
    void searchFallsBackToMessageContentWithASnippetWhenTitleDoesNotMatch() {
        // A custom title (not the "New chat" sentinel) so addMessage's auto-title logic
        // doesn't overwrite it with the message content below - otherwise the title would
        // end up containing the search term too, defeating the point of this test.
        Conversation conversation = store.createConversation("Unrelated title", ConversationKind.CHAT, null);
        store.addMessage(conversation.getId(), MessageRole.USER, "how do I configure the sqlite connection pool?",
                "general");

        List<SearchResult> results = store.search("sqlite");

        assertEquals(1, results.size());
        assertFalse(results.get(0).matchedInTitle());
        assertNotNull(results.get(0).snippet());
        assertTrue(results.get(0).snippet().contains("sqlite"));
    }

    @Test
    void searchEscapesLikeWildcardsInTheTerm() {
        store.createConversation("50% off sale planning", ConversationKind.CHAT, null);
        store.createConversation("50X off sale planning", ConversationKind.CHAT, null);

        // A literal "%" in the term must not act as a SQL LIKE wildcard matching "50X" too.
        List<SearchResult> results = store.search("50%");

        assertEquals(1, results.size());
        assertTrue(results.get(0).title().startsWith("50% off"));
    }

    @Test
    void searchDedupesAConversationThatMatchesBothTitleAndContent() {
        Conversation conversation = store.createConversation("banana bread recipe", ConversationKind.CHAT, null);
        store.addMessage(conversation.getId(), MessageRole.USER, "how ripe should the banana be?", "general");

        List<SearchResult> results = store.search("banana");

        assertEquals(1, results.size());
        assertTrue(results.get(0).matchedInTitle());
    }

    @Test
    void searchReturnsEmptyForABlankTerm() {
        store.createConversation("anything", ConversationKind.CHAT, null);
        assertTrue(store.search("   ").isEmpty());
    }
}
