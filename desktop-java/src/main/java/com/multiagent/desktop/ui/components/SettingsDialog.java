package com.multiagent.desktop.ui.components;

import com.multiagent.desktop.AppInfo;
import com.multiagent.desktop.model.AppSettings;
import com.multiagent.desktop.model.ModelInfo;
import com.multiagent.desktop.model.Persona;
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
import javafx.scene.control.Alert;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

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

        // --- Personas: view the bundled ones, add / edit / remove your own. ---
        VBox personasSection = buildPersonasSection(viewModel);

        Label versionLabel = new Label(AppInfo.nameWithVersion());
        versionLabel.setStyle("-fx-text-fill: gray; -fx-font-size: 11px;");

        VBox layout = new VBox(12, themeRow, debugRow, serversHeader, serverList, personasSection, versionLabel);
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

    /**
     * A "+ Add new" header over a list of every loaded persona. Bundled personas get a
     * <b>View</b> button (read-only); user-defined ones (a file in the writable override dir)
     * get <b>Edit</b> / <b>Remove</b>. Changes are written to disk and pushed to the panes
     * immediately via {@code ChatViewModel}, independent of this dialog's OK/Cancel.
     */
    private static VBox buildPersonasSection(ChatViewModel viewModel) {
        Label label = new Label("Personas");
        label.setStyle("-fx-font-weight: bold;");

        ObservableList<Persona> personas = FXCollections.observableArrayList(viewModel.personas());
        ListView<Persona> list = new ListView<>(personas);
        list.setPrefHeight(160);

        Runnable refresh = () -> personas.setAll(viewModel.personas());

        List<String> modelIds = viewModel.models().stream()
                .map(ModelInfo::id).filter(s -> s != null && !s.isBlank()).toList();

        Button addButton = new Button("+ Add new");
        addButton.setOnAction(e -> {
            Set<String> taken = personas.stream().map(Persona::getId).collect(Collectors.toCollection(HashSet::new));
            PersonaEditDialog dialog = new PersonaEditDialog(null, false, taken, modelIds);
            dialog.initOwner(addButton.getScene().getWindow());
            dialog.showAndWait().ifPresent(persona -> {
                try {
                    viewModel.saveCustomPersona(persona);
                    refresh.run();
                } catch (RuntimeException ex) {
                    showError(addButton, ex.getMessage());
                }
            });
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(8, label, spacer, addButton);
        header.setAlignment(Pos.CENTER_LEFT);

        list.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(Persona item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                    setText(null);
                    return;
                }
                boolean custom = viewModel.isCustomPersona(item.getId());

                Region swatch = new Region();
                swatch.setMinSize(12, 12);
                swatch.setPrefSize(12, 12);
                swatch.setMaxSize(12, 12);
                String color = PersonaEditDialog.sanitizeColor(item.getColor());
                swatch.setStyle("-fx-background-radius: 3; -fx-border-radius: 3; -fx-border-color: gray;"
                        + " -fx-background-color: " + (color == null ? "transparent" : color) + ";");

                Label nameLabel = new Label(item.getName() + (custom ? "  (custom)" : "  (built-in)"));

                Region rowSpacer = new Region();
                HBox.setHgrow(rowSpacer, Priority.ALWAYS);

                HBox row = new HBox(8, swatch, nameLabel, rowSpacer);
                row.setAlignment(Pos.CENTER_LEFT);

                if (custom) {
                    Button editButton = new Button("Edit");
                    editButton.setOnAction(e -> {
                        Set<String> taken = personas.stream().map(Persona::getId)
                                .filter(id -> !id.equals(item.getId()))
                                .collect(Collectors.toCollection(HashSet::new));
                        PersonaEditDialog dialog = new PersonaEditDialog(item, false, taken, modelIds);
                        dialog.initOwner(editButton.getScene().getWindow());
                        dialog.showAndWait().ifPresent(updated -> {
                            try {
                                viewModel.saveCustomPersona(updated);
                                refresh.run();
                            } catch (RuntimeException ex) {
                                showError(editButton, ex.getMessage());
                            }
                        });
                    });

                    Button removeButton = new Button("Remove");
                    removeButton.setOnAction(e -> {
                        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                                "Remove the persona \"" + item.getName() + "\"? Chats pinned to it "
                                        + "fall back to the default persona.", ButtonType.OK, ButtonType.CANCEL);
                        confirm.initOwner(removeButton.getScene().getWindow());
                        confirm.setHeaderText(null);
                        confirm.showAndWait().filter(b -> b == ButtonType.OK).ifPresent(b -> {
                            viewModel.deleteCustomPersona(item.getId());
                            refresh.run();
                        });
                    });

                    row.getChildren().addAll(editButton, removeButton);
                } else {
                    Button viewButton = new Button("View");
                    viewButton.setOnAction(e -> {
                        PersonaEditDialog dialog = new PersonaEditDialog(item, true, Set.of(), modelIds);
                        dialog.initOwner(viewButton.getScene().getWindow());
                        dialog.showAndWait();
                    });
                    row.getChildren().add(viewButton);
                }

                setGraphic(row);
                setText(null);
            }
        });

        Label hint = new Label("Custom personas are saved as JSON in " + viewModel.customPersonaDir() + ".");
        hint.setWrapText(true);
        hint.setStyle("-fx-text-fill: gray; -fx-font-size: 11px;");

        return new VBox(6, header, list, hint);
    }

    private static void showError(javafx.scene.Node owner, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR, message == null ? "Something went wrong." : message);
        if (owner.getScene() != null) {
            alert.initOwner(owner.getScene().getWindow());
        }
        alert.setHeaderText(null);
        alert.showAndWait();
    }
}
