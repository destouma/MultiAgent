package com.multiagent.desktop.ui.components;

import com.github.difflib.DiffUtils;
import com.github.difflib.UnifiedDiffUtils;
import com.github.difflib.patch.Patch;
import com.multiagent.desktop.model.CheckpointDiff;

import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.TextArea;

import java.util.Arrays;
import java.util.List;

/**
 * Shows a unified diff (like `git diff`) between a checkpoint's pre-op content and the
 * file's current content on disk, mirroring the Electron app's DiffModal - but as plain
 * unified-diff text in a monospace TextArea rather than a custom colored line-by-line
 * view, since java-diff-utils gives that format for free and it's an equally standard,
 * readable way to show a diff without building a bespoke renderer for Phase 5.
 */
public class DiffDialog extends Dialog<Void> {
    public DiffDialog(CheckpointDiff diff) {
        setTitle("Diff: " + diff.path());

        List<String> before = diff.before() == null ? List.of() : Arrays.asList(diff.before().split("\n", -1));
        List<String> after = diff.after() == null ? List.of() : Arrays.asList(diff.after().split("\n", -1));

        String content;
        if (diff.before() == null && diff.after() == null) {
            content = "(File no longer exists on either side of this checkpoint.)";
        } else {
            Patch<String> patch = DiffUtils.diff(before, after);
            if (patch.getDeltas().isEmpty()) {
                content = "(No changes since this checkpoint - the file already matches, or was reverted.)";
            } else {
                List<String> unified = UnifiedDiffUtils.generateUnifiedDiff(diff.path(), diff.path(), before, patch, 3);
                content = String.join("\n", unified);
            }
        }

        TextArea area = new TextArea(content);
        area.setEditable(false);
        area.setWrapText(false);
        area.setPrefSize(720, 480);
        area.setStyle("-fx-font-family: 'Consolas', 'Menlo', monospace;");

        getDialogPane().setContent(area);
        getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
    }
}
