package com.multiagent.desktop.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Mirrors shared/types.ts AppSettings / desktop/electron/config.ts defaults exactly,
 * including the default Lemonade base URL - keep in sync with the Electron app so a
 * shared mental model of "what does a fresh install point at" holds across both clients.
 */
public class AppSettings {
    public static final String DEFAULT_BASE_URL = "http://localhost:13305/api/v1";
    public static final String DEFAULT_API_KEY = "local-llm";
    public static final int DEFAULT_MAX_HISTORY = 40;

    private String baseUrl = DEFAULT_BASE_URL;
    private String apiKey = DEFAULT_API_KEY;
    private String model = "";
    private String imageModel = "";
    private int maxHistory = DEFAULT_MAX_HISTORY;
    private ThemeMode theme = ThemeMode.LIGHT;
    private ProviderType providerType = ProviderType.LEMONADE;
    private List<ServerProfile> servers = new ArrayList<>();
    private String activeServerId;

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

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getImageModel() {
        return imageModel;
    }

    public void setImageModel(String imageModel) {
        this.imageModel = imageModel;
    }

    public int getMaxHistory() {
        return maxHistory;
    }

    public void setMaxHistory(int maxHistory) {
        this.maxHistory = maxHistory;
    }

    public ThemeMode getTheme() {
        return theme;
    }

    public void setTheme(ThemeMode theme) {
        this.theme = theme;
    }

    public ProviderType getProviderType() {
        return providerType;
    }

    public void setProviderType(ProviderType providerType) {
        this.providerType = providerType;
    }

    public List<ServerProfile> getServers() {
        return servers;
    }

    public void setServers(List<ServerProfile> servers) {
        this.servers = servers;
    }

    public String getActiveServerId() {
        return activeServerId;
    }

    public void setActiveServerId(String activeServerId) {
        this.activeServerId = activeServerId;
    }
}
