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
    /** Per-chat override of the server's vision model. Blank/null ⇒ fall back to ServerProfile.visionModel. */
    private String visionModel;
    /** Orchestrator only: JSON {"specialistId":"modelId"} of per-conversation model overrides. Blank/null ⇒ none. */
    private String specialistModels;
    /**
     * Orchestrator only: whether the write-capable executor phase runs after synthesis (every write
     * is still approval-gated). It's <em>on by default</em> whenever a workspace is bound - {@code "0"}
     * is an explicit opt-out (pure planning), {@code "1"}/null both mean "run it".
     */
    private String orchestratorApply;

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

    public String getVisionModel() {
        return visionModel;
    }

    public void setVisionModel(String visionModel) {
        this.visionModel = visionModel;
    }

    public String getSpecialistModels() {
        return specialistModels;
    }

    public void setSpecialistModels(String specialistModels) {
        this.specialistModels = specialistModels;
    }

    public String getOrchestratorApply() {
        return orchestratorApply;
    }

    public void setOrchestratorApply(String orchestratorApply) {
        this.orchestratorApply = orchestratorApply;
    }

    /** True unless the user explicitly opted out ({@code "0"}); the caller still gates this on a workspace being bound. */
    public boolean isOrchestratorApply() {
        return !"0".equals(orchestratorApply);
    }

    @Override
    public String toString() {
        return title;
    }
}
