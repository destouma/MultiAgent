package com.multiagent.desktop.ui.components;

import com.multiagent.desktop.action.ActionApprover;
import com.multiagent.desktop.action.PendingAction;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.TextArea;
import javafx.stage.Window;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * Shows a confirmation dialog on the FX Application Thread and blocks the calling
 * (background, tool-loop) thread until the user answers. One instance is shared across
 * every conversation/pane - approve() is called from whichever background thread is
 * currently running a tool loop, and each call is independent (no shared mutable state
 * beyond the owner window), so concurrent approvals from two panes are safe.
 */
public class DialogActionApprover implements ActionApprover {
    private final Window owner;

    public DialogActionApprover(Window owner) {
        this.owner = owner;
    }

    @Override
    public boolean approve(PendingAction action) {
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
            alert.initOwner(owner);
            alert.setTitle("Approve action?");
            alert.setHeaderText(action.summary());
            alert.setContentText("The model wants to do this. Allow it?");
            // The default header text renders no heavier than the body text, so the actual
            // action (the one thing the user must actually read before clicking OK) doesn't
            // stand out. applyCss() forces the header-panel to build immediately so the
            // lookup below finds the label instead of returning null.
            alert.getDialogPane().applyCss();
            Node headerText = alert.getDialogPane().lookup(".header-panel .header-text");
            if (headerText != null) {
                headerText.setStyle("-fx-font-weight: bold; -fx-font-size: 15px;");
            }
            if (action.detail() != null && !action.detail().isBlank()) {
                TextArea area = new TextArea(action.detail());
                area.setEditable(false);
                area.setWrapText(true);
                area.setPrefSize(520, 280);
                area.setStyle("-fx-font-family: 'Consolas', 'Menlo', monospace;");
                alert.getDialogPane().setExpandableContent(area);
                alert.getDialogPane().setExpanded(true);
            }
            result.complete(alert.showAndWait().filter(b -> b == ButtonType.OK).isPresent());
        });
        try {
            return result.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException e) {
            return false;
        }
    }
}
