package com.multiagent.desktop.ui.components;

import com.multiagent.desktop.model.ChatMessage;
import com.multiagent.desktop.model.Conversation;
import com.multiagent.desktop.model.Persona;
import com.multiagent.desktop.model.ServerProfile;
import com.multiagent.desktop.service.TokenEstimate;
import com.multiagent.desktop.ui.viewmodel.ChatViewModel;

import javafx.collections.ListChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.paint.Color;

import java.util.ArrayList;
import java.util.List;

/**
 * "~N tokens - last M messages" hint just above the composer, mirroring ContextUsage.tsx: a
 * rough chars/4 estimate (TokenEstimate) over the active persona's system prompt plus the
 * same message window ChatService/OrchestratorService would actually send (capped to the
 * active conversation's resolved server maxHistory) - colored as an early warning before a
 * context_exceeded error, not to bill or enforce anything.
 */
public class ContextUsageBar extends HBox {
    private static final int DEFAULT_MAX_HISTORY = 40;

    private final Label dot = new Label("●");
    private final Label text = new Label();

    public ContextUsageBar(ChatViewModel viewModel) {
        setSpacing(6);
        setAlignment(Pos.CENTER_LEFT);
        setPadding(new Insets(2, 12, 4, 12));
        Tooltip.install(this, new Tooltip(
                "Approximate size of what will be sent as context for the next message "
                        + "(~4 characters per token; not exact)"));
        getChildren().addAll(dot, text);

        Runnable update = () -> refresh(viewModel);
        viewModel.messages().addListener((ListChangeListener<ChatMessage>) c -> update.run());
        viewModel.activeConversationProperty().addListener((obs, old, val) -> update.run());
        viewModel.activePersonaProperty().addListener((obs, old, val) -> update.run());
        viewModel.activeServerProperty().addListener((obs, old, val) -> update.run());
        update.run();
    }

    private void refresh(ChatViewModel viewModel) {
        Conversation conversation = viewModel.activeConversationProperty().get();
        if (conversation == null) {
            setVisible(false);
            setManaged(false);
            return;
        }

        Persona persona = viewModel.activePersonaProperty().get();
        ServerProfile server = viewModel.activeServerProperty().get();
        int maxHistory = server != null ? server.getMaxHistory() : DEFAULT_MAX_HISTORY;

        List<ChatMessage> messages = viewModel.messages();
        int from = Math.max(0, messages.size() - Math.max(1, maxHistory));
        List<ChatMessage> capped = messages.subList(from, messages.size());

        List<String> texts = new ArrayList<>();
        texts.add(persona != null ? persona.getSystemPrompt() : "");
        for (ChatMessage message : capped) {
            texts.add(message.getContent());
        }

        int tokens = TokenEstimate.estimateTokens(texts);
        TokenEstimate.Level level = TokenEstimate.levelFor(tokens);
        dot.setTextFill(switch (level) {
            case OK -> Color.GRAY;
            case WARN -> Color.web("#d97706");
            case DANGER -> Color.CRIMSON;
        });

        setVisible(true);
        setManaged(true);
        text.setText("~" + tokens + " tokens · last " + capped.size()
                + " message" + (capped.size() == 1 ? "" : "s"));
    }
}
