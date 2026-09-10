package com.multiagent.desktop.ui.components;

import com.multiagent.desktop.model.Conversation;
import com.multiagent.desktop.model.HealthStatus;
import com.multiagent.desktop.model.ModelInfo;
import com.multiagent.desktop.model.Persona;
import com.multiagent.desktop.model.ServerProfile;
import com.multiagent.desktop.ui.viewmodel.ChatViewModel;

import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.Callback;

import java.util.List;
import java.util.Objects;

/**
 * One conversation pane: its own topbar (persona/server/model/health), orchestrator status
 * banner, chat thread, and composer - all bound to one ChatViewModel. Extracted out of
 * MainWindow so split view (Phase 5) can instantiate two of these, one per pane, exactly
 * mirroring App.tsx's per-pane topbar vs. the app-level ".global-topbar".
 */
public class ChatPaneView extends BorderPane {
    private final ChatViewModel viewModel;
    /** True while syncPersonaItems is rewriting the persona box - suppresses the value listener so a programmatic reselect can't loop back into setPersona -> activeConversation.set -> resync. */
    private boolean syncingPersonaBox;

    public ChatPaneView(ChatViewModel viewModel) {
        this.viewModel = viewModel;
        getStyleClass().add("chat-panel");
        setTop(buildConversationTopBar());
        setCenter(buildCenterColumn());
        VBox bottom = new VBox(new ContextUsageBar(viewModel), new Composer(viewModel));
        setBottom(bottom);
    }

    private javafx.scene.Node buildCenterColumn() {
        Label statusBanner = new Label();
        statusBanner.getStyleClass().add("orchestrator-status");
        statusBanner.setMaxWidth(Double.MAX_VALUE);
        statusBanner.visibleProperty().bind(viewModel.orchestratorStatusProperty().isNotEmpty());
        statusBanner.managedProperty().bind(statusBanner.visibleProperty());
        statusBanner.textProperty().bind(viewModel.orchestratorStatusProperty());

        ChatThread thread = new ChatThread(viewModel);
        VBox column = new VBox(statusBanner, thread);
        VBox.setVgrow(thread, Priority.ALWAYS);
        return column;
    }

    /**
     * Belongs to the active conversation: persona/server/model selection and this
     * conversation's own resolved server health. Server/model here pin THIS conversation
     * (Conversation.serverId/model) - a different conversation keeps its own pin
     * untouched, so two chats (in this pane and the other) can point at two different
     * servers/models at once.
     */
    private javafx.scene.Node buildConversationTopBar() {
        // Own item list, not viewModel.personas() directly, so it can be filtered by kind
        // (the "orchestrator" persona is only meaningful as an orchestrator chat's coordinator).
        ComboBox<Persona> personaBox = new ComboBox<>();
        personaBox.setPromptText("Persona");
        personaBox.valueProperty().addListener((obs, old, val) -> {
            if (val != null && !syncingPersonaBox) {
                viewModel.setPersona(val);
            }
        });
        viewModel.activePersonaProperty().addListener((obs, old, val) -> personaBox.setValue(val));
        viewModel.personas().addListener((ListChangeListener<Persona>) c -> syncPersonaItems(personaBox, isOrchestrator(viewModel)));

        ComboBox<ServerProfile> serverBox = new ComboBox<>();
        serverBox.setPromptText("Server");
        Callback<ListView<ServerProfile>, ListCell<ServerProfile>> serverCellFactory = lv -> new ListCell<>() {
            @Override
            protected void updateItem(ServerProfile item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? null : (item == null ? "Default (active connection)" : item.getName()));
            }
        };
        serverBox.setCellFactory(serverCellFactory);
        serverBox.setButtonCell(serverCellFactory.call(null));
        serverBox.setOnShowing(e -> {
            ServerProfile current = serverBox.getValue();
            ObservableList<ServerProfile> items = FXCollections.observableArrayList();
            items.add(null);
            items.addAll(viewModel.configService().getSettings().getServers());
            serverBox.setItems(items);
            serverBox.setValue(current);
        });
        serverBox.getItems().add(null);
        serverBox.getItems().addAll(viewModel.configService().getSettings().getServers());

        Runnable syncServerFromConversation = () -> {
            Conversation conversation = viewModel.activeConversationProperty().get();
            String serverId = conversation != null ? conversation.getServerId() : null;
            ServerProfile match = serverId == null ? null : serverBox.getItems().stream()
                    .filter(p -> p != null && p.getId().equals(serverId))
                    .findFirst()
                    .orElse(null);
            serverBox.setValue(match);
        };
        viewModel.activeConversationProperty().addListener((obs, old, val) -> syncServerFromConversation.run());
        syncServerFromConversation.run();

        serverBox.valueProperty().addListener((obs, old, val) -> {
            Conversation conversation = viewModel.activeConversationProperty().get();
            if (conversation == null) {
                return;
            }
            String newServerId = val == null ? null : val.getId();
            if (!Objects.equals(conversation.getServerId(), newServerId)) {
                viewModel.setServer(val);
            }
        });

