package com.multiagent.desktop.ui.components;

import com.multiagent.desktop.service.DebugLog;

import javafx.collections.ListChangeListener;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextArea;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Non-modal window over {@link DebugLog#entries()}: a list of captured LLM HTTP exchanges on
 * the left, the selected one's raw request + response on the right. Opened from the "Debug"
 * button in the global topbar; only meaningful while "Debug: log raw API traffic" is on in
 * Settings, but it always opens (and says so) so the button is never a dead no-op.
 */
public class DebugLogWindow extends Stage {

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

    private final ListView<DebugLog.Entry> list = new ListView<>(DebugLog.entries());
    private final TextArea detail = new TextArea();

    public DebugLogWindow(Window owner) {
        initOwner(owner);
        setTitle("Debug - raw API traffic");

        Label status = new Label();
        status.setPadding(new Insets(4, 8, 4, 8));
        Runnable refreshStatus = () -> status.setText(DebugLog.isEnabled()
                ? "Capturing. " + DebugLog.entries().size() + " call(s) this session."
                : "Capture is OFF - enable \"Debug: log raw API traffic\" in Settings to record calls.");
        refreshStatus.run();

        // This window carries no stylesheet (deliberately stock light Modena), so the two
        // fixed colours below are safe - and NOTE: never setTextFill(null) on a cell, that
        // overrides the CSS default with an empty paint and the text renders invisibly.
        Color normalText = Color.rgb(34, 34, 34);
        list.setPlaceholder(new Label("No calls captured yet."));
        list.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(DebugLog.Entry item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    return;
                }
                String path = shortenUrl(item.url());
                String outcome = item.error() != null
                        ? "ERROR"
                        : (item.status() != null ? String.valueOf(item.status()) : "-");
                setText(TIME.format(Instant.ofEpochMilli(item.startedAtMillis()))
                        + "  " + item.method() + "  " + path
                        + "  → " + outcome + "  (" + item.durationMillis() + " ms)");
                boolean bad = item.error() != null || (item.status() != null && item.status() >= 400);
                setTextFill(bad ? Color.CRIMSON : normalText);
            }
        });
        list.getSelectionModel().selectedItemProperty().addListener((obs, old, val) -> showDetail(val));
        // Show the most recent call straight away instead of making the user hunt for it.
        if (!DebugLog.entries().isEmpty()) {
            list.getSelectionModel().selectLast();
            list.scrollTo(DebugLog.entries().size() - 1);
        }
        DebugLog.entries().addListener((ListChangeListener<DebugLog.Entry>) c -> {
            refreshStatus.run();
            while (c.next()) {
                if (c.wasAdded() && !DebugLog.entries().isEmpty()) {
                    list.scrollTo(DebugLog.entries().size() - 1);
                }
            }
        });

        detail.setEditable(false);
        detail.setWrapText(false);
        detail.setStyle("-fx-font-family: 'Consolas','Menlo',monospace;");
        detail.setPromptText("Select a call to see its raw request and response.");

        CheckBox wrap = new CheckBox("Wrap");
        wrap.selectedProperty().addListener((obs, old, val) -> detail.setWrapText(val));

        Button copy = new Button("Copy");
        copy.setOnAction(e -> {
            DebugLog.Entry selected = list.getSelectionModel().getSelectedItem();
            if (selected != null) {
                putOnClipboard(render(selected));
            }
        });

        Button copyAll = new Button("Copy all");
        copyAll.setOnAction(e -> {
            StringBuilder all = new StringBuilder();
            for (DebugLog.Entry entry : DebugLog.entries()) {
                all.append(render(entry)).append("\n\n").append("=".repeat(72)).append("\n\n");
            }
            putOnClipboard(all.toString());
        });

        Button clear = new Button("Clear");
        clear.setOnAction(e -> {
            DebugLog.clear();
            detail.clear();
        });

        Button openFile = new Button("Open log file");
        openFile.setOnAction(e -> openLogFile());
        openFile.setDisable(DebugLog.logFile() == null);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox toolbar = new HBox(6, copy, copyAll, clear, openFile, spacer, wrap);
        toolbar.setPadding(new Insets(6));

        SplitPane split = new SplitPane(list, detail);
        split.setDividerPositions(0.42);
        SplitPane.setResizableWithParent(list, false);
        VBox.setVgrow(split, Priority.ALWAYS);

        VBox rootBox = new VBox(status, toolbar, split);
        setScene(new Scene(rootBox, 900, 560));
    }

    private void showDetail(DebugLog.Entry entry) {
        detail.setText(entry == null ? "" : render(entry));
        detail.positionCaret(0);
    }

    private static String render(DebugLog.Entry e) {
        StringBuilder sb = new StringBuilder();
        sb.append(TIME.format(Instant.ofEpochMilli(e.startedAtMillis())))
                .append("  ").append(e.method()).append(' ').append(e.url()).append('\n');
        sb.append("duration: ").append(e.durationMillis()).append(" ms");
        if (e.status() != null) {
            sb.append("   status: ").append(e.status());
        }
        sb.append('\n');
        if (e.error() != null) {
            sb.append("\n--- error ---\n").append(e.error()).append('\n');
        }
        sb.append("\n--- request body ---\n")
                .append(e.requestBody() == null || e.requestBody().isBlank() ? "(none)" : e.requestBody())
                .append('\n');
        sb.append("\n--- response body ---\n")
                .append(e.responseBody() == null || e.responseBody().isBlank() ? "(none)" : e.responseBody())
                .append('\n');
        return sb.toString();
    }

    private static String shortenUrl(String url) {
        if (url == null) {
            return "";
        }
        int scheme = url.indexOf("://");
        if (scheme < 0) {
            return url;
        }
        int slash = url.indexOf('/', scheme + 3);
        return slash < 0 ? url : url.substring(slash);
    }

    private static void putOnClipboard(String text) {
        ClipboardContent content = new ClipboardContent();
        content.putString(text == null ? "" : text);
        Clipboard.getSystemClipboard().setContent(content);
    }

    private void openLogFile() {
        Path file = DebugLog.logFile();
        if (file == null) {
            return;
        }
        // Off the FX thread: launching the OS file handler can block for a noticeable moment.
        Thread opener = new Thread(() -> {
            try {
                if (!Files.exists(file)) {
                    Files.createDirectories(file.getParent());
                    Files.writeString(file, "");
                }
                if (Desktop.isDesktopSupported()) {
                    Desktop.getDesktop().open(file.toFile());
                }
            } catch (IOException | RuntimeException ignored) {
                // Opening the file is a convenience; the path is also shown in Settings' hint text.
            }
        }, "debug-log-open");
        opener.setDaemon(true);
        opener.start();
    }
}
