package com.multiagent.desktop.model;

/**
 * Mirrors shared/types.ts Conversation, plus one deliberate addition: personaId. The
 * Electron app keeps the active persona as a single pane-wide field (chatStore.ts's
 * activePersonaId), not a per-conversation one - but per-chat isolation is a hard
 * requirement for this Java client (see ChatViewModel), so persona is pinned to the
 * conversation exactly like model/serverId already are, persisted the same way.
 */
public class Conversation {
    private String id;
    private String title;
    private long createdAt;
    private long updatedAt;
    private String workspacePath;
    private ConversationKind kind = ConversationKind.CHAT;
    private String model;
    private String serverId;
    private String personaId;

    public Conversation() {
    }

    public Conversation(String id, String title, long createdAt, long updatedAt,
                         String workspacePath, ConversationKind kind, String model, String serverId) {
        this(id, title, createdAt, updatedAt, workspacePath, kind, model, serverId, null);
    }

    public Conversation(String id, String title, long createdAt, long updatedAt,
                         String workspacePath, ConversationKind kind, String model, String serverId,
                         String personaId) {
        this.id = id;
        this.title = title;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.workspacePath = workspacePath;
        this.kind = kind;
        this.model = model;
        this.serverId = serverId;
        this.personaId = personaId;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(long updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getWorkspacePath() {
        return workspacePath;
    }

    public void setWorkspacePath(String workspacePath) {
        this.workspacePath = workspacePath;
    }

    public ConversationKind getKind() {
        return kind;
    }

    public void setKind(ConversationKind kind) {
        this.kind = kind;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getServerId() {
        return serverId;
    }

    public void setServerId(String serverId) {
        this.serverId = serverId;
    }

    public String getPersonaId() {
        return personaId;
    }

    public void setPersonaId(String personaId) {
        this.personaId = personaId;
    }

    @Override
    public String toString() {
        return title;
    }
}
