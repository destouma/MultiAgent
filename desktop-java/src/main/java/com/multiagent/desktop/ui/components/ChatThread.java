package com.multiagent.desktop.ui.components;

import com.multiagent.desktop.model.ChatMessage;
import com.multiagent.desktop.model.Conversation;
import com.multiagent.desktop.model.ConversationKind;
import com.multiagent.desktop.model.MessageRole;
import com.multiagent.desktop.model.Persona;
import com.multiagent.desktop.ui.viewmodel.ChatViewModel;
import com.multiagent.desktop.ui.viewmodel.WorkspaceOpEntry;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import javafx.util.Duration;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

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

    // A single reused "assistant is working" bubble, shown while a turn is in flight but
    // nothing has streamed yet (and no tool call is currently running). The Timeline just
    // re-labels it every ~450ms - animated dots plus an elapsed-seconds counter - so a long
    // local inference reads as progress rather than a frozen window.
    private final Label thinkingLabel = new Label();
    private final HBox thinkingRow;
    private final Timeline thinkingAnimation;
    private int dotTick;

    /** Id of the message currently showing its edit textarea instead of normal content, or null. An instance field (not local UI state) because render() rebuilds the whole column from scratch on every relevant change, so this has to survive that rebuild the same way dotTick/thinkingAnimation already do. Only one message editable at a time - simpler than the alternative and edit always truncates everything after it anyway, so editing two at once wouldn't make sense regardless. */
    private String editingMessageId;

    public ChatThread(ChatViewModel viewModel) {
        this.viewModel = viewModel;
        setFitToWidth(true);
        column.setPadding(new Insets(12));
        setContent(column);

        thinkingLabel.getStyleClass().addAll("bubble-assistant", "thinking-text");
        thinkingLabel.setPadding(new Insets(8, 12, 8, 12));
        thinkingRow = new HBox(thinkingLabel);
        thinkingRow.setAlignment(Pos.CENTER_LEFT);
        thinkingAnimation = new Timeline(new KeyFrame(Duration.millis(450), e -> {
            dotTick++;
            updateThinkingLabel();
        }));
        thinkingAnimation.setCycleCount(Animation.INDEFINITE);

        viewModel.messages().addListener((javafx.collections.ListChangeListener<ChatMessage>) c -> render());
        viewModel.streamingContentProperty().addListener((obs, old, val) -> render());
        viewModel.streamingProperty().addListener((obs, old, val) -> render());
        viewModel.modelStatusProperty().addListener((obs, old, val) -> render());
        viewModel.generationStartedAtProperty().addListener((obs, old, val) -> render());
        viewModel.workspaceOps().addListener((javafx.collections.ListChangeListener<WorkspaceOpEntry>) c -> render());
        viewModel.activeConversationProperty().addListener((obs, old, val) -> {
            editingMessageId = null;
            render();
        });
        render();
    }

    private void render() {
        column.getChildren().clear();
        var messages = viewModel.messages();
        for (int i = 0; i < messages.size(); i++) {
            column.getChildren().add(messageBubble(messages.get(i), i == messages.size() - 1));
        }
        // Left visible after streaming finishes, not just during it - that's what makes
        // View diff/Revert usable once the turn is done, mirroring the Electron app.
        for (WorkspaceOpEntry op : viewModel.workspaceOps()) {
            column.getChildren().add(workspaceOpLine(op));
        }
        boolean streaming = viewModel.streamingProperty().get();
        boolean nothingStreamedYet = viewModel.streamingContentProperty().get().isEmpty();
        if (streaming && !nothingStreamedYet) {
            // Not a persisted ChatMessage yet (still streaming in), so no id to edit/
            // regenerate against - plainBubble skips the trigger row entirely.
            column.getChildren().add(plainBubble(MessageRole.ASSISTANT, viewModel.streamingContentProperty().get()));
        }
        // Show the "working" bubble in the gaps with no other signal: before the first token,
        // and (for workspace chats) before the first tool call and during the final synthesis.
        // While a tool is actually running, its own "…" op line is the indicator instead.
        if (streaming && nothingStreamedYet && !anyOpRunning()) {
            updateThinkingLabel();
            column.getChildren().add(thinkingRow);
            if (thinkingAnimation.getStatus() != Animation.Status.RUNNING) {
                dotTick = 0;
                thinkingAnimation.playFromStart();
            }
        } else if (thinkingAnimation.getStatus() == Animation.Status.RUNNING) {
            thinkingAnimation.stop();
        }
        // Auto-scroll to bottom, mirroring ChatThread.tsx's scroll-on-update effect.
        javafx.application.Platform.runLater(() -> setVvalue(1.0));
    }

    private boolean anyOpRunning() {
        for (WorkspaceOpEntry op : viewModel.workspaceOps()) {
            if ("running".equals(op.status())) {
                return true;
            }
        }
        return false;
    }

    private void updateThinkingLabel() {
        String base = viewModel.modelStatusProperty().get();
        base = (base == null || base.isBlank()) ? "Thinking" : base.strip();
        while (base.endsWith(".")) {
            base = base.substring(0, base.length() - 1);
        }
        String dots = ".".repeat(dotTick % 4);
        long startedAt = viewModel.generationStartedAtProperty().get();
        String elapsed = startedAt > 0
                ? "   " + Math.max(0, (System.currentTimeMillis() - startedAt) / 1000) + "s"
                : "";
        thinkingLabel.setText(base + dots + elapsed);
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

    /** The synthetic in-progress streaming bubble - no persisted ChatMessage/id exists yet, so no Edit/Regenerate trigger row makes sense here. */
    private HBox plainBubble(MessageRole role, String content) {
        boolean fromUser = role == MessageRole.USER;
        VBox container = styledContainer(content, fromUser);
        // Match what the persisted synthesis bubble will get once streaming ends, so the
        // coordinator's accent doesn't "pop in" only after the last token lands.
        String accent = isOrchestratorChat() ? conversationPersonaColor() : null;
        HBox row = new HBox(accent == null ? container : withAccent(container, accent, fromUser));
        row.setAlignment(fromUser ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);
        HBox.setHgrow(row, Priority.ALWAYS);
        return row;
    }

    private VBox styledContainer(String content, boolean fromUser) {
        VBox container = buildMessageContent(content == null ? "" : content);
        container.setMaxWidth(560);
        container.setPadding(new Insets(8, 12, 8, 12));
        container.getStyleClass().add(fromUser ? "bubble-user" : "bubble-assistant");
        return container;
    }

    /**
     * Wraps a bubble with a 3px persona-coloured bar on its outer edge (right for a
     * right-aligned user bubble, left for an assistant one) - a sibling node rather than a
     * CSS border so it never fights a theme's own bubble border (Terminal draws one).
     */
    private HBox withAccent(VBox bubble, String colorHex, boolean fromUser) {
        Region bar = new Region();
        bar.setMinWidth(3);
        bar.setPrefWidth(3);
        bar.setMaxWidth(3);
        bar.setMaxHeight(Double.MAX_VALUE); // HBox fills height, so the bar matches the bubble
        bar.setStyle("-fx-background-color: " + colorHex + "; -fx-background-radius: 2;");
        HBox box = fromUser ? new HBox(6, bubble, bar) : new HBox(6, bar, bubble);
        box.setAlignment(Pos.CENTER_LEFT);
        box.setMaxWidth(Region.USE_PREF_SIZE);
        return box;
    }

    /** Small persona-name caption above an orchestrator specialist's reply, tinted to match its accent. */
    private Label personaHeader(String personaId, String colorHex) {
        String name = personaNameById(personaId);
        if (name == null) {
            return null;
        }
        Label label = new Label(name);
        label.setPadding(new Insets(0, 0, 2, 9));
        label.setStyle("-fx-font-size: 11px; -fx-font-weight: bold;"
                + (colorHex != null ? " -fx-text-fill: " + colorHex + ";" : ""));
        return label;
    }

    private boolean isOrchestratorChat() {
        Conversation c = viewModel.activeConversationProperty().get();
        return c != null && c.getKind() == ConversationKind.ORCHESTRATOR;
    }

    /** The colour of the conversation's resolved (coordinator) persona, or null. */
    private String conversationPersonaColor() {
        Persona persona = viewModel.activePersonaProperty().get();
        return persona == null ? null : sanitizeColor(persona.getColor());
    }

    private String personaColorById(String personaId) {
        if (personaId == null) {
            return null;
        }
        for (Persona persona : viewModel.personas()) {
            if (persona.getId().equals(personaId)) {
                return sanitizeColor(persona.getColor());
            }
        }
        return null;
    }

    private String personaNameById(String personaId) {
        for (Persona persona : viewModel.personas()) {
            if (persona.getId().equals(personaId)) {
                return persona.getName();
            }
        }
        return null;
    }

    /** Only a plain #hex literal reaches an inline -fx- style, so a hand-edited persona JSON can't inject CSS. */
    private static String sanitizeColor(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.matches("#([0-9a-fA-F]{3,4}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})") ? trimmed : null;
    }

    /**
     * A real, persisted message: normal bubble content, or - if this is the message
     * currently being edited (editingMessageId) - the edit textarea in its place, plus a
     * trigger row below the bubble (mirroring MessageBubble.tsx's placement outside/below
     * the colored bubble, not inside it): "Edit" on any user message when idle, "Regenerate"
     * only on the last message when it's from the assistant and idle - matching
     * ChatThread.tsx's editable/canRegenerate gating exactly (minus generatingImage, which
     * has no equivalent here since image generation isn't ported).
     */
    private HBox messageBubble(ChatMessage message, boolean isLast) {
        boolean fromUser = message.getRole() == MessageRole.USER;
        boolean editingThis = message.getId().equals(editingMessageId);
        boolean streaming = viewModel.streamingProperty().get();

        VBox wrapper = new VBox(4);
        wrapper.setMaxWidth(560);

        if (editingThis) {
            wrapper.getChildren().add(editBox(message));
        } else {
            VBox container = styledContainer(message.getContent(), fromUser);

            // A thin persona-coloured bar hugging the outer edge of the bubble: for a user
            // message it's the conversation's (coordinator) persona; for an assistant message
            // in an orchestrator thread it's that reply's own specialist persona, and the
            // specialist's name sits above the bubble in the same colour so interleaved
            // specialist notes are tellable apart at a glance.
            boolean orchestrator = isOrchestratorChat();
            String accent = fromUser
                    ? conversationPersonaColor()
                    : (orchestrator ? personaColorById(message.getPersonaId()) : null);
            if (!fromUser && orchestrator && message.getPersonaId() != null) {
                Label who = personaHeader(message.getPersonaId(), accent);
                if (who != null) {
                    wrapper.getChildren().add(who);
                }
            }
            wrapper.getChildren().add(accent == null ? container : withAccent(container, accent, fromUser));

            boolean editable = fromUser && !streaming;
            boolean canRegenerate = isLast && !fromUser && !streaming;
            if (editable || canRegenerate) {
                HBox triggerRow = new HBox(8);
                triggerRow.setAlignment(fromUser ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);
                if (editable) {
                    Button editTrigger = new Button("✎ Edit");
                    editTrigger.setOnAction(e -> {
                        editingMessageId = message.getId();
                        render();
                    });
                    triggerRow.getChildren().add(editTrigger);
                }
                if (canRegenerate) {
                    Button regenTrigger = new Button("↻ Regenerate");
                    regenTrigger.setOnAction(e -> regenerate());
                    triggerRow.getChildren().add(regenTrigger);
                }
                wrapper.getChildren().add(triggerRow);
            }
        }

        HBox row = new HBox(wrapper);
        row.setAlignment(fromUser ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);
        HBox.setHgrow(row, Priority.ALWAYS);
        return row;
    }

    /** Edit-in-place: a textarea pre-filled with the message's current content, Cancel/Save & resend, Enter-to-save (Shift+Enter for a newline), Escape-to-cancel - mirroring MessageBubble.tsx's edit mode. */
    private VBox editBox(ChatMessage message) {
        TextArea draftArea = new TextArea(message.getContent());
        draftArea.setWrapText(true);
        draftArea.setPrefRowCount(4);

        Button cancel = new Button("Cancel");
        cancel.setOnAction(e -> {
            editingMessageId = null;
            render();
        });

        Button saveAndResend = new Button("Save & resend");
        saveAndResend.disableProperty().bind(draftArea.textProperty().isEmpty());
        Runnable save = () -> {
            String trimmed = draftArea.getText() == null ? "" : draftArea.getText().trim();
            editingMessageId = null;
            if (!trimmed.isEmpty() && !trimmed.equals(message.getContent())) {
                viewModel.editAndResend(message.getId(), trimmed);
            } else {
                render();
            }
        };
        saveAndResend.setOnAction(e -> save.run());

        draftArea.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ENTER && !event.isShiftDown()) {
                event.consume();
                save.run();
            } else if (event.getCode() == KeyCode.ESCAPE) {
                cancel.fire();
            }
        });

        HBox actions = new HBox(8, cancel, saveAndResend);
        actions.setAlignment(Pos.CENTER_RIGHT);

        VBox box = new VBox(6, draftArea, actions);
        box.setPadding(new Insets(8, 12, 8, 12));
        return box;
    }

    /** Truncates from the last user message (inclusive) onward and resends its original content unchanged - a fresh generation, not a branch/version history, matching ChatThread.tsx's onRegenerate. */
    private void regenerate() {
        var messages = viewModel.messages();
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage candidate = messages.get(i);
            if (candidate.getRole() == MessageRole.USER) {
                viewModel.editAndResend(candidate.getId(), candidate.getContent());
                return;
            }
        }
    }

    /**
     * Splits on ```fenced``` blocks the same way MessageContent.tsx does, no other markdown -
     * except each fenced block now becomes its own dedicated "code box" (codeBox()) instead
     * of an inline monospace run, so it can carry a language label and a Download button,
     * rather than one TextFlow mixing prose and code runs together.
     */
    private VBox buildMessageContent(String content) {
        VBox container = new VBox(8);
        int cursor = 0;
        while (cursor < content.length()) {
            int fenceStart = content.indexOf("```", cursor);
            if (fenceStart < 0) {
                addProse(container, content.substring(cursor));
                cursor = content.length();
                break;
            }
            if (fenceStart > cursor) {
                addProse(container, content.substring(cursor, fenceStart));
            }
            int fenceEnd = content.indexOf("```", fenceStart + 3);
            if (fenceEnd < 0) {
                addProse(container, content.substring(fenceStart));
                cursor = content.length();
                break;
            }
            String fence = content.substring(fenceStart + 3, fenceEnd);
            int firstNewline = fence.indexOf('\n');
            String language = firstNewline >= 0 ? fence.substring(0, firstNewline).trim() : "";
            String code = firstNewline >= 0 ? fence.substring(firstNewline + 1) : fence;
            container.getChildren().add(codeBox(language, code));
            cursor = fenceEnd + 3;
        }
        if (container.getChildren().isEmpty()) {
            addProse(container, "");
        }
        return container;
    }

    private void addProse(VBox container, String text) {
        if (text.isEmpty() && !container.getChildren().isEmpty()) {
            return;
        }
        // "bubble-text" is added explicitly here rather than relying on Text's own default
        // style class (there isn't a reliable one to hook a ".bubble-user .text"-style
        // selector to), so .bubble-user/.bubble-assistant's text-color rule is guaranteed to
        // actually match instead of silently falling back to Text's own default fill - which
        // is exactly what made a message unreadable against a bubble color that default
        // happened not to contrast with (worst case: invisible, in the black-on-black
        // Terminal theme).
        Text prose = new Text(text);
        prose.getStyleClass().add("bubble-text");
        container.getChildren().add(new TextFlow(prose));
    }

    /**
     * A fenced code block's own dedicated box, styled via the "code-box"/"code-box-header"/
     * "code-box-lang"/"code-box-body"/"code-block" classes so it visually reads as "this is
     * code" separate from the surrounding prose - each theme tailors its own palette for
     * this (see styles.css/theme-dark.css/theme-terminal.css) rather than one look forced
     * onto every theme, since what pops against a light background clashes against a
     * default-dark one, and Terminal wants its own black+green look, not a foreign one.
     * Carries a language tag, a Copy button, and a Download button to save the snippet as a
     * local file - both shown consistently in every chat, workspace-bound or not: a folder
     * binding doesn't guarantee this particular snippet was ever actually written there (the
     * model may only have shown it, or a workspace write may have been declined at the
     * approval gate), so gating either button on that was more confusing than helpful.
     */
    // Past this many lines (or this many characters, for a single very long line - a
    // minified JSON blob has no line breaks to collapse by) a code box starts collapsed,
    // so one large file (an attachment, or something a model wrote out in full) doesn't
    // turn the whole thread into one long scroll of a single message.
    private static final int COLLAPSE_LINE_THRESHOLD = 25;
    private static final int COLLAPSE_PREVIEW_LINES = 15;
    private static final int COLLAPSE_CHAR_THRESHOLD = 4000;

    private VBox codeBox(String language, String code) {
        String trimmedCode = code.stripTrailing();

        Label langLabel = new Label(language.isBlank() ? "code" : language);
        langLabel.getStyleClass().add("code-box-lang");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button copy = new Button("Copy");
        copy.setOnAction(e -> copyToClipboard(trimmedCode, copy));
        Button download = new Button("Download");
        download.setOnAction(e -> downloadSnippet(language, trimmedCode));
        HBox header = new HBox(6, langLabel, spacer, copy, download);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("code-box-header");

        Text codeText = new Text();
        codeText.getStyleClass().add("code-block");
        TextFlow textFlow = new TextFlow(codeText);
        VBox bodyBox = new VBox(6, textFlow);
        bodyBox.getStyleClass().add("code-box-body");

        int totalLines = trimmedCode.isEmpty() ? 0 : (int) trimmedCode.lines().count();
        boolean isLong = totalLines > COLLAPSE_LINE_THRESHOLD || trimmedCode.length() > COLLAPSE_CHAR_THRESHOLD;
        if (isLong) {
            String preview = collapsedPreview(trimmedCode);
            codeText.setText(preview);
            Button toggle = new Button("Show full file (" + totalLines + " lines)");
            boolean[] expanded = {false};
            toggle.setOnAction(e -> {
                expanded[0] = !expanded[0];
                codeText.setText(expanded[0] ? trimmedCode : preview);
                toggle.setText(expanded[0] ? "Show less" : "Show full file (" + totalLines + " lines)");
            });
            bodyBox.getChildren().add(toggle);
        } else {
            codeText.setText(trimmedCode);
        }

        VBox box = new VBox(header, bodyBox);
        box.getStyleClass().add("code-box");
        return box;
    }

    /** First COLLAPSE_PREVIEW_LINES lines, further capped by character count in case even those few lines are enormous (one giant minified line, say). */
    private String collapsedPreview(String code) {
        String[] lines = code.split("\n", -1);
        int previewLineCount = Math.min(COLLAPSE_PREVIEW_LINES, lines.length);
        String preview = String.join("\n", java.util.Arrays.copyOfRange(lines, 0, previewLineCount));
        return preview.length() > COLLAPSE_CHAR_THRESHOLD ? preview.substring(0, COLLAPSE_CHAR_THRESHOLD) : preview;
    }

    /** Puts the snippet on the system clipboard and flashes the button's label as brief feedback, then reverts it. */
    private void copyToClipboard(String code, Button sourceButton) {
        ClipboardContent content = new ClipboardContent();
        content.putString(code.stripTrailing());
        Clipboard.getSystemClipboard().setContent(content);

        String original = sourceButton.getText();
        sourceButton.setText("Copied!");
        PauseTransition reset = new PauseTransition(Duration.seconds(1.2));
        reset.setOnFinished(e -> sourceButton.setText(original));
        reset.play();
    }

    private void downloadSnippet(String language, String code) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Save code snippet");
        // A fence label that's already a real filename (e.g. an attached "notes.txt", vs. a
        // model-generated fence's plain language tag like "python") gets suggested verbatim
        // instead of a generic "snippet.*" one.
        chooser.setInitialFileName(language.contains(".")
                ? language : "snippet." + CodeBlockExtensions.extensionFor(language));

        Window window = getScene() != null ? getScene().getWindow() : null;
        File file = chooser.showSaveDialog(window);
        if (file == null) {
            return;
        }
        try {
            Files.writeString(file.toPath(), code.stripTrailing() + System.lineSeparator(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            showError("Failed to save file: " + e.getMessage());
        }
    }
}
