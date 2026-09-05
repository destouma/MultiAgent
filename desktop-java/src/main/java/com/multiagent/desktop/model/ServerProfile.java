package com.multiagent.desktop.model;

/** Mirrors shared/types.ts ServerProfile. */
public class ServerProfile {
    private String id;
    private String name;
    private ProviderType providerType = ProviderType.LEMONADE;
    private String baseUrl;
    private String apiKey;
    private int maxHistory = 40;

    public ServerProfile() {
    }

    public ServerProfile(String id, String name, ProviderType providerType, String baseUrl,
                          String apiKey, int maxHistory) {
        this.id = id;
        this.name = name;
        this.providerType = providerType;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.maxHistory = maxHistory;
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

    @Override
    public String toString() {
        return name;
    }
}
