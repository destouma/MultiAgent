package com.multiagent.desktop.model;

/** Mirrors shared/types.ts Persona. Loaded from personas/*.json (Jackson). */
public class Persona {
    private String id;
    private String name;
    private String description;
    private String systemPrompt;
    private String defaultModel;
    private String color;

    public Persona() {
    }

    public Persona(String id, String name, String description, String systemPrompt, String color) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.systemPrompt = systemPrompt;
        this.color = color;
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

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public void setSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
    }

    public String getDefaultModel() {
        return defaultModel;
    }

    public void setDefaultModel(String defaultModel) {
        this.defaultModel = defaultModel;
    }

    public String getColor() {
        return color;
    }

    public void setColor(String color) {
        this.color = color;
    }

    public boolean isValid() {
        return id != null && !id.isBlank() && name != null && !name.isBlank()
                && systemPrompt != null && !systemPrompt.isBlank();
    }

    @Override
    public String toString() {
        return name;
    }
}
