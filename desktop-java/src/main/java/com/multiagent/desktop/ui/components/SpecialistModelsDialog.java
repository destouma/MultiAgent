package com.multiagent.desktop.ui.components;

import com.multiagent.desktop.model.ModelInfo;
import com.multiagent.desktop.model.Persona;

import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
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
 * Orchestrator only: pick which model each specialist runs on for THIS conversation (a blank
 * pick means "use the specialist's persona default, else this conversation's model"), and
 * whether the coordinator may run the write-capable executor phase after synthesis. Returns
 * an {@link OrchestratorConfig} on OK, null on Cancel.
 */
public class SpecialistModelsDialog extends Dialog<SpecialistModelsDialog.OrchestratorConfig> {

    private static final String DEFAULT_SENTINEL = "";

    /** @param specialistModels non-blank per-specialist model picks; @param apply run the executor phase. */
    public record OrchestratorConfig(Map<String, String> specialistModels, boolean apply) {
    }

    public SpecialistModelsDialog(List<Persona> specialists, Map<String, String> current,
                                   List<ModelInfo> models, String conversationModel,
                                   String workspacePath, boolean applyEnabled) {
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

        CheckBox applyBox = new CheckBox("Let the coordinator apply changes to the workspace"
                + " (each write still asks for approval)");
        applyBox.setSelected(applyEnabled && hasWorkspace(workspacePath));
        if (!hasWorkspace(workspacePath)) {
            applyBox.setSelected(false);
            applyBox.setDisable(true);
            Label hint = new Label("Bind a workspace folder to this chat to enable applying changes.");
            hint.setStyle("-fx-text-fill: gray;");
            grid.add(applyBox, 0, row++, 2, 1);
            grid.add(hint, 0, row++, 2, 1);
        } else {
            grid.add(applyBox, 0, row++, 2, 1);
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
            return new OrchestratorConfig(result, applyBox.isSelected());
        });
    }

    private static boolean hasWorkspace(String workspacePath) {
        return workspacePath != null && !workspacePath.isBlank();
    }
}
