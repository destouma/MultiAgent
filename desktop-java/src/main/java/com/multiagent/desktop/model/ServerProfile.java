package com.multiagent.desktop.model;

/** Mirrors shared/types.ts ServerProfile. */
public class ServerProfile {
    private String id;
    private String name;
    private ProviderType providerType = ProviderType.LEMONADE;
    private String baseUrl;
    private String apiKey;
    private int maxHistory = 40;
    /** Id of a vision/multimodal model loaded on this server, enabling the describe_image tool + image attachments. Blank = off. */
    private String visionModel = "";

    public ServerProfile() {
    }

    public ServerProfile(String id, String name, ProviderType providerType, String baseUrl,
                          String apiKey, int maxHistory) {
        this(id, name, providerType, baseUrl, apiKey, maxHistory, "");
    }

    public ServerProfile(String id, String name, ProviderType providerType, String baseUrl,
                          String apiKey, int maxHistory, String visionModel) {
        this.id = id;
        this.name = name;
        this.providerType = providerType;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.maxHistory = maxHistory;
        this.visionModel = visionModel == null ? "" : visionModel;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public ProviderType getProviderType() {
        return providerType;
    }

    public void setProviderType(ProviderType providerType) {
        this.providerType = providerType;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public int getMaxHistory() {
        return maxHistory;
    }

    public void setMaxHistory(int maxHistory) {
        this.maxHistory = maxHistory;
    }

    public String getVisionModel() {
        return visionModel == null ? "" : visionModel;
    }

    public void setVisionModel(String visionModel) {
        this.visionModel = visionModel == null ? "" : visionModel;
    }

    @Override
    public String toString() {
        return name;
    }
}
