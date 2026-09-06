package com.multiagent.desktop.ui;

import com.multiagent.desktop.model.Conversation;
import com.multiagent.desktop.model.ConversationKind;
import com.multiagent.desktop.model.FolderEntry;
import com.multiagent.desktop.model.ProjectEntry;
import com.multiagent.desktop.model.ThemeMode;
import com.multiagent.desktop.service.ExportFormat;
import com.multiagent.desktop.ui.components.ChatPaneView;
import com.multiagent.desktop.ui.components.SearchDialog;
import com.multiagent.desktop.ui.components.SettingsDialog;
import com.multiagent.desktop.ui.components.SplitPickerDialog;
import com.multiagent.desktop.ui.viewmodel.ChatViewModel;

import javafx.collections.ListChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Top-level layout, mirroring App.tsx's split between the app-level ".global-topbar"
 * (Search/Refresh/Settings/Close-split - not tied to any one conversation) and each pane's
 * own topbar (persona/model, built by ChatPaneView). The sidebar is always bound to the
 * PRIMARY ChatViewModel only, exactly like ConversationList.tsx - the secondary pane
 * (Phase 5 split view) is a second, independent ChatPaneView shown alongside the primary
 * one in a SplitPane, sharing the same backend but with its own active conversation.
 */
public class MainWindow {
    private final BorderPane root = new BorderPane();
    private final ChatViewModel primary;
    private final ChatViewModel secondary;
    private final ChatPaneView primaryPane;
    private final ChatPaneView secondaryPane;
    private final SplitPane centerSplit = new SplitPane();
    private final Button closeSplitButton = new Button("× Close split");

    public MainWindow(ChatViewModel primary, ChatViewModel secondary) {
        this.primary = primary;
        this.secondary = secondary;
        this.primaryPane = new ChatPaneView(primary);
        this.secondaryPane = new ChatPaneView(secondary);

        centerSplit.getItems().add(primaryPane);
        closeSplitButton.setOnAction(e -> closeSplit());
        closeSplitButton.setVisible(false);
        closeSplitButton.setManaged(false);

        // An outer SplitPane (sidebar | chat area) instead of BorderPane.setLeft/setCenter,
        // so the sidebar's width is drag-resizable rather than fixed. It keeps its own size
        // when the window itself is resized (setResizableWithParent(false)) - only the chat
        // area should absorb extra space, the way a typical sidebar behaves.
        Node sidebar = buildSidebar();
        SplitPane outerSplit = new SplitPane(sidebar, centerSplit);
        outerSplit.setDividerPositions(0.2);
        SplitPane.setResizableWithParent(sidebar, false);

        root.setTop(buildGlobalTopBar());
        root.setCenter(outerSplit);
    }

    public Parent getView() {
        return root;
    }

    /**
     * Swaps the Scene's stylesheet entirely rather than layering theme-specific overrides
     * on top of the base one - each theme file is fully self-contained (covers every
     * custom class this app uses, plus .root base-variable overrides that cascade into
     * stock controls), so switching is just "load a different, complete stylesheet".
     * Called once at startup (App.java, after the Scene exists) and again whenever
     * Settings is saved with a different theme.
     */
    public void applyTheme(ThemeMode mode) {
        if (root.getScene() == null) {
            return;
        }
        String resource = switch (mode) {
            case DARK -> "theme-dark.css";
            case TERMINAL -> "theme-terminal.css";
            case LIGHT -> "styles.css";
        };
        var url = MainWindow.class.getResource("/com/multiagent/desktop/ui/" + resource);
        root.getScene().getStylesheets().clear();
        if (url != null) {
            root.getScene().getStylesheets().add(url.toExternalForm());
        }
    }

    private void openSplit(Conversation left, Conversation right) {
        primary.selectConversation(left);
        secondary.selectConversation(right);
        if (!centerSplit.getItems().contains(secondaryPane)) {
            centerSplit.getItems().add(secondaryPane);
            centerSplit.setDividerPositions(0.5);
        }
        closeSplitButton.setVisible(true);
        closeSplitButton.setManaged(true);
    }