        ComboBox<String> modelBox = new ComboBox<>();
        modelBox.setPromptText("Model");
        // Non-editable: the model id must be one the server actually reported. An editable
        // box let stray keystrokes prepend to the id (e.g. "analyse this file" + a real id),
        // which the server then 404s as model_not_found.
        modelBox.setEditable(false);
        syncModelItems(modelBox, viewModel.models());
        viewModel.models().addListener((ListChangeListener<ModelInfo>) c -> syncModelItems(modelBox, viewModel.models()));
        modelBox.valueProperty().addListener((obs, old, val) -> {
            if (val != null && !val.equals(viewModel.activeModelProperty().get())) {
                viewModel.setModel(val);
            }
        });
        viewModel.activeModelProperty().addListener((obs, old, val) -> {
            if (val != null && !val.equals(modelBox.getValue())) {
                modelBox.setValue(val);
            }
        });
        if (viewModel.activeModelProperty().get() != null) {
            modelBox.setValue(viewModel.activeModelProperty().get());
        }

        // Vision model for this chat. "" = the server profile's default (or off). A real id
        // pins this conversation to it - and when it equals Model, images go inline.
        ComboBox<String> visionBox = new ComboBox<>();
        visionBox.setEditable(false);
        Callback<ListView<String>, ListCell<String>> visionCells = lv -> new ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? null : (item == null || item.isEmpty() ? "(server default)" : item));
            }
        };
        visionBox.setCellFactory(visionCells);
        visionBox.setButtonCell(visionCells.call(null));
        syncVisionItems(visionBox, viewModel.models());
        viewModel.models().addListener((ListChangeListener<ModelInfo>) c -> syncVisionItems(visionBox, viewModel.models()));
        visionBox.valueProperty().addListener((obs, old, val) -> {
            if (val == null) {
                return;
            }
            if (val.isEmpty()) {
                viewModel.setVisionModel("");
            } else if (!val.equals(viewModel.activeVisionModelProperty().get())) {
                viewModel.setVisionModel(val);
            }
        });
        viewModel.activeVisionModelProperty().addListener((obs, old, val) -> {
            String show = val == null ? "" : val;
            if (!show.equals(visionBox.getValue())) {
                visionBox.setValue(show);
            }
        });
        visionBox.setValue(viewModel.activeVisionModelProperty().get() == null
                ? "" : viewModel.activeVisionModelProperty().get());

        // Orchestrator only: pick a model per specialist for this chat.
        javafx.scene.control.Button specialistsButton = new javafx.scene.control.Button("Specialists…");
        specialistsButton.setOnAction(e -> {
            com.multiagent.desktop.model.Conversation c = viewModel.activeConversationProperty().get();
            if (c == null) {
                return;
            }
            SpecialistModelsDialog dialog = new SpecialistModelsDialog(
                    viewModel.availableSpecialistPersonas(),
                    com.multiagent.desktop.service.SpecialistModels.parse(c.getSpecialistModels()),
                    viewModel.models(), viewModel.activeModelProperty().get());
            if (specialistsButton.getScene() != null) {
                dialog.initOwner(specialistsButton.getScene().getWindow());
            }
            dialog.showAndWait().ifPresent(viewModel::setSpecialistModels);
        });
        // "Persona" reads as the *coordinator* picker in an orchestrator chat, so relabel it
        // Persona -> Coordinator and show the Specialists button there, keyed off the kind.
        // "Vision" applies to both kinds: an orchestrator chat runs the same image pre-pass
        // (transcribe once, fold into the request) before planning - see OrchestratorService.
        Label personaCaption = new Label("Persona");
        personaCaption.getStyleClass().add("topbar-field-label");
        VBox personaColumn = new VBox(2, personaCaption, personaBox);
        VBox visionColumn = labeledColumn("Vision", visionBox);
        VBox specialistsColumn = labeledColumn(" ", specialistsButton); // blank caption keeps it aligned with the dropdowns

        // Which folder this chat is bound to - it's where write_file/run_command act. Click to open it.
        javafx.scene.control.Hyperlink folderLink = new javafx.scene.control.Hyperlink();
        folderLink.setMaxWidth(200);
        folderLink.setOnAction(e -> {
            com.multiagent.desktop.model.Conversation c = viewModel.activeConversationProperty().get();
            if (c != null && c.getWorkspacePath() != null && !c.getWorkspacePath().isBlank()) {
                openInFileManager(c.getWorkspacePath());
            }
        });
        VBox folderColumn = labeledColumn("Folder", folderLink);

        Runnable syncForKind = () -> {
            boolean orchestrator = isOrchestrator(viewModel);
            specialistsColumn.setVisible(orchestrator);
            specialistsColumn.setManaged(orchestrator);
            personaCaption.setText(orchestrator ? "Coordinator" : "Persona");
            syncPersonaItems(personaBox, orchestrator);

            com.multiagent.desktop.model.Conversation c = viewModel.activeConversationProperty().get();
            String ws = c == null ? null : c.getWorkspacePath();
            boolean bound = ws != null && !ws.isBlank();
            folderColumn.setVisible(bound);
            folderColumn.setManaged(bound);
            if (bound) {
                java.nio.file.Path p = java.nio.file.Path.of(ws);
                folderLink.setText(p.getFileName() != null ? p.getFileName().toString() : ws);
                javafx.scene.control.Tooltip tip = new javafx.scene.control.Tooltip(
                        ws + "\n(files and commands act here)");
                tip.setShowDelay(javafx.util.Duration.millis(200));
                folderLink.setTooltip(tip);
            }
        };
        viewModel.activeConversationProperty().addListener((obs, old, val) -> syncForKind.run());
        syncForKind.run();

        Label statusDot = new Label("●");
        Label statusText = new Label("Checking...");
        viewModel.healthProperty().addListener((obs, old, val) -> updateHealthLabels(statusDot, statusText, val));
        updateHealthLabels(statusDot, statusText, viewModel.healthProperty().get());

        HBox bar = new HBox(10,
                folderColumn, labeledColumn("Server", serverBox), labeledColumn("Model", modelBox),
                visionColumn, personaColumn, specialistsColumn, statusDot, statusText);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(8, 12, 8, 12));
        bar.getStyleClass().add("conversation-topbar");
        return bar;
    }

    private boolean isOrchestrator(ChatViewModel vm) {
        com.multiagent.desktop.model.Conversation c = vm.activeConversationProperty().get();
        return c != null && c.getKind() == com.multiagent.desktop.model.ConversationKind.ORCHESTRATOR;
    }

    /** Opens the bound workspace folder in the OS file manager (off the FX thread - the handler can stall). */
    private void openInFileManager(String path) {
        Thread opener = new Thread(() -> {
            try {
                java.io.File dir = new java.io.File(path);
                if (dir.isDirectory() && java.awt.Desktop.isDesktopSupported()) {
                    java.awt.Desktop.getDesktop().open(dir);
                }
            } catch (java.io.IOException | RuntimeException ignored) {
                // convenience only - the full path is in the Folder link's tooltip
            }
        }, "open-workspace-folder");
        opener.setDaemon(true);
        opener.start();
    }

    /**
     * Fills the persona box: every persona for an orchestrator chat (the box picks the
     * coordinator, and "orchestrator" is the sensible default there); every persona *except*
     * "orchestrator" for a normal chat, where that persona's "I coordinate specialists" prompt
     * is just misleading. Preserves the current selection, falling back to the resolved one.
     */
    private void syncPersonaItems(ComboBox<Persona> personaBox, boolean orchestrator) {
        Persona keep = personaBox.getValue() != null ? personaBox.getValue()
                : viewModel.activePersonaProperty().get();
        List<Persona> items = viewModel.personas().stream()
                .filter(p -> orchestrator || !"orchestrator".equals(p.getId()))
                .toList();
        syncingPersonaBox = true;
        try {
            personaBox.getItems().setAll(items);
            if (keep != null && items.contains(keep)) {
                personaBox.setValue(keep);
            } else if (!items.isEmpty()) {
                // keep not selectable in this kind (e.g. a plain chat once pinned to "orchestrator"):
                // show the first item; the real pin resolves when the user picks one.
                personaBox.setValue(items.get(0));
            }
        } finally {
            syncingPersonaBox = false;
        }
    }

    /** A small caption above a dropdown, so the topbar reads as "Server / Model / Persona" instead of three unlabeled boxes. */
    private VBox labeledColumn(String title, javafx.scene.Node control) {
        Label caption = new Label(title);
        caption.getStyleClass().add("topbar-field-label");
        VBox column = new VBox(2, caption, control);
        return column;
    }

    private void syncVisionItems(ComboBox<String> visionBox, List<ModelInfo> models) {
        String current = visionBox.getValue();
        List<String> items = new java.util.ArrayList<>();
        items.add(""); // "(server default)"
        models.forEach(m -> items.add(m.id()));
        visionBox.getItems().setAll(items);
        visionBox.setValue(current == null ? "" : current);
    }

    private void syncModelItems(ComboBox<String> modelBox, List<ModelInfo> models) {
        String current = modelBox.getValue();
        modelBox.getItems().setAll(models.stream().map(ModelInfo::id).toList());
        if (current != null) {
            modelBox.setValue(current);
        }
    }

    private void updateHealthLabels(Label dot, Label text, HealthStatus status) {
        if (status == null) {
            dot.setTextFill(javafx.scene.paint.Color.GRAY);
            text.setText("Checking...");
            return;
        }
        dot.setTextFill(status.ok() ? javafx.scene.paint.Color.SEAGREEN : javafx.scene.paint.Color.CRIMSON);
        text.setText(status.message());
    }
}
