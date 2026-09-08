package com.multiagent.desktop.ui.components;

import com.multiagent.desktop.model.Persona;
import com.multiagent.desktop.service.PersonaRegistry;

import javafx.collections.FXCollections;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

import java.util.List;
import java.util.Set;

/**
 * Add / edit / view one persona for the Settings persona list. Returns the (new or updated)
 * {@link Persona} on OK, null on Cancel/Close. Bundled personas open read-only (fields
 * selectable for copy, but no Save); user-defined ones are fully editable. The id is fixed
 * once created since it's the {@code <id>.json} filename.
 */
public class PersonaEditDialog extends Dialog<Persona> {

    /**
     * @param existing  null to create a new persona
     * @param readOnly  true for a bundled persona (view only)
     * @param takenIds  every id already in use (only enforced when creating)
     * @param modelIds  server model ids to seed the "Default model" dropdown
     */
    public PersonaEditDialog(Persona existing, boolean readOnly, Set<String> takenIds, List<String> modelIds) {
        boolean creating = existing == null;
        setTitle(readOnly ? "View persona" : creating ? "Add persona" : "Edit persona");

        TextField idField = new TextField(creating ? "" : existing.getId());
        idField.setPromptText("lowercase-with-dashes");
        idField.setEditable(creating && !readOnly); // fixed after creation - it's the filename
        idField.setDisable(!creating); // greys it so it clearly reads as immutable

        TextField nameField = new TextField(creating ? "" : nz(existing.getName()));
        nameField.setEditable(!readOnly);

        TextField descriptionField = new TextField(creating ? "" : nz(existing.getDescription()));
        descriptionField.setPromptText("one line, shown under the name");
        descriptionField.setEditable(!readOnly);

        TextField colorField = new TextField(creating ? "#3B82F6" : nz(existing.getColor()));
        colorField.setPromptText("#RRGGBB");
        colorField.setEditable(!readOnly);
        Region swatch = new Region();
        swatch.setMinSize(26, 26);
        swatch.setPrefSize(26, 26);
        swatch.setMaxSize(26, 26);
        Runnable paintSwatch = () -> {
            String c = sanitizeColor(colorField.getText());
            swatch.setStyle("-fx-background-radius: 4; -fx-border-radius: 4; -fx-border-color: gray;"
                    + " -fx-background-color: " + (c == null ? "transparent" : c) + ";");
        };
        colorField.textProperty().addListener((o, a, b) -> paintSwatch.run());
        paintSwatch.run();
        HBox colorRow = new HBox(6, colorField, swatch);
        HBox.setHgrow(colorField, Priority.ALWAYS);

        ComboBox<String> modelBox = new ComboBox<>(FXCollections.observableArrayList(modelIds));
        modelBox.setEditable(true);
        modelBox.setPromptText("optional - blank uses the chat's model");
        modelBox.setMaxWidth(Double.MAX_VALUE);
        modelBox.setDisable(readOnly);
        if (!creating && existing.getDefaultModel() != null && !existing.getDefaultModel().isBlank()) {
            if (!modelBox.getItems().contains(existing.getDefaultModel())) {
                modelBox.getItems().add(0, existing.getDefaultModel());
            }
            modelBox.setValue(existing.getDefaultModel());
        }

        TextArea promptArea = new TextArea(creating ? "" : nz(existing.getSystemPrompt()));
        promptArea.setWrapText(true);
        promptArea.setPrefRowCount(9);
        promptArea.setPromptText("The system prompt sent at the top of every turn for this persona.");
        promptArea.setEditable(!readOnly);

        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(8);
        grid.setPadding(new Insets(12));
        ColumnConstraints labels = new ColumnConstraints();
        labels.setMinWidth(Region.USE_PREF_SIZE); // never squeeze the label column - it truncates "Description"/"System prompt" otherwise
        labels.setHalignment(javafx.geometry.HPos.RIGHT);
        ColumnConstraints fields = new ColumnConstraints();
        fields.setHgrow(Priority.ALWAYS);
        fields.setFillWidth(true);
        grid.getColumnConstraints().addAll(labels, fields);
        grid.addRow(0, new Label("Id"), idField);
        grid.addRow(1, new Label("Name"), nameField);
        grid.addRow(2, new Label("Description"), descriptionField);
        grid.addRow(3, new Label("Color"), colorRow);
        grid.addRow(4, new Label("Default model"), modelBox);
        Label promptLabel = new Label("System prompt");
        GridPane.setValignment(promptLabel, javafx.geometry.VPos.TOP);
        grid.addRow(5, promptLabel, promptArea);

        getDialogPane().setContent(grid);
        getDialogPane().setPrefWidth(560);
        if (readOnly) {
            getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        } else {
            getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
            Node okButton = getDialogPane().lookupButton(ButtonType.OK);
            okButton.addEventFilter(ActionEvent.ACTION, event -> {
                String error = validate(creating, idField.getText(), nameField.getText(),
                        promptArea.getText(), colorField.getText(), takenIds);
                if (error != null) {
                    Alert alert = new Alert(Alert.AlertType.WARNING, error);
                    alert.initOwner(getDialogPane().getScene().getWindow());
                    alert.setHeaderText(null);
                    alert.showAndWait();
                    event.consume(); // keep the dialog open
                }
            });
        }

        setResultConverter(button -> {
            if (button != ButtonType.OK) {
                return null;
            }
            String id = creating ? idField.getText().trim() : existing.getId();
            Persona persona = new Persona(id, nameField.getText().trim(),
                    blankToNull(descriptionField.getText()), promptArea.getText().trim(),
                    blankToNull(colorField.getText()));
            String model = modelBox.getEditor().getText();
            persona.setDefaultModel(blankToNull(model));
            return persona;
        });
    }

    private static String validate(boolean creating, String id, String name, String prompt,
                                    String color, Set<String> takenIds) {
        if (name == null || name.isBlank()) {
            return "Name can't be empty.";
        }
        if (prompt == null || prompt.isBlank()) {
            return "System prompt can't be empty.";
        }
        if (color != null && !color.isBlank() && sanitizeColor(color) == null) {
            return "Color must be a hex value like #3B82F6 (or left blank).";
        }
        if (creating) {
            String trimmed = id == null ? "" : id.trim();
            if (!PersonaRegistry.VALID_ID.matcher(trimmed).matches()) {
                return "Id must be lowercase letters, digits and dashes, e.g. \"security-auditor\".";
            }
            if (takenIds != null && takenIds.contains(trimmed)) {
                return "A persona with id \"" + trimmed + "\" already exists.";
            }
        }
        return null;
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }

    private static String blankToNull(String value) {
        return value == null || value.trim().isBlank() ? null : value.trim();
    }

    /** Only a plain #hex literal reaches an inline -fx- style, so a pasted value can't inject CSS. */
    static String sanitizeColor(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.matches("#([0-9a-fA-F]{3,4}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})") ? trimmed : null;
    }
}