    /** Hides the second pane without deleting its conversation - it's still there if "Side by side" is used again. */
    private void closeSplit() {
        centerSplit.getItems().remove(secondaryPane);
        closeSplitButton.setVisible(false);
        closeSplitButton.setManaged(false);
    }

    /**
     * Project/folder-grouped sidebar: a TreeView<Object> whose nodes are a ProjectEntry
     * (header, right-click for Rename/Remove), a FolderEntry (header, right-click for
     * "New chat here"/"Side by side"/"Assign to project…") nested under its project (if
     * any) or at the top level otherwise, the literal String "No folder" (header for
     * folder-less conversations), or a Conversation (leaf, double-click to rename).
     * Projects are a pure UI grouping - see ProjectEntry's javadoc - a folder belongs to at
     * most one project. Rebuilt from scratch on every conversations/folders/projects change -
     * the tree is small enough (a handful of chats) that this is simpler and safer than
     * incremental TreeItem patching, matching ConversationList.tsx's approach of just
     * re-rendering. Always bound to the PRIMARY view model.
     */
    private Node buildSidebar() {
        TreeView<Object> tree = new TreeView<>();
        tree.setShowRoot(false);
        TreeItem<Object> invisibleRoot = new TreeItem<>();
        tree.setRoot(invisibleRoot);

        Runnable[] refreshHolder = new Runnable[1];
        boolean[] selecting = {false};

        refreshHolder[0] = () -> {
            Map<String, List<Conversation>> byFolder = new LinkedHashMap<>();
            List<Conversation> ungrouped = new ArrayList<>();
            for (Conversation conversation : primary.conversations()) {
                String workspacePath = conversation.getWorkspacePath();
                if (workspacePath != null && !workspacePath.isBlank()) {
                    byFolder.computeIfAbsent(workspacePath, k -> new ArrayList<>()).add(conversation);
                } else {
                    ungrouped.add(conversation);
                }
            }

            Map<String, List<FolderEntry>> foldersByProject = new LinkedHashMap<>();
            List<FolderEntry> ungroupedFolders = new ArrayList<>();
            for (FolderEntry folder : primary.folders()) {
                if (folder.projectId() != null && !folder.projectId().isBlank()) {
                    foldersByProject.computeIfAbsent(folder.projectId(), k -> new ArrayList<>()).add(folder);
                } else {
                    ungroupedFolders.add(folder);
                }
            }

            TreeItem<Object> root = new TreeItem<>();
            for (ProjectEntry project : primary.projects()) {
                TreeItem<Object> projectItem = new TreeItem<>(project);
                projectItem.setExpanded(true);
                for (FolderEntry folder : foldersByProject.getOrDefault(project.id(), List.of())) {
                    projectItem.getChildren().add(folderTreeItem(folder, byFolder));
                }
                root.getChildren().add(projectItem);
            }
            for (FolderEntry folder : ungroupedFolders) {
                root.getChildren().add(folderTreeItem(folder, byFolder));
            }
            if (!ungrouped.isEmpty()) {
                TreeItem<Object> noFolderItem = new TreeItem<>("No folder");
                noFolderItem.setExpanded(true);
                for (Conversation conversation : ungrouped) {
                    noFolderItem.getChildren().add(new TreeItem<>(conversation));
                }
                root.getChildren().add(noFolderItem);
            }

            selecting[0] = true;
            tree.setRoot(root);
            selectTreeItemFor(tree, primary.activeConversationProperty().get());
            selecting[0] = false;
        };

        primary.conversations().addListener((ListChangeListener<Conversation>) c -> refreshHolder[0].run());
        primary.folders().addListener((ListChangeListener<FolderEntry>) c -> refreshHolder[0].run());
        primary.projects().addListener((ListChangeListener<ProjectEntry>) c -> refreshHolder[0].run());
        primary.activeConversationProperty().addListener((obs, old, val) -> {
            if (!selecting[0]) {
                selectTreeItemFor(tree, val);
            }
        });
        refreshHolder[0].run();

        tree.setCellFactory(tv -> new TreeCell<>() {
            @Override
            protected void updateItem(Object item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setContextMenu(null);
                    return;
                }
                if (item instanceof ProjectEntry project) {
                    setText("🗂 " + project.name());
                    MenuItem rename = new MenuItem("Rename");
                    rename.setOnAction(e -> promptRenameProject(project));
                    MenuItem remove = new MenuItem("Remove project");
                    remove.setOnAction(e -> confirmRemoveProject(project));
                    setContextMenu(new ContextMenu(rename, remove));
                } else if (item instanceof FolderEntry folder) {
                    Path fileName = Path.of(folder.path()).getFileName();
                    setText("📁 " + (fileName != null ? fileName.toString() : folder.path()));
                    MenuItem newChatHere = new MenuItem("New chat here");
                    newChatHere.setOnAction(e -> primary.newConversationInFolder(folder.path()));
                    MenuItem newOrchestratorHere = new MenuItem("New orchestrator here");
                    newOrchestratorHere.setOnAction(e -> primary.newOrchestratorConversationInFolder(folder.path()));
                    List<Conversation> folderConversations = primary.conversations().stream()
                            .filter(c -> folder.path().equals(c.getWorkspacePath()))
                            .toList();
                    MenuItem sideBySide = new MenuItem("Side by side");
                    sideBySide.setDisable(folderConversations.size() < 2);
                    sideBySide.setOnAction(e -> SplitPickerDialog.ask(folderConversations, ownerWindow())
                            .ifPresent(selection -> openSplit(selection.left(), selection.right())));
                    MenuItem assignToProject = new MenuItem("Assign to project…");
                    assignToProject.setDisable(primary.projects().isEmpty() && folder.projectId() == null);
                    assignToProject.setOnAction(e -> promptAssignFolderToProject(folder));
                    MenuItem removeFolder = new MenuItem("Remove from list");
                    removeFolder.setOnAction(e -> confirmRemoveFolder(folder));
                    setContextMenu(new ContextMenu(
                            newChatHere, newOrchestratorHere, sideBySide, assignToProject, removeFolder));
                } else if (item instanceof Conversation conversation) {
                    String icon = conversation.getKind() == ConversationKind.ORCHESTRATOR ? "🧭 " : "";
                    setText(icon + conversation.getTitle());

                    MenuItem rename = new MenuItem("Rename");
                    rename.setOnAction(e -> promptRename(conversation));
                    MenuItem delete = new MenuItem("Delete");
                    delete.setOnAction(e -> primary.deleteConversation(conversation));
                    MenuItem exportMarkdown = new MenuItem("Export as Markdown");
                    exportMarkdown.setOnAction(e -> exportConversation(conversation, "markdown", tree));
                    MenuItem exportJson = new MenuItem("Export as JSON");
                    exportJson.setOnAction(e -> exportConversation(conversation, "json", tree));
                    setContextMenu(new ContextMenu(rename, delete, exportMarkdown, exportJson));
                } else {
                    setText(String.valueOf(item));
                    setContextMenu(null);
                }
            }
        });

