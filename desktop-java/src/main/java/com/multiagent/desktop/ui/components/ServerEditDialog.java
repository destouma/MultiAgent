package com.multiagent.desktop.ui.components;

import com.multiagent.desktop.model.AppSettings;
import com.multiagent.desktop.model.ProviderType;
import com.multiagent.desktop.model.ServerProfile;

import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;

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

        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(8);
        grid.setPadding(new Insets(12));
        grid.addRow(0, new Label("Name"), nameField);
        grid.addRow(1, new Label("Provider"), providerBox);
        grid.addRow(2, new Label("Base URL"), baseUrlField);
        grid.addRow(3, new Label("API key"), apiKeyField);
        grid.addRow(4, new Label("Max history"), maxHistorySpinner);

        getDialogPane().setContent(grid);
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        setResultConverter(buttonType -> {
            if (buttonType != ButtonType.OK) {
                return null;
            }
            String id = existing != null ? existing.getId() : UUID.randomUUID().toString();
            String name = nameField.getText().isBlank() ? "Server" : nameField.getText().trim();
            return new ServerProfile(id, name, providerBox.getValue(), baseUrlField.getText(),
                    apiKeyField.getText(), maxHistorySpinner.getValue());
        });
    }
}
