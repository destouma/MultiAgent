package com.multiagent.desktop.ui.components;

import com.multiagent.desktop.model.Conversation;

import javafx.geometry.Insets;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.stage.Window;

import java.util.List;
import java.util.Optional;

/** "Side by side": pick which two of a folder's conversations go in the left/right panes, mirroring SplitPickerModal.tsx. */
public class SplitPickerDialog extends Dialog<SplitPickerDialog.Selection> {

    public record Selection(Conversation left, Conversation right) {
    }

    public SplitPickerDialog(List<Conversation> folderConversations) {
        setTitle("Side by side");

        ComboBox<Conversation> leftBox = new ComboBox<>();
        leftBox.getItems().addAll(folderConversations);
        ComboBox<Conversation> rightBox = new ComboBox<>();
        rightBox.getItems().addAll(folderConversations);

        leftBox.setValue(folderConversations.get(0));
        rightBox.setValue(folderConversations.size() > 1 ? folderConversations.get(1) : folderConversations.get(0));

        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(10);
        grid.setPadding(new Insets(12));
        grid.addRow(0, new Label("Left"), leftBox);
        grid.addRow(1, new Label("Right"), rightBox);

        getDialogPane().setContent(grid);
        ButtonType openType = new ButtonType("Open side by side", ButtonType.OK.getButtonData());
        getDialogPane().getButtonTypes().addAll(openType, ButtonType.CANCEL);

        setResultConverter(button -> button == openType
                ? new Selection(leftBox.getValue(), rightBox.getValue())
                : null);
    }

    public static Optional<Selection> ask(List<Conversation> folderConversations, Window owner) {
        SplitPickerDialog dialog = new SplitPickerDialog(folderConversations);
        dialog.initOwner(owner);
        return dialog.showAndWait();
    }
}