        tree.setOnMouseClicked(event -> {
            TreeItem<Object> selected = tree.getSelectionModel().getSelectedItem();
            if (selected != null && selected.getValue() instanceof Conversation conversation) {
                if (event.getClickCount() == 2) {
                    promptRename(conversation);
                } else {
                    primary.selectConversation(conversation);
                }
            }
        });

        Button newChatButton = new Button("+ New chat");
        newChatButton.setMaxWidth(Double.MAX_VALUE);
        newChatButton.setOnAction(e -> primary.newConversation());

        Button newOrchestratorButton = new Button("+ Orchestrator");
        newOrchestratorButton.setMaxWidth(Double.MAX_VALUE);
        newOrchestratorButton.setOnAction(e -> primary.newOrchestratorConversation());

        Button openFolderButton = new Button("+ Add folder");
        openFolderButton.setMaxWidth(Double.MAX_VALUE);
        openFolderButton.setOnAction(e -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("Open folder");
            Window window = openFolderButton.getScene() != null ? openFolderButton.getScene().getWindow() : null;
            File selected = chooser.showDialog(window);
            if (selected != null) {
                primary.openFolder(selected.getAbsolutePath());
            }
        });

        Button newProjectButton = new Button("+ New project");
        newProjectButton.setMaxWidth(Double.MAX_VALUE);
        newProjectButton.setOnAction(e -> promptNewProject());

        VBox newColumn = new VBox(8, newChatButton, newOrchestratorButton, openFolderButton, newProjectButton);

        VBox box = new VBox(8, newColumn, tree);
        box.setPadding(new Insets(10));
        box.setPrefWidth(240);
        box.setMinWidth(180);
        box.getStyleClass().add("sidebar");
        VBox.setVgrow(tree, Priority.ALWAYS);
        return box;
    }

    /** One FolderEntry TreeItem with its conversations nested - shared by both the project-grouped and ungrouped folder rendering paths. */
    private TreeItem<Object> folderTreeItem(FolderEntry folder, Map<String, List<Conversation>> byFolder) {
        TreeItem<Object> folderItem = new TreeItem<>(folder);
        folderItem.setExpanded(true);
        for (Conversation conversation : byFolder.getOrDefault(folder.path(), List.of())) {
            folderItem.getChildren().add(new TreeItem<>(conversation));
        }
        return folderItem;
    }

    /**
     * Searches the whole tree, not just two levels deep - a conversation can now sit at
     * root -> folder -> conversation (ungrouped folder), root -> "No folder" -> conversation,
     * or root -> project -> folder -> conversation, depending on whether its folder is
     * grouped into a project.
     */
    private void selectTreeItemFor(TreeView<Object> tree, Conversation conversation) {
        if (conversation == null || tree.getRoot() == null) {
            tree.getSelectionModel().clearSelection();
            return;
        }
        TreeItem<Object> match = findConversationItem(tree.getRoot(), conversation.getId());
        if (match != null) {
            tree.getSelectionModel().select(match);
        } else {
            tree.getSelectionModel().clearSelection();
        }
    }

    private TreeItem<Object> findConversationItem(TreeItem<Object> node, String conversationId) {
        for (TreeItem<Object> child : node.getChildren()) {
            if (child.getValue() instanceof Conversation c && c.getId().equals(conversationId)) {
                return child;
            }
            TreeItem<Object> nested = findConversationItem(child, conversationId);
            if (nested != null) {
                return nested;
            }
        }
        return null;
    }

    /** App-level only: not tied to any one conversation, so it stays out of either chat pane. */
    private Node buildGlobalTopBar() {
        Label title = new Label("MultiAgent");
        title.getStyleClass().add("app-title");

        Button searchButton = new Button("Search");
        searchButton.setOnAction(e -> {
            SearchDialog dialog = new SearchDialog(primary);
            dialog.initOwner(ownerWindow());
            dialog.showAndWait();
        });

        Button refreshButton = new Button("Refresh");
        refreshButton.setOnAction(e -> {
            primary.refreshHealth();
            primary.refreshModels();
        });

        Button settingsButton = new Button("Settings");
        settingsButton.setOnAction(e -> {
            SettingsDialog dialog = new SettingsDialog(primary, this::applyTheme);
            dialog.initOwner(ownerWindow());
            dialog.showAndWait();
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox bar = new HBox(10, title, spacer, closeSplitButton, searchButton, refreshButton, settingsButton);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(8, 12, 8, 12));
        bar.getStyleClass().add("global-topbar");
        return bar;
    }

    /** The app's own top-level window - used to parent every dialog/alert this class opens, so none of them can end up opening behind it. */
    private Window ownerWindow() {
        return root.getScene() != null ? root.getScene().getWindow() : null;
    }

    private void confirmRemoveFolder(FolderEntry folder) {
        Path fileName = Path.of(folder.path()).getFileName();
        String label = fileName != null ? fileName.toString() : folder.path();
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.initOwner(ownerWindow());
        alert.setTitle("Remove folder");
        alert.setHeaderText(null);
        alert.setContentText("Remove \"" + label + "\" from the sidebar? Its chats are kept, just "
                + "ungrouped - nothing on disk is touched.");
        alert.showAndWait().filter(button -> button == ButtonType.OK)
                .ifPresent(button -> primary.removeFolder(folder.path()));
    }

    private void promptNewProject() {
        TextInputDialog dialog = new TextInputDialog();
        dialog.initOwner(ownerWindow());
        dialog.setTitle("New project");
        dialog.setHeaderText(null);
        dialog.setContentText("Project name:");
        dialog.showAndWait().ifPresent(name -> {
            if (!name.isBlank()) {
                primary.addProject(name.trim());
            }
        });
    }

    private void promptRenameProject(ProjectEntry project) {
        TextInputDialog dialog = new TextInputDialog(project.name());
        dialog.initOwner(ownerWindow());
        dialog.setTitle("Rename project");
        dialog.setHeaderText(null);
        dialog.setContentText("Name:");
        dialog.showAndWait().ifPresent(name -> {
            if (!name.isBlank()) {
                primary.renameProject(project, name.trim());
            }
        });
    }

    private void confirmRemoveProject(ProjectEntry project) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.initOwner(ownerWindow());
        alert.setTitle("Remove project");
        alert.setHeaderText(null);
        alert.setContentText("Remove \"" + project.name() + "\"? Its folders are kept, just "
                + "ungrouped - nothing on disk or in your chats is touched.");
        alert.showAndWait().filter(button -> button == ButtonType.OK)
                .ifPresent(button -> primary.removeProject(project));
    }

    /** "(no project)" plus every project's name, so a folder can be assigned or ungrouped from the same picker. */
    private void promptAssignFolderToProject(FolderEntry folder) {
        String noProject = "(no project)";
        List<String> choices = new ArrayList<>();
        choices.add(noProject);
        for (ProjectEntry project : primary.projects()) {
            choices.add(project.name());
        }
        String current = folder.projectId() == null ? noProject
                : primary.projects().stream()
                        .filter(p -> p.id().equals(folder.projectId()))
                        .findFirst()
                        .map(ProjectEntry::name)
                        .orElse(noProject);

        ChoiceDialog<String> dialog = new ChoiceDialog<>(current, choices);
        dialog.initOwner(ownerWindow());
        dialog.setTitle("Assign to project");
        dialog.setHeaderText(null);
        dialog.setContentText("Project:");
        dialog.showAndWait().ifPresent(chosenName -> {
            String projectId = noProject.equals(chosenName) ? null
                    : primary.projects().stream()
                            .filter(p -> p.name().equals(chosenName))
                            .findFirst()
                            .map(ProjectEntry::id)
                            .orElse(null);
            primary.assignFolderToProject(folder.path(), projectId);
        });
    }

    private void exportConversation(Conversation conversation, String format, Node ownerNode) {
        String content = primary.exportConversation(conversation, format);
        boolean isJson = "json".equals(format);

        FileChooser chooser = new FileChooser();
        chooser.setTitle("Export conversation");
        chooser.setInitialFileName(ExportFormat.slugifyTitle(conversation.getTitle()) + (isJson ? ".json" : ".md"));
        chooser.getExtensionFilters().add(isJson
                ? new FileChooser.ExtensionFilter("JSON", "*.json")
                : new FileChooser.ExtensionFilter("Markdown", "*.md"));

        Window window = ownerNode.getScene() != null ? ownerNode.getScene().getWindow() : null;
        File file = chooser.showSaveDialog(window);
        if (file == null) {
            return;
        }
        try {
            Files.writeString(file.toPath(), content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            Alert alert = new Alert(Alert.AlertType.ERROR, "Failed to write file: " + e.getMessage());
            alert.initOwner(ownerWindow());
            alert.showAndWait();
        }
    }

    private void promptRename(Conversation conversation) {
        TextInputDialog dialog = new TextInputDialog(conversation.getTitle());
        dialog.initOwner(ownerWindow());
        dialog.setTitle("Rename chat");
        dialog.setHeaderText(null);
        dialog.setContentText("Title:");
        dialog.showAndWait().ifPresent(title -> {
            if (!title.isBlank()) {
                primary.renameConversation(conversation, title.trim());
            }
        });
    }
}
