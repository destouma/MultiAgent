package com.multiagent.desktop.persistence;

import com.multiagent.desktop.model.ChatMessage;
import com.multiagent.desktop.model.Conversation;
import com.multiagent.desktop.model.ConversationKind;
import com.multiagent.desktop.model.FileCheckpoint;
import com.multiagent.desktop.model.FolderEntry;
import com.multiagent.desktop.model.MessageRole;
import com.multiagent.desktop.model.ProjectEntry;
import com.multiagent.desktop.model.SearchResult;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * All persistence: conversations + messages (Phase 1). folders/file_checkpoints tables
 * exist (see Migrations) but their CRUD lands in Phase 2 alongside workspace tools.
 * Mirrors desktop/electron/services/conversationStore.ts, using real file-backed SQLite
 * via sqlite-jdbc instead of sql.js/WASM - writes land immediately, no export+atomic-
 * rename persist step needed.
 */
public class ConversationStore implements AutoCloseable {
    private static final Set<String> DEFAULT_TITLES = Set.of("New chat", "New image", "New orchestrator");

    private final Connection connection;

    public ConversationStore() {
        this(defaultDbPath());
    }

    public ConversationStore(Path dbPath) {
        try {
            if (dbPath.getParent() != null) {
                Files.createDirectories(dbPath.getParent());
            }
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
            Migrations.run(connection);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to open database at " + dbPath, e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path defaultDbPath() {
        String appData = System.getenv("APPDATA");
        Path base = appData != null
                ? Path.of(appData)
                : Path.of(System.getProperty("user.home"), ".config");
        return base.resolve("MultiAgentJava").resolve("chats.db");
    }

    public List<Conversation> listConversations() {
        String sql = "SELECT id, title, createdAt, updatedAt, workspacePath, kind, model, serverId, personaId, visionModel, specialistModels, orchestratorApply "
                + "FROM conversations ORDER BY updatedAt DESC";
        List<Conversation> conversations = new ArrayList<>();
        try (PreparedStatement stmt = connection.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                conversations.add(mapConversation(rs));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return conversations;
    }

    public Conversation createConversation(String title, ConversationKind kind, String workspacePath) {
        long now = System.currentTimeMillis();
        ConversationKind resolvedKind = kind == null ? ConversationKind.CHAT : kind;
        String resolvedTitle = (title == null || title.isBlank()) ? defaultTitleFor(resolvedKind) : title;

        String id = UUID.randomUUID().toString();
        String sql = "INSERT INTO conversations (id, title, createdAt, updatedAt, workspacePath, kind, model, serverId, personaId) "
                + "VALUES (?, ?, ?, ?, ?, ?, NULL, NULL, NULL)";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, id);
            stmt.setString(2, resolvedTitle);
            stmt.setLong(3, now);
            stmt.setLong(4, now);
            stmt.setString(5, workspacePath);
            stmt.setString(6, resolvedKind.wire());
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return new Conversation(id, resolvedTitle, now, now, workspacePath, resolvedKind, null, null, null);
    }

    public Conversation createConversation() {
        return createConversation(null, ConversationKind.CHAT, null);
    }

    private static String defaultTitleFor(ConversationKind kind) {
        return switch (kind) {
            case IMAGE -> "New image";
            case ORCHESTRATOR -> "New orchestrator";
            case CHAT -> "New chat";
        };
    }

    public Conversation renameConversation(String id, String title) {
        long updatedAt = System.currentTimeMillis();
        try (PreparedStatement stmt = connection.prepareStatement(
                "UPDATE conversations SET title = ?, updatedAt = ? WHERE id = ?")) {
            stmt.setString(1, title);
            stmt.setLong(2, updatedAt);
            stmt.setString(3, id);
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return getConversation(id);
    }

    public Conversation setConversationModel(String id, String model) {
        try (PreparedStatement stmt = connection.prepareStatement(
                "UPDATE conversations SET model = ? WHERE id = ?")) {
            stmt.setString(1, model);
            stmt.setString(2, id);
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return getConversation(id);
    }

    public Conversation setConversationVisionModel(String id, String visionModel) {
        try (PreparedStatement stmt = connection.prepareStatement(
                "UPDATE conversations SET visionModel = ? WHERE id = ?")) {
            stmt.setString(1, visionModel);
            stmt.setString(2, id);
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return getConversation(id);
    }

    public Conversation setConversationSpecialistModels(String id, String specialistModelsJson) {
        try (PreparedStatement stmt = connection.prepareStatement(
                "UPDATE conversations SET specialistModels = ? WHERE id = ?")) {
            stmt.setString(1, specialistModelsJson);
            stmt.setString(2, id);
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return getConversation(id);
    }

    public Conversation setConversationOrchestratorApply(String id, String value) {
        try (PreparedStatement stmt = connection.prepareStatement(
                "UPDATE conversations SET orchestratorApply = ? WHERE id = ?")) {
            stmt.setString(1, value);
            stmt.setString(2, id);
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return getConversation(id);
    }

    public Conversation setConversationServer(String id, String serverId) {
        try (PreparedStatement stmt = connection.prepareStatement(
                "UPDATE conversations SET serverId = ? WHERE id = ?")) {
            stmt.setString(1, serverId);
            stmt.setString(2, id);
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return getConversation(id);
    }

    public Conversation setConversationPersona(String id, String personaId) {
        try (PreparedStatement stmt = connection.prepareStatement(
                "UPDATE conversations SET personaId = ? WHERE id = ?")) {
            stmt.setString(1, personaId);
            stmt.setString(2, id);
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return getConversation(id);
    }

    public boolean deleteConversation(String id) {
        boolean existed = getConversation(id) != null;
        try (PreparedStatement deleteMessages = connection.prepareStatement(
                "DELETE FROM messages WHERE conversationId = ?");
             PreparedStatement deleteCheckpoints = connection.prepareStatement(
                     "DELETE FROM file_checkpoints WHERE conversationId = ?");
             PreparedStatement deleteConversation = connection.prepareStatement(
                     "DELETE FROM conversations WHERE id = ?")) {
            deleteMessages.setString(1, id);
            deleteMessages.executeUpdate();
            deleteCheckpoints.setString(1, id);
            deleteCheckpoints.executeUpdate();
            deleteConversation.setString(1, id);
            deleteConversation.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return existed;
    }

    public Conversation getConversation(String id) {
        String sql = "SELECT id, title, createdAt, updatedAt, workspacePath, kind, model, serverId, personaId, visionModel, specialistModels, orchestratorApply "
                + "FROM conversations WHERE id = ?";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, id);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? mapConversation(rs) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public List<ChatMessage> getMessages(String conversationId) {
        String sql = "SELECT id, conversationId, role, content, personaId, createdAt "
                + "FROM messages WHERE conversationId = ? ORDER BY createdAt ASC";
        List<ChatMessage> messages = new ArrayList<>();
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, conversationId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    messages.add(new ChatMessage(
                            rs.getString("id"),
                            rs.getString("conversationId"),
                            MessageRole.fromWire(rs.getString("role")),
                            rs.getString("content"),
                            rs.getString("personaId"),
                            rs.getLong("createdAt")));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return messages;
    }

    public ChatMessage addMessage(String conversationId, MessageRole role, String content, String personaId) {
        ChatMessage message = new ChatMessage(
                UUID.randomUUID().toString(), conversationId, role, content, personaId,
                System.currentTimeMillis());

        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO messages (id, conversationId, role, content, personaId, createdAt) "
                        + "VALUES (?, ?, ?, ?, ?, ?)")) {
            insert.setString(1, message.getId());
            insert.setString(2, message.getConversationId());
            insert.setString(3, message.getRole().wire());
            insert.setString(4, message.getContent());
            insert.setString(5, message.getPersonaId());
            insert.setLong(6, message.getCreatedAt());
            insert.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }

        try (PreparedStatement touch = connection.prepareStatement(
                "UPDATE conversations SET updatedAt = ? WHERE id = ?")) {
            touch.setLong(1, message.getCreatedAt());
            touch.setString(2, conversationId);
            touch.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }

        if (role == MessageRole.USER) {
            autoTitleFromFirstMessage(conversationId, content);
        }

        return message;
    }

    private void autoTitleFromFirstMessage(String conversationId, String content) {
        Conversation conversation = getConversation(conversationId);
        if (conversation == null || !DEFAULT_TITLES.contains(conversation.getTitle())) {
            return;
        }
        String fallback = defaultTitleFor(conversation.getKind());
        String raw = content == null ? "" : content;
        String title = raw.trim();
        title = title.length() > 48 ? title.substring(0, 48) : title;
        if (title.isEmpty()) {
            title = fallback;
        }
        try (PreparedStatement stmt = connection.prepareStatement(
                "UPDATE conversations SET title = ? WHERE id = ?")) {
            stmt.setString(1, title);
            stmt.setString(2, conversationId);
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public void updateMessageContent(String id, String content) {
        try (PreparedStatement stmt = connection.prepareStatement(
                "UPDATE messages SET content = ? WHERE id = ?")) {
            stmt.setString(1, content);
            stmt.setString(2, id);
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Deletes the given message and every message after it in the same conversation,
     * ordered by SQLite's implicit rowid (insertion order) rather than createdAt, since
     * two messages can share a millisecond timestamp. Used by edit-and-resend/regenerate.
     */
    public boolean deleteMessagesFrom(String conversationId, String messageId) {
        long rowid;
        try (PreparedStatement stmt = connection.prepareStatement(
                "SELECT rowid FROM messages WHERE id = ? AND conversationId = ?")) {
            stmt.setString(1, messageId);
            stmt.setString(2, conversationId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return false;
                }
                rowid = rs.getLong("rowid");
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }

        try (PreparedStatement stmt = connection.prepareStatement(
                "DELETE FROM messages WHERE conversationId = ? AND rowid >= ?")) {
            stmt.setString(1, conversationId);
            stmt.setLong(2, rowid);
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return true;
    }

    private static final int SEARCH_MAX_RESULTS = 30;

    /**
     * LIKE search across conversation titles first, then message content, mirroring
     * conversationStore.ts's search(): title hits win when a conversation matches both,
     * results are capped and deduped by conversation, and SQLite's own LIKE wildcards
     * (% and _) are escaped so a literal "%" in the search term isn't treated as one.
     */
    public List<SearchResult> search(String term) {
        String trimmed = term == null ? "" : term.trim();
        if (trimmed.isEmpty()) {
            return List.of();
        }
        String escaped = trimmed.replaceAll("([\\\\%_])", "\\\\$1");
        String pattern = "%" + escaped + "%";
        Map<String, SearchResult> results = new LinkedHashMap<>();

        try (PreparedStatement stmt = connection.prepareStatement(
                "SELECT id, title, kind, workspacePath FROM conversations "
                        + "WHERE title LIKE ? ESCAPE '\\' ORDER BY updatedAt DESC LIMIT ?")) {
            stmt.setString(1, pattern);
            stmt.setInt(2, SEARCH_MAX_RESULTS);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    results.put(rs.getString("id"), new SearchResult(
                            rs.getString("id"), rs.getString("title"),
                            ConversationKind.fromWire(rs.getString("kind")),
                            rs.getString("workspacePath"), true, null));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }

        if (results.size() < SEARCH_MAX_RESULTS) {
            String lowerTerm = trimmed.toLowerCase();
            try (PreparedStatement stmt = connection.prepareStatement(
                    "SELECT m.conversationId, m.content, c.title, c.kind, c.workspacePath "
                            + "FROM messages m JOIN conversations c ON c.id = m.conversationId "
                            + "WHERE m.content LIKE ? ESCAPE '\\' ORDER BY m.createdAt DESC LIMIT 200")) {
                stmt.setString(1, pattern);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (results.size() < SEARCH_MAX_RESULTS && rs.next()) {
                        String conversationId = rs.getString("conversationId");
                        if (results.containsKey(conversationId)) {
                            continue;
                        }
                        String content = rs.getString("content");
                        int matchIndex = content.toLowerCase().indexOf(lowerTerm);
                        int start = matchIndex >= 0 ? Math.max(0, matchIndex - 40) : 0;
                        int end = matchIndex >= 0
                                ? Math.min(content.length(), matchIndex + lowerTerm.length() + 40)
                                : Math.min(content.length(), 80);
                        String snippet = content.substring(start, end).trim();
                        results.put(conversationId, new SearchResult(
                                conversationId, rs.getString("title"),
                                ConversationKind.fromWire(rs.getString("kind")),
                                rs.getString("workspacePath"), false, snippet));
                    }
                }
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        }

        return new ArrayList<>(results.values());
    }

    public List<FolderEntry> listFolders() {
        List<FolderEntry> folders = new ArrayList<>();
        try (PreparedStatement stmt = connection.prepareStatement(
                "SELECT path, addedAt, projectId FROM folders ORDER BY addedAt ASC");
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                folders.add(new FolderEntry(rs.getString("path"), rs.getLong("addedAt"), rs.getString("projectId")));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return folders;
    }

    public FolderEntry addFolder(String folderPath) {
        long addedAt = System.currentTimeMillis();
        try (PreparedStatement stmt = connection.prepareStatement(
                "INSERT OR IGNORE INTO folders (path, addedAt) VALUES (?, ?)")) {
            stmt.setString(1, folderPath);
            stmt.setLong(2, addedAt);
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        try (PreparedStatement stmt = connection.prepareStatement(
                "SELECT path, addedAt, projectId FROM folders WHERE path = ?")) {
            stmt.setString(1, folderPath);
            try (ResultSet rs = stmt.executeQuery()) {
                rs.next();
                return new FolderEntry(rs.getString("path"), rs.getLong("addedAt"), rs.getString("projectId"));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public boolean removeFolder(String folderPath) {
        boolean existed = listFolders().stream().anyMatch(f -> f.path().equals(folderPath));
        if (!existed) {
            return false;
        }
        try (PreparedStatement deleteFolder = connection.prepareStatement("DELETE FROM folders WHERE path = ?");
             PreparedStatement clearBinding = connection.prepareStatement(
                     "UPDATE conversations SET workspacePath = NULL, updatedAt = ? WHERE workspacePath = ?")) {
            deleteFolder.setString(1, folderPath);
            deleteFolder.executeUpdate();
            clearBinding.setLong(1, System.currentTimeMillis());
            clearBinding.setString(2, folderPath);
            clearBinding.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return true;
    }

    /** Assigns a folder to a project, or ungroups it when projectId is null. Silently a no-op if the folder isn't registered. */
    public void setFolderProject(String folderPath, String projectId) {
        try (PreparedStatement stmt = connection.prepareStatement(
                "UPDATE folders SET projectId = ? WHERE path = ?")) {
            stmt.setString(1, projectId);
            stmt.setString(2, folderPath);
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public List<ProjectEntry> listProjects() {
        List<ProjectEntry> projects = new ArrayList<>();
        try (PreparedStatement stmt = connection.prepareStatement(
                "SELECT id, name, createdAt FROM projects ORDER BY createdAt ASC");
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                projects.add(new ProjectEntry(rs.getString("id"), rs.getString("name"), rs.getLong("createdAt")));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return projects;
    }

    public ProjectEntry addProject(String name) {
        String id = UUID.randomUUID().toString();
        long createdAt = System.currentTimeMillis();
        try (PreparedStatement stmt = connection.prepareStatement(
                "INSERT INTO projects (id, name, createdAt) VALUES (?, ?, ?)")) {
            stmt.setString(1, id);
            stmt.setString(2, name);
            stmt.setLong(3, createdAt);
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return new ProjectEntry(id, name, createdAt);
    }

    public ProjectEntry renameProject(String id, String newName) {
        try (PreparedStatement stmt = connection.prepareStatement(
                "UPDATE projects SET name = ? WHERE id = ?")) {
            stmt.setString(1, newName);
            stmt.setString(2, id);
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return listProjects().stream().filter(p -> p.id().equals(id)).findFirst().orElse(null);
    }

    /** Deletes the project and ungroups its folders (projectId -> NULL) - folders and their chats are untouched, same unbind-not-cascade pattern as removeFolder(). */
    public boolean removeProject(String id) {
        boolean existed = listProjects().stream().anyMatch(p -> p.id().equals(id));
        if (!existed) {
            return false;
        }
        try (PreparedStatement deleteProject = connection.prepareStatement("DELETE FROM projects WHERE id = ?");
             PreparedStatement clearBinding = connection.prepareStatement(
                     "UPDATE folders SET projectId = NULL WHERE projectId = ?")) {
            deleteProject.setString(1, id);
            deleteProject.executeUpdate();
            clearBinding.setString(1, id);
            clearBinding.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return true;
    }

    public FileCheckpoint addCheckpoint(String conversationId, String relativePath, String previousContent,
                                         boolean previousExisted) {
        FileCheckpoint checkpoint = new FileCheckpoint(UUID.randomUUID().toString(), conversationId, relativePath,
                previousContent, previousExisted, System.currentTimeMillis());
        try (PreparedStatement stmt = connection.prepareStatement(
                "INSERT INTO file_checkpoints (id, conversationId, relativePath, previousContent, previousExisted, createdAt) "
                        + "VALUES (?, ?, ?, ?, ?, ?)")) {
            stmt.setString(1, checkpoint.id());
            stmt.setString(2, checkpoint.conversationId());
            stmt.setString(3, checkpoint.relativePath());
            stmt.setString(4, checkpoint.previousContent());
            stmt.setInt(5, checkpoint.previousExisted() ? 1 : 0);
            stmt.setLong(6, checkpoint.createdAt());
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return checkpoint;
    }

    public FileCheckpoint getCheckpoint(String id) {
        try (PreparedStatement stmt = connection.prepareStatement(
                "SELECT id, conversationId, relativePath, previousContent, previousExisted, createdAt "
                        + "FROM file_checkpoints WHERE id = ?")) {
            stmt.setString(1, id);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new FileCheckpoint(
                        rs.getString("id"),
                        rs.getString("conversationId"),
                        rs.getString("relativePath"),
                        rs.getString("previousContent"),
                        rs.getInt("previousExisted") != 0,
                        rs.getLong("createdAt"));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private Conversation mapConversation(ResultSet rs) throws SQLException {
        Conversation conversation = new Conversation(
                rs.getString("id"),
                rs.getString("title"),
                rs.getLong("createdAt"),
                rs.getLong("updatedAt"),
                rs.getString("workspacePath"),
                ConversationKind.fromWire(rs.getString("kind")),
                rs.getString("model"),
                rs.getString("serverId"),
                rs.getString("personaId"));
        conversation.setVisionModel(rs.getString("visionModel"));
        conversation.setSpecialistModels(rs.getString("specialistModels"));
        conversation.setOrchestratorApply(rs.getString("orchestratorApply"));
        return conversation;
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (SQLException ignored) {
            // best-effort on shutdown
        }
    }
}
