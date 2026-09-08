package com.multiagent.desktop.ui.components;

import com.multiagent.desktop.AppInfo;
import com.multiagent.desktop.model.AppSettings;
import com.multiagent.desktop.model.ServerProfile;
import com.multiagent.desktop.model.ThemeMode;
import com.multiagent.desktop.service.ConfigService;
import com.multiagent.desktop.service.DebugLog;
import com.multiagent.desktop.ui.viewmodel.ChatViewModel;

import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.function.Consumer;

/**
 * Theme (a global setting, shown first since it applies app-wide) + a flat list of every
 * saved server profile with Add/Edit/Remove/Set-default actions, mirroring the Electron
 * app's SettingsModal.tsx server management but as a persistent list rather than a
 * pick-one-then-edit-its-fields ComboBox. Saving copies the default profile's fields into
 * the "active connection" fields (baseUrl/apiKey/providerType/maxHistory), same as
 * desktop/electron/config.ts, then calls ChatViewModel.applySettingsChange() so the
 * LlmClient cache is rebuilt immediately without restarting the app.
 */
public class SettingsDialog extends Dialog<Void> {

    /** onThemeApplied lets the caller (MainWindow) actually swap the Scene's stylesheet live, since this dialog has no notion of the Scene itself. */
    public SettingsDialog(ChatViewModel viewModel, Consumer<ThemeMode> onThemeApplied) {
        setTitle("Settings");
        ConfigService config = viewModel.configService();
        AppSettings settings = config.ensureDefaultServer();

        ObservableList<ServerProfile> servers = FXCollections.observableArrayList(settings.getServers());
        String[] defaultServerId = {settings.getActiveServerId()};
        if (defaultServerId[0] == null && !servers.isEmpty()) {
            defaultServerId[0] = servers.get(0).getId();
        }

        // --- Theme: a global setting, not tied to any server, so it comes first. ---
        ComboBox<ThemeMode> themeBox = new ComboBox<>(FXCollections.observableArrayList(ThemeMode.values()));
        themeBox.setValue(settings.getTheme());
        HBox themeRow = new HBox(8, new Label("Theme"), themeBox);
        themeRow.setAlignment(Pos.CENTER_LEFT);

        // --- Debug: raw API traffic capture (global, like theme). ---
        CheckBox debugBox = new CheckBox("Debug: log raw API traffic");
        debugBox.setSelected(settings.isDebugLogging());
        Label debugHint = new Label("Records every request/response to the Debug panel and "
                + "api-debug.log next to config.json.");
        debugHint.setWrapText(true);
        debugHint.setStyle("-fx-text-fill: gray; -fx-font-size: 11px;");
        VBox debugRow = new VBox(2, debugBox, debugHint);

        // --- Servers: "+ Add new" above the list; each row gets Edit/Remove on the right. ---
        Label serversLabel = new Label("Servers");
        serversLabel.setStyle("-fx-font-weight: bold;");

        ListView<ServerProfile> serverList = new ListView<>(servers);
        serverList.setPrefHeight(220);

        Button addButton = new Button("+ Add new");
        addButton.setOnAction(e -> {
            ServerEditDialog dialog = new ServerEditDialog(null);
            // Without an explicit owner, a dialog opened from inside another dialog's
            // handler can end up behind its parent on Windows instead of on top of it -
            // looking exactly like the button "did nothing".
            dialog.initOwner(addButton.getScene().getWindow());
            dialog.showAndWait().ifPresent(profile -> {
                servers.add(profile);
                if (defaultServerId[0] == null) {
                    defaultServerId[0] = profile.getId();
                }
            });
        });

        Region addSpacer = new Region();
        HBox.setHgrow(addSpacer, Priority.ALWAYS);
        HBox serversHeader = new HBox(8, serversLabel, addSpacer, addButton);
        serversHeader.setAlignment(Pos.CENTER_LEFT);

        serverList.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(ServerProfile item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                    setText(null);
                    return;
                }
                boolean isDefault = item.getId().equals(defaultServerId[0]);

                Button defaultToggle = new Button(isDefault ? "★" : "☆");
                defaultToggle.setStyle("-fx-font-size: 14px; -fx-padding: 0 6 0 6;");
                defaultToggle.setDisable(isDefault);
                defaultToggle.setOnAction(e -> {
                    defaultServerId[0] = item.getId();
                    serverList.refresh();
                });

                Label nameLabel = new Label(item.getName() + (isDefault ? "  (default)" : ""));

                Region spacer = new Region();
                HBox.setHgrow(spacer, Priority.ALWAYS);

                Button editButton = new Button("Edit");
                editButton.setOnAction(e -> {
                    ServerEditDialog dialog = new ServerEditDialog(item);
                    dialog.initOwner(editButton.getScene().getWindow());
                    dialog.showAndWait().ifPresent(updated -> {
                        int index = servers.indexOf(item);
                        if (index >= 0) {
                            servers.set(index, updated);
                        }
                    });
                });

                Button removeButton = new Button("Remove");
                removeButton.setDisable(servers.size() <= 1);
                removeButton.setOnAction(e -> {
                    servers.remove(item);
                    if (isDefault && !servers.isEmpty()) {
                        defaultServerId[0] = servers.get(0).getId();
                    }
                    serverList.refresh();
                });

                HBox row = new HBox(4, defaultToggle, nameLabel, spacer, editButton, removeButton);
                row.setAlignment(Pos.CENTER_LEFT);
                setGraphic(row);
                setText(null);
            }
        });
        // Structural changes (add/remove) need every row's "Remove" disabled-state and
        // default marker recomputed, not just the changed row.
        servers.addListener((ListChangeListener<ServerProfile>) c -> serverList.refresh());

        Label versionLabel = new Label(AppInfo.nameWithVersion());
        versionLabel.setStyle("-fx-text-fill: gray; -fx-font-size: 11px;");

        VBox layout = new VBox(12, themeRow, debugRow, serversHeader, serverList, versionLabel);
        layout.setPadding(new Insets(12));
        layout.setPrefWidth(420);

        getDialogPane().setContent(layout);
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        setResultConverter(buttonType -> {
            if (buttonType != ButtonType.OK) {
                return null;
            }
            ServerProfile defaultProfile = servers.stream()
                    .filter(s -> s.getId().equals(defaultServerId[0]))
                    .findFirst()
                    .orElse(servers.isEmpty() ? null : servers.get(0));

            config.updateSettings(s -> {
                s.setServers(new ArrayList<>(servers));
                s.setTheme(themeBox.getValue());
                s.setDebugLogging(debugBox.isSelected());
                if (defaultProfile != null) {
                    s.setActiveServerId(defaultProfile.getId());
                    s.setBaseUrl(defaultProfile.getBaseUrl());
                    s.setApiKey(defaultProfile.getApiKey());
                    s.setProviderType(defaultProfile.getProviderType());
                    s.setMaxHistory(defaultProfile.getMaxHistory());
                }
            });
            DebugLog.setEnabled(debugBox.isSelected());
            viewModel.applySettingsChange();
            if (onThemeApplied != null) {
                onThemeApplied.accept(themeBox.getValue());
            }
            return null;
        });
    }
}
