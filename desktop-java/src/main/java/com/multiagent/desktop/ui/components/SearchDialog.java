package com.multiagent.desktop.ui.components;

import com.multiagent.desktop.model.SearchResult;
import com.multiagent.desktop.ui.viewmodel.ChatViewModel;

import javafx.geometry.Insets;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * Live conversation search, mirroring SearchModal.tsx: queries ConversationStore.search()
 * on every keystroke (a local SQLite LIKE query is fast enough not to need debouncing at
 * this scale) and selects the chosen conversation on click.
 */
public class SearchDialog extends Dialog<Void> {
    public SearchDialog(ChatViewModel viewModel) {
        setTitle("Search chats");

        TextField field = new TextField();
        field.setPromptText("Search titles and messages...");

        ListView<SearchResult> results = new ListView<>();
        results.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(SearchResult item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    return;
                }
                setText(item.matchedInTitle()
                        ? item.title()
                        : item.title() + "  -  " + (item.snippet() != null ? item.snippet() : ""));
            }
        });

        field.textProperty().addListener((obs, old, val) -> results.getItems().setAll(viewModel.search(val)));

        results.setOnMouseClicked(event -> {
            SearchResult selected = results.getSelectionModel().getSelectedItem();
            if (selected != null) {
                viewModel.selectConversationById(selected.conversationId());
                close();
            }
        });

        VBox box = new VBox(8, field, results);
        box.setPadding(new Insets(12));
        box.setPrefSize(480, 420);
        VBox.setVgrow(results, Priority.ALWAYS);

        getDialogPane().setContent(box);
        getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        field.requestFocus();
    }
}
