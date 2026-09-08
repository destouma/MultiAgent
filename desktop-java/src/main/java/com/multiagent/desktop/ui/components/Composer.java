package com.multiagent.desktop.ui.components;

import com.multiagent.desktop.model.ImageAttachment;
import com.multiagent.desktop.ui.viewmodel.ChatViewModel;

import javafx.beans.binding.Bindings;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextArea;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import javax.imageio.ImageIO;

/**
 * Text input + Send/Stop, mirroring Composer.tsx: Enter sends, Shift+Enter inserts a
 * newline, the button toggles to Stop (calling cancelStreaming) while streaming. Also
 * carries the one-file-at-a-time text-attachment feature - see loadAttachment()/
 * buildMessageWithAttachment(): an attached file (via the Attach button or dropped onto
 * this composer) is folded into the same plain-text message the composer always sent, as a
 * ```filename\ncontent``` fence, so it renders/downloads/copies through ChatThread's
 * existing codeBox() with no new rendering path, and reaches ChatService/ConversationStore
 * as ordinary message content with no schema change at all.
 */
public class Composer extends VBox {
    /** Bigger than WorkspaceService's per-turn agent read cap (this is one explicit user action, not a tool call), but still small enough not to blow a small local model's context on its own. */
    private static final long MAX_ATTACHMENT_BYTES = 200_000;
    /** Images are base64-inflated into one vision request; keep in step with WorkspaceService.MAX_IMAGE_BYTES. */
    private static final long MAX_IMAGE_ATTACHMENT_BYTES = 4_000_000;
    private static final java.util.Map<String, String> IMAGE_MIME = java.util.Map.of(
            "png", "image/png", "jpg", "image/jpeg", "jpeg", "image/jpeg",
            "gif", "image/gif", "webp", "image/webp");

    private File pendingAttachment;
    private String pendingAttachmentContent;
    private ImageAttachment pendingImage;

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
        // Model-load / provider errors can be long (a 404 body echoes the whole model id).
        // Wrap to the composer width instead of truncating with an ellipsis.
        error.setWrapText(true);
        error.setMaxWidth(Double.MAX_VALUE);
        makeCopyable(error);
        error.visibleProperty().bind(viewModel.errorMessageProperty().isNotEmpty());
        error.managedProperty().bind(error.visibleProperty());
        error.textProperty().bind(viewModel.errorMessageProperty());

