package com.multiagent.desktop.ui.components;

import com.multiagent.desktop.ui.viewmodel.ChatViewModel;

import javafx.beans.binding.Bindings;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.io.File;
import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Text input + Send/Stop, mirroring Composer.tsx: Enter sends, Shift+Enter inserts a
 * newline, the button toggles to Stop (calling cancelStreaming) while streaming. Also
 * carries the one-file-at-a-time text-attachment feature - see attach()/buildMessageWith
 * Attachment(): an attached file is folded into the same plain-text message the composer
 * always sent, as a ```filename\ncontent``` fence, so it renders/downloads/copies through
 * ChatThread's existing codeBox() with no new rendering path, and reaches ChatService/
 * ConversationStore as ordinary message content with no schema change at all.
 */
public class Composer extends VBox {
    /** Bigger than WorkspaceService's per-turn agent read cap (this is one explicit user action, not a tool call), but still small enough not to blow a small local model's context on its own. */
    private static final long MAX_ATTACHMENT_BYTES = 200_000;

    private File pendingAttachment;
    private String pendingAttachmentContent;

    public Composer(ChatViewModel viewModel) {
        setSpacing(6);
        setPadding(new Insets(10));
        getStyleClass().add("composer");

        TextArea input = new TextArea();
        input.setWrapText(true);
        input.setPrefRowCount(3);
        input.setPromptText("Message... (Enter to send, Shift+Enter for a new line)");

        Label error = new Label();
        error.getStyleClass().add("error-banner");
        error.visibleProperty().bind(viewModel.errorMessageProperty().isNotEmpty());
        error.managedProperty().bind(error.visibleProperty());
        error.textProperty().bind(viewModel.errorMessageProperty());

        Label attachError = new Label();
        attachError.getStyleClass().add("error-banner");
        attachError.setVisible(false);
        attachError.setManaged(false);

        Label attachmentLabel = new Label();
        Button removeAttachmentButton = new Button("✕");
        HBox attachmentChip = new HBox(6, attachmentLabel, removeAttachmentButton);
        attachmentChip.setAlignment(Pos.CENTER_LEFT);
        attachmentChip.setVisible(false);
        attachmentChip.setManaged(false);
        removeAttachmentButton.setOnAction(e -> clearAttachment(attachmentChip));

        Button attachButton = new Button("Attach");
        attachButton.setOnAction(e -> {
            Window window = attachButton.getScene() != null ? attachButton.getScene().getWindow() : null;
            attach(window, attachError, attachmentLabel, attachmentChip);
        });

        Button sendButton = new Button("Send");
        sendButton.setDefaultButton(true);
        sendButton.textProperty().bind(javafx.beans.binding.Bindings
                .when(viewModel.streamingProperty()).then("Stop").otherwise("Send"));
        // Greyed out when there's no model to send to - streaming always stays enabled
        // though, since generation already started with whatever model it used, and this
        // button doubles as Stop while it's in flight.
        sendButton.disableProperty().bind(Bindings.createBooleanBinding(
                () -> !viewModel.streamingProperty().get()
                        && (viewModel.activeModelProperty().get() == null
                        || viewModel.activeModelProperty().get().isBlank()),
                viewModel.streamingProperty(), viewModel.activeModelProperty()));

        Runnable send = () -> {
            if (viewModel.streamingProperty().get()) {
                viewModel.cancelStreaming();
                return;
            }
            String text = input.getText();
            boolean hasAttachment = pendingAttachment != null;
            if ((text == null || text.isBlank()) && !hasAttachment) {
                return;
            }
            String combined = buildMessageWithAttachment(text,
                    hasAttachment ? pendingAttachment.getName() : null,
                    hasAttachment ? pendingAttachmentContent : null);
            // Only clear the composer (and any pending attachment) once the send actually
            // goes through - if it's rejected (e.g. no model selected), the user's draft
            // must not be wiped out from under them just because the error banner also
            // updated.
            if (viewModel.sendMessage(combined)) {
                input.clear();
                clearAttachment(attachmentChip);
            }
        };

        sendButton.setOnAction(e -> send.run());
        input.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ENTER && !event.isShiftDown()) {
                event.consume();
                send.run();
            }
        });

        HBox row = new HBox(8, input, attachButton, sendButton);
        HBox.setHgrow(input, Priority.ALWAYS);
        BorderPane.setMargin(row, new Insets(0));

        getChildren().addAll(error, attachError, attachmentChip, row);
    }

    /**
     * Reads the picked file (size-checked before reading, so a huge file is rejected
     * without ever loading it) and stashes it as the pending attachment, or shows a clear
     * inline error - never a popup - for the two ways this can fail: too large, or not
     * valid UTF-8 text (the "binary files are harder, later" boundary from the feature
     * request). Picking a new file while one is already pending silently replaces it,
     * matching the one-attachment-at-a-time design.
     */
    private void attach(Window window, Label attachError, Label attachmentLabel, HBox attachmentChip) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Attach file");
        File file = chooser.showOpenDialog(window);
        if (file == null) {
            return;
        }
        try {
            long size = Files.size(file.toPath());
            if (size > MAX_ATTACHMENT_BYTES) {
                showAttachError(attachError, "\"" + file.getName() + "\" is too large to attach ("
                        + size + " bytes). Max is " + MAX_ATTACHMENT_BYTES + " bytes.");
                return;
            }
            String content = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            pendingAttachment = file;
            pendingAttachmentContent = content;
            attachError.setVisible(false);
            attachError.setManaged(false);
            attachmentLabel.setText("📎 " + file.getName() + " (" + formatBytes(size) + ")");
            attachmentChip.setVisible(true);
            attachmentChip.setManaged(true);
        } catch (MalformedInputException e) {
            showAttachError(attachError, "\"" + file.getName()
                    + "\" looks like a binary file - only text files are supported for now.");
        } catch (IOException e) {
            showAttachError(attachError, "Could not read \"" + file.getName() + "\": " + e.getMessage());
        }
    }

    private void showAttachError(Label attachError, String message) {
        attachError.setText(message);
        attachError.setVisible(true);
        attachError.setManaged(true);
    }

    private void clearAttachment(HBox attachmentChip) {
        pendingAttachment = null;
        pendingAttachmentContent = null;
        attachmentChip.setVisible(false);
        attachmentChip.setManaged(false);
    }

    private static String formatBytes(long bytes) {
        return bytes < 1024 ? bytes + " bytes" : String.format("%.1f KB", bytes / 1024.0);
    }

    /**
     * Folds an attachment into the plain message text: "<typed text>\n\n```filename\ncontent\n```" -
     * or just the fence alone if nothing was typed, so attaching with no comment still sends
     * something sensible. Returns typedText unchanged when there's no attachment. Package-
     * private and static so it's directly unit-testable without a live JavaFX Composer.
     */
    static String buildMessageWithAttachment(String typedText, String fileName, String fileContent) {
        if (fileName == null || fileContent == null) {
            return typedText;
        }
        String fence = "```" + fileName + "\n" + fileContent + "\n```";
        String typed = typedText == null ? "" : typedText;
        return typed.isBlank() ? fence : typed + "\n\n" + fence;
    }
}
