package com.multiagent.desktop.ui.components;

import com.multiagent.desktop.llm.LlmClient;
import com.multiagent.desktop.llm.LlmClientFactory;
import com.multiagent.desktop.llm.ProviderSettings;
import com.multiagent.desktop.model.AppSettings;
import com.multiagent.desktop.model.ModelInfo;
import com.multiagent.desktop.model.ProviderType;
import com.multiagent.desktop.model.ServerProfile;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;

import java.util.List;
import java.util.UUID;

/** Add or edit a single server profile's fields. Returns the (new or updated) profile on OK, null on Cancel. */
public class ServerEditDialog extends Dialog<ServerProfile> {

    /** Pass null to create a new profile. */
    public ServerEditDialog(ServerProfile existing) {
        setTitle(existing == null ? "Add server" : "Edit server");

        TextField nameField = new TextField(existing != null ? existing.getName() : "New server");
        ComboBox<ProviderType> providerBox = new ComboBox<>(FXCollections.observableArrayList(ProviderType.values()));
        providerBox.setValue(existing != null ? existing.getProviderType() : ProviderType.LEMONADE);
        TextField baseUrlField = new TextField(existing != null ? existing.getBaseUrl() : AppSettings.DEFAULT_BASE_URL);
        PasswordField apiKeyField = new PasswordField();
        apiKeyField.setText(existing != null ? existing.getApiKey() : AppSettings.DEFAULT_API_KEY);
        Spinner<Integer> maxHistorySpinner = new Spinner<>(1, 500,
                existing != null ? existing.getMaxHistory() : AppSettings.DEFAULT_MAX_HISTORY);
        maxHistorySpinner.setEditable(true);

        // Editable so an id the server doesn't currently list (not loaded yet, server down)
        // can still be typed; the dropdown is a convenience seeded from /models.
        ComboBox<String> visionModelBox = new ComboBox<>();
        visionModelBox.setEditable(true);
        visionModelBox.setPromptText("optional - pick or type a vision model id");
        visionModelBox.setMaxWidth(Double.MAX_VALUE);
        if (existing != null && !existing.getVisionModel().isBlank()) {
            visionModelBox.getItems().add(existing.getVisionModel());
            visionModelBox.setValue(existing.getVisionModel());
        }

        Label modelsStatus = new Label();
        modelsStatus.setStyle("-fx-text-fill: gray; -fx-font-size: 11px;");
        Button refreshModels = new Button("↻");
        refreshModels.setTooltip(new javafx.scene.control.Tooltip("Fetch the model list from this server"));

        Runnable fetchModels = () -> {
            String typed = visionModelBox.getEditor().getText();
            modelsStatus.setText("loading models...");
            ProviderType provider = providerBox.getValue();
            ProviderSettings settings = new ProviderSettings(baseUrlField.getText(), apiKeyField.getText());
            Thread worker = new Thread(() -> {
                String status;
                List<String> ids;
                try {
                    LlmClient client = LlmClientFactory.create(provider, settings);
                    ids = client.listModels().stream().map(ModelInfo::id).filter(s -> !s.isBlank()).toList();
                    status = ids.isEmpty() ? "server returned no models" : ids.size() + " models";
                } catch (RuntimeException e) {
                    ids = List.of();
                    status = "could not reach server";
                }
                List<String> finalIds = ids;
                String finalStatus = status;
                Platform.runLater(() -> {
                    visionModelBox.getItems().setAll(finalIds);
                    if (typed != null && !typed.isBlank() && !finalIds.contains(typed)) {
                        visionModelBox.getItems().add(0, typed);
                    }
                    visionModelBox.getEditor().setText(typed);
                    modelsStatus.setText(finalStatus);
                });
            }, "server-edit-models");
            worker.setDaemon(true);
            worker.start();
        };
        refreshModels.setOnAction(e -> fetchModels.run());

        HBox visionRow = new HBox(6, visionModelBox, refreshModels);
        visionRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(visionModelBox, Priority.ALWAYS);

        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(8);
        grid.setPadding(new Insets(12));
        grid.addRow(0, new Label("Name"), nameField);
        grid.addRow(1, new Label("Provider"), providerBox);
        grid.addRow(2, new Label("Base URL"), baseUrlField);
        grid.addRow(3, new Label("API key"), apiKeyField);
        grid.addRow(4, new Label("Max history"), maxHistorySpinner);
        grid.addRow(5, new Label("Vision model"), visionRow);
        grid.add(modelsStatus, 1, 6);

        getDialogPane().setContent(grid);
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        // Populate the dropdown once the dialog is on screen (a network call - keep it off construction).
        Platform.runLater(fetchModels);

        setResultConverter(buttonType -> {
            if (buttonType != ButtonType.OK) {
                return null;
            }
            String id = existing != null ? existing.getId() : UUID.randomUUID().toString();
            String name = nameField.getText().isBlank() ? "Server" : nameField.getText().trim();
            String visionModel = visionModelBox.getEditor().getText() == null
                    ? "" : visionModelBox.getEditor().getText().trim();
            return new ServerProfile(id, name, providerBox.getValue(), baseUrlField.getText(),
                    apiKeyField.getText(), maxHistorySpinner.getValue(), visionModel);
        });
    }
}