        Label attachError = new Label();
        attachError.getStyleClass().add("error-banner");
        attachError.setWrapText(true);
        attachError.setMaxWidth(Double.MAX_VALUE);
        makeCopyable(attachError);
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
            boolean hasTextAttachment = pendingAttachment != null;
            boolean hasImage = pendingImage != null;
            if ((text == null || text.isBlank()) && !hasTextAttachment && !hasImage) {
                return;
            }
            // Only clear the composer (and any pending attachment) once the send actually
            // goes through - if it's rejected (e.g. no model selected), the user's draft
            // must not be wiped out from under them just because the error banner also
            // updated.
            boolean sent = hasImage
                    ? viewModel.sendMessage(text, pendingImage)
                    : viewModel.sendMessage(buildMessageWithAttachment(text,
                            hasTextAttachment ? pendingAttachment.getName() : null,
                            hasTextAttachment ? pendingAttachmentContent : null));
            if (sent) {
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

        // Attach a clipboard bitmap (Snipping Tool, PrintScreen, "copy image") as an image.
        Runnable pasteImage = () -> {
            javafx.scene.image.Image img = Clipboard.getSystemClipboard().getImage();
            byte[] png = img == null ? null : encodePng(img);
            if (png == null) {
                showAttachError(attachError, "Could not read the pasted image.");
            } else if (png.length > MAX_IMAGE_ATTACHMENT_BYTES) {
                showAttachError(attachError, "Pasted image is too large ("
                        + png.length + " bytes). Max is " + MAX_IMAGE_ATTACHMENT_BYTES + " bytes.");
            } else {
                clearPending();
                pendingImage = new ImageAttachment(
                        "pasted-" + System.currentTimeMillis() + ".png", "image/png", png);
                attachError.setVisible(false);
                attachError.setManaged(false);
                attachmentLabel.setText("🖼️ " + pendingImage.name() + " (" + formatBytes(png.length) + ")");
                attachmentChip.setVisible(true);
                attachmentChip.setManaged(true);
            }
        };

        // Ctrl/Cmd+V: intercept only when the clipboard is a bitmap with no text (a
        // screenshot); a clipboard with text is left to the TextArea's normal paste.
        input.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.V && event.isShortcutDown()
                    && Clipboard.getSystemClipboard().hasImage()
                    && !Clipboard.getSystemClipboard().hasString()) {
                pasteImage.run();
                event.consume();
            }
        });

        // ...and a right-click "Paste image" entry, enabled whenever the clipboard has one
        // (this menu replaces the stock TextArea one, so the usual items are rebuilt).
        input.setContextMenu(buildInputMenu(input, pasteImage));

        HBox row = new HBox(8, input, attachButton, sendButton);
        HBox.setHgrow(input, Priority.ALWAYS);
        BorderPane.setMargin(row, new Insets(0));

        getChildren().addAll(error, attachError, attachmentChip, row);

        // Drag-and-drop is a second way in to the exact same one-attachment pipeline the
        // Attach button uses - not a separate feature. Dropping more than one file just
        // takes the first, matching the one-attachment-at-a-time design.
        setOnDragOver(event -> {
            if (event.getGestureSource() != this && event.getDragboard().hasFiles()) {
                event.acceptTransferModes(TransferMode.COPY);
            }
            event.consume();
        });
        setOnDragEntered(event -> {
            if (event.getDragboard().hasFiles()) {
                getStyleClass().add("composer-drag-over");
            }
        });
        setOnDragExited(event -> getStyleClass().remove("composer-drag-over"));
        setOnDragDropped(event -> {
            Dragboard dragboard = event.getDragboard();
            boolean accepted = dragboard.hasFiles() && !dragboard.getFiles().isEmpty();
            if (accepted) {
                loadAttachment(dragboard.getFiles().get(0), attachError, attachmentLabel, attachmentChip);
            }
            getStyleClass().remove("composer-drag-over");
            event.setDropCompleted(accepted);
            event.consume();
        });
    }

    private void attach(Window window, Label attachError, Label attachmentLabel, HBox attachmentChip) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Attach file");
        File file = chooser.showOpenDialog(window);
        if (file != null) {
            loadAttachment(file, attachError, attachmentLabel, attachmentChip);
        }
    }

    /**
     * Reads the given file (size-checked before reading, so a huge file is rejected without
     * ever loading it) and stashes it as the pending attachment, or shows a clear inline
     * error - never a popup - for the two ways this can fail: too large, or not valid UTF-8
     * text (the "binary files are harder, later" boundary from the feature request). A new
     * file - via the Attach button or a drop - silently replaces any already-pending one,
     * matching the one-attachment-at-a-time design. An image (.png/.jpg/.jpeg/.gif/.webp) is
     * kept as raw bytes for the vision pre-pass instead of being folded into the text.
     */
    private void loadAttachment(File file, Label attachError, Label attachmentLabel, HBox attachmentChip) {
        String ext = extensionOf(file.getName());
        try {
            long size = Files.size(file.toPath());
            if (IMAGE_MIME.containsKey(ext)) {
                if (size > MAX_IMAGE_ATTACHMENT_BYTES) {
                    showAttachError(attachError, "\"" + file.getName() + "\" is too large to attach ("
                            + size + " bytes). Max image is " + MAX_IMAGE_ATTACHMENT_BYTES + " bytes.");
                    return;
                }
                byte[] bytes = Files.readAllBytes(file.toPath());
                clearPending();
                pendingImage = new ImageAttachment(file.getName(), IMAGE_MIME.get(ext), bytes);
                attachError.setVisible(false);
                attachError.setManaged(false);
                attachmentLabel.setText("🖼️ " + file.getName() + " (" + formatBytes(size) + ")");
                attachmentChip.setVisible(true);
                attachmentChip.setManaged(true);
                return;
            }
            if (size > MAX_ATTACHMENT_BYTES) {
                showAttachError(attachError, "\"" + file.getName() + "\" is too large to attach ("
                        + size + " bytes). Max is " + MAX_ATTACHMENT_BYTES + " bytes.");
                return;
            }
            String content = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            clearPending();
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
        clearPending();
        attachmentChip.setVisible(false);
        attachmentChip.setManaged(false);
    }

    private void clearPending() {
        pendingAttachment = null;
        pendingAttachmentContent = null;
        pendingImage = null;
    }

    private static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase();
    }

    /**
     * A replacement for the stock TextArea context menu: the usual edit items plus a
     * "Paste image" entry (the built-in Paste only handles text). Disabled states are
     * recomputed each time the menu opens.
     */
    private static ContextMenu buildInputMenu(TextArea input, Runnable pasteImage) {
        MenuItem undo = new MenuItem("Undo");
        undo.setOnAction(e -> input.undo());
        MenuItem redo = new MenuItem("Redo");
        redo.setOnAction(e -> input.redo());
        MenuItem cut = new MenuItem("Cut");
        cut.setOnAction(e -> input.cut());
        MenuItem copy = new MenuItem("Copy");
        copy.setOnAction(e -> input.copy());
        MenuItem paste = new MenuItem("Paste");
        paste.setOnAction(e -> input.paste());
        MenuItem pasteImg = new MenuItem("Paste image");
        pasteImg.setOnAction(e -> pasteImage.run());
        MenuItem delete = new MenuItem("Delete");
        delete.setOnAction(e -> input.replaceSelection(""));
        MenuItem selectAll = new MenuItem("Select All");
        selectAll.setOnAction(e -> input.selectAll());

        ContextMenu menu = new ContextMenu(undo, redo, new SeparatorMenuItem(),
                cut, copy, paste, pasteImg, delete, new SeparatorMenuItem(), selectAll);
        menu.setOnShowing(e -> {
            boolean hasSelection = input.getSelection().getLength() > 0;
            Clipboard clipboard = Clipboard.getSystemClipboard();
            undo.setDisable(!input.isUndoable());
            redo.setDisable(!input.isRedoable());
            cut.setDisable(!hasSelection);
            copy.setDisable(!hasSelection);
            delete.setDisable(!hasSelection);
            paste.setDisable(!clipboard.hasString());
            pasteImg.setDisable(!clipboard.hasImage());
            selectAll.setDisable(input.getText().isEmpty());
        });
        return menu;
    }

    /**
     * Encodes a JavaFX clipboard image to PNG bytes without the javafx-swing module - copy
     * the pixels into a BufferedImage and hand that to ImageIO (java.desktop, already on the
     * classpath). Output is opaque RGB (any alpha is composited onto white): smaller, and
     * CLIP/mmproj vision preprocessors are less fussy about it than RGBA. Returns null on
     * any failure.
     */
    private static byte[] encodePng(javafx.scene.image.Image image) {
        try {
            int w = (int) Math.round(image.getWidth());
            int h = (int) Math.round(image.getHeight());
            if (w <= 0 || h <= 0) {
                return null;
            }
            javafx.scene.image.PixelReader reader = image.getPixelReader();
            if (reader == null) {
                return null;
            }
            BufferedImage buffered = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int argb = reader.getArgb(x, y);
                    int a = (argb >>> 24) & 0xFF;
                    int r = (argb >> 16) & 0xFF;
                    int g = (argb >> 8) & 0xFF;
                    int b = argb & 0xFF;
                    if (a < 255) {
                        r = (r * a + 255 * (255 - a)) / 255;
                        g = (g * a + 255 * (255 - a)) / 255;
                        b = (b * a + 255 * (255 - a)) / 255;
                    }
                    buffered.setRGB(x, y, (r << 16) | (g << 8) | b);
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            return ImageIO.write(buffered, "png", out) ? out.toByteArray() : null;
        } catch (RuntimeException | IOException e) {
            return null;
        }
    }

    private static String formatBytes(long bytes) {
        return bytes < 1024 ? bytes + " bytes" : String.format("%.1f KB", bytes / 1024.0);
    }

    /**
     * Makes an error banner {@link Label} copyable: left-click or the right-click "Copy error"
     * item puts the current text on the system clipboard. A plain Label can't be selected, and
     * these messages (model-load 404s, provider errors) are exactly what a user wants to paste
     * into a bug report.
     */
    private static void copyToClipboard(String text) {
        ClipboardContent content = new ClipboardContent();
        content.putString(text == null ? "" : text);
        Clipboard.getSystemClipboard().setContent(content);
    }

    private static void makeCopyable(Label banner) {
        banner.setCursor(Cursor.HAND);
        banner.setTooltip(new Tooltip("Click to copy"));

        MenuItem copyItem = new MenuItem("Copy error");
        copyItem.setOnAction(e -> copyToClipboard(banner.getText()));
        banner.setContextMenu(new ContextMenu(copyItem));

        banner.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY) {
                copyToClipboard(banner.getText());
            }
        });
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
