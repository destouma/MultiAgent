package com.multiagent.desktop.ui.components;

import com.multiagent.desktop.model.ChatMessage;
import com.multiagent.desktop.model.MessageRole;
import com.multiagent.desktop.ui.viewmodel.ChatViewModel;
import com.multiagent.desktop.ui.viewmodel.WorkspaceOpEntry;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

/**
 * Renders messages[] plus a synthetic streaming bubble while streaming is true, mirroring
 * ChatThread.tsx. No markdown/syntax-highlighting library is needed here for the same
 * reason it isn't in the Electron app: MessageContent.tsx only does plain text + a
 * triple-backtick code-fence split with no highlighting, so a TextFlow with monospace runs
 * for fenced segments is an equivalent, not a simplification.
 */
public class ChatThread extends ScrollPane {
    private final VBox column = new VBox(10);
    private final ChatViewModel viewModel;

    public ChatThread(ChatViewModel viewModel) {
        this.viewModel = viewModel;
        setFitToWidth(true);
        column.setPadding(new Insets(12));
        setContent(column);

        viewModel.messages().addListener((javafx.collections.ListChangeListener<ChatMessage>) c -> render());
        viewModel.streamingContentProperty().addListener((obs, old, val) -> render());
        viewModel.streamingProperty().addListener((obs, old, val) -> render());
        viewModel.workspaceOps().addListener((javafx.collections.ListChangeListener<WorkspaceOpEntry>) c -> render());
        viewModel.activeConversationProperty().addListener((obs, old, val) -> render());
        render();
    }

    private void render() {
        column.getChildren().clear();
        for (ChatMessage message : viewModel.messages()) {
            column.getChildren().add(bubble(message.getRole(), message.getContent()));
        }
        // Left visible after streaming finishes, not just during it - that's what makes
        // View diff/Revert usable once the turn is done, mirroring the Electron app.
        for (WorkspaceOpEntry op : viewModel.workspaceOps()) {
            column.getChildren().add(workspaceOpLine(op));
        }
        if (viewModel.streamingProperty().get() && !viewModel.streamingContentProperty().get().isEmpty()) {
            column.getChildren().add(bubble(MessageRole.ASSISTANT, viewModel.streamingContentProperty().get()));
        }
        // Auto-scroll to bottom, mirroring ChatThread.tsx's scroll-on-update effect.
        javafx.application.Platform.runLater(() -> setVvalue(1.0));
    }

    /**
     * One tool-activity line (e.g. "write_file src/Foo.java - ok"), mirroring the
     * workspace-ops feed in ChatThread.tsx. A successful write_file/delete_file carries a
     * checkpointId, which is what earns it View diff/Revert buttons.
     */
    private HBox workspaceOpLine(WorkspaceOpEntry op) {
        String icon = switch (op.status()) {
            case "ok" -> "✓";
            case "error" -> "✗";
            default -> "…";
        };
        Label label = new Label(icon + " " + op.op() + " " + op.path()
                + (op.detail() != null && !op.detail().isBlank() ? " - " + op.detail() : ""));
        label.getStyleClass().add("workspace-op-" + op.status());
        label.setWrapText(true);
        HBox.setHgrow(label, Priority.ALWAYS);

        HBox row = new HBox(6, label);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(2, 12, 2, 12));

        if ("ok".equals(op.status()) && op.checkpointId() != null) {
            Button viewDiffButton = new Button("View diff");
            viewDiffButton.setOnAction(e -> showDiff(op.checkpointId()));

            Button revertButton = new Button("Revert");
            revertButton.setOnAction(e -> revert(op.checkpointId()));

            row.getChildren().addAll(viewDiffButton, revertButton);
        }
        return row;
    }

    private void showDiff(String checkpointId) {
        try {
            DiffDialog dialog = new DiffDialog(viewModel.diffCheckpoint(checkpointId));
            dialog.initOwner(getScene() != null ? getScene().getWindow() : null);
            dialog.showAndWait();
        } catch (RuntimeException e) {
            showError(e.getMessage());
        }
    }

    private void revert(String checkpointId) {
        try {
            viewModel.revertCheckpoint(checkpointId);
        } catch (RuntimeException e) {
            showError(e.getMessage());
        }
    }

    private void showError(String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR, message);
        alert.initOwner(getScene() != null ? getScene().getWindow() : null);
        alert.showAndWait();
    }

    private HBox bubble(MessageRole role, String content) {
        boolean fromUser = role == MessageRole.USER;

        TextFlow flow = buildContentFlow(content == null ? "" : content);
        flow.setMaxWidth(560);
        flow.setPadding(new Insets(8, 12, 8, 12));
        flow.getStyleClass().add(fromUser ? "bubble-user" : "bubble-assistant");

        HBox row = new HBox(flow);
        row.setAlignment(fromUser ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);
        HBox.setHgrow(row, Priority.ALWAYS);
        return row;
    }

    /** Splits on ```fenced``` blocks the same way MessageContent.tsx does, no other markdown. */
    private TextFlow buildContentFlow(String content) {
        TextFlow flow = new TextFlow();
        int cursor = 0;
        while (cursor < content.length()) {
            int fenceStart = content.indexOf("```", cursor);
            if (fenceStart < 0) {
                flow.getChildren().add(new Text(content.substring(cursor)));
                break;
            }
            if (fenceStart > cursor) {
                flow.getChildren().add(new Text(content.substring(cursor, fenceStart)));
            }
            int fenceEnd = content.indexOf("```", fenceStart + 3);
            if (fenceEnd < 0) {
                flow.getChildren().add(new Text(content.substring(fenceStart)));
                break;
            }
            String code = content.substring(fenceStart + 3, fenceEnd);
            int firstNewline = code.indexOf('\n');
            String body = firstNewline >= 0 ? code.substring(firstNewline + 1) : code;
            Text codeText = new Text(body);
            codeText.getStyleClass().add("code-block");
            flow.getChildren().add(codeText);
            cursor = fenceEnd + 3;
        }
        if (flow.getChildren().isEmpty()) {
            flow.getChildren().add(new Text(""));
        }
        return flow;
    }
}
