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
        ComboBox<Persona> personaBox = new ComboBox<>(viewModel.personas());
        personaBox.setPromptText("Persona");
        personaBox.valueProperty().addListener((obs, old, val) -> {
            if (val != null) {
                viewModel.setPersona(val);
            }
        });
        viewModel.activePersonaProperty().addListener((obs, old, val) -> personaBox.setValue(val));
        if (viewModel.activePersonaProperty().get() != null) {
            personaBox.setValue(viewModel.activePersonaProperty().get());
        }

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
        modelBox.setEditable(true);
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

        Label statusDot = new Label("●");
        Label statusText = new Label("Checking...");
        viewModel.healthProperty().addListener((obs, old, val) -> updateHealthLabels(statusDot, statusText, val));
        updateHealthLabels(statusDot, statusText, viewModel.healthProperty().get());

        HBox bar = new HBox(10,
                labeledColumn("Server", serverBox), labeledColumn("Model", modelBox), labeledColumn("Persona", personaBox),
                statusDot, statusText);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(8, 12, 8, 12));
        bar.getStyleClass().add("conversation-topbar");
        return bar;
    }

    /** A small caption above a dropdown, so the topbar reads as "Server / Model / Persona" instead of three unlabeled boxes. */
    private VBox labeledColumn(String title, javafx.scene.Node control) {
        Label caption = new Label(title);
        caption.getStyleClass().add("topbar-field-label");
        VBox column = new VBox(2, caption, control);
        return column;
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
