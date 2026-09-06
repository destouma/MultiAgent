package com.multiagent.desktop.ui.components;

import com.multiagent.desktop.ui.viewmodel.ChatViewModel;

import javafx.beans.binding.Bindings;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * Text input + Send/Stop, mirroring Composer.tsx: Enter sends, Shift+Enter inserts a
 * newline, the button toggles to Stop (calling cancelStreaming) while streaming.
 */
public class Composer extends VBox {
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
            if (text == null || text.isBlank()) {
                return;
            }
            // Only clear the composer once the send actually goes through - if it's
            // rejected (e.g. no model selected), the user's draft must not be wiped out
            // from under them just because the error banner also updated.
            if (viewModel.sendMessage(text)) {
                input.clear();
            }
        };

        sendButton.setOnAction(e -> send.run());
        input.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ENTER && !event.isShiftDown()) {
                event.consume();
                send.run();
            }
        });

        HBox row = new HBox(8, input, sendButton);
        HBox.setHgrow(input, Priority.ALWAYS);
        BorderPane.setMargin(row, new Insets(0));

        getChildren().addAll(error, row);
    }
}
