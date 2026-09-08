package com.multiagent.desktop.ui.components;

import com.multiagent.desktop.model.ModelInfo;
import com.multiagent.desktop.model.Persona;

import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.layout.GridPane;
import javafx.util.Callback;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Orchestrator only: pick which model each specialist runs on for THIS conversation. A blank
 * pick means "use the specialist's persona default, else this conversation's model". Returns
 * the map of non-blank picks on OK, null on Cancel.
 */
public class SpecialistModelsDialog extends Dialog<Map<String, String>> {

    private static final String DEFAULT_SENTINEL = "";

    public SpecialistModelsDialog(List<Persona> specialists, Map<String, String> current,
                                   List<ModelInfo> models, String conversationModel) {
        setTitle("Specialist models");

        Map<String, ComboBox<String>> boxes = new LinkedHashMap<>();
        List<String> ids = models.stream().map(ModelInfo::id).filter(s -> !s.isBlank()).toList();

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.setPadding(new Insets(14));
        grid.add(new Label("Model per specialist for this orchestrator chat. Blank = its default."), 0, 0, 2, 1);

        int row = 1;
        for (Persona persona : specialists) {
            String personaDefault = persona.getDefaultModel() != null && !persona.getDefaultModel().isBlank()
                    ? persona.getDefaultModel() : conversationModel;

            ComboBox<String> box = new ComboBox<>();
            List<String> items = new ArrayList<>();
            items.add(DEFAULT_SENTINEL);
            items.addAll(ids);
            String saved = current.get(persona.getId());
            if (saved != null && !saved.isBlank() && !items.contains(saved)) {
                items.add(saved);
            }
            box.setItems(FXCollections.observableArrayList(items));
            box.setValue(saved == null ? DEFAULT_SENTINEL : saved);
            Callback<ListView<String>, ListCell<String>> cells = lv -> new ListCell<>() {
                @Override
                protected void updateItem(String item, boolean empty) {
                    super.updateItem(item, empty);
                    setText(empty ? null : (item == null || item.isEmpty()
                            ? "(default: " + personaDefault + ")" : item));
                }
            };
            box.setCellFactory(cells);
            box.setButtonCell(cells.call(null));

            grid.add(new Label(persona.getName()), 0, row);
            grid.add(box, 1, row);
            boxes.put(persona.getId(), box);
            row++;
        }

        getDialogPane().setContent(grid);
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        setResultConverter(buttonType -> {
            if (buttonType != ButtonType.OK) {
                return null;
            }
            Map<String, String> result = new LinkedHashMap<>();
            boxes.forEach((id, box) -> {
                String value = box.getValue();
                if (value != null && !value.isBlank()) {
                    result.put(id, value);
                }
            });
            return result;
        });
    }
}
