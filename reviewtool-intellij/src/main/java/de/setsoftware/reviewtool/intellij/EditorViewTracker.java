package de.setsoftware.reviewtool.intellij;

import java.awt.Point;
import java.awt.Rectangle;
import java.io.File;

import javax.swing.Timer;

import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.LogicalPosition;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;

import de.setsoftware.reviewtool.model.viewtracking.ViewStatistics;

/**
 * Feeds the platform-independent {@link ViewStatistics} from the IntelliJ editors: on a timer it
 * marks the lines that are currently visible in the selected editor as viewed. This is the IntelliJ
 * counterpart of the Eclipse view tracker and enables the "visited" computations.
 */
final class EditorViewTracker {

    private static final int INTERVAL_MS = 1000;

    private final Project project;
    private final ViewStatistics statistics;
    private final Timer timer;

    EditorViewTracker(Project project, ViewStatistics statistics) {
        this.project = project;
        this.statistics = statistics;
        this.timer = new Timer(INTERVAL_MS, (e) -> this.tick());
        this.timer.setRepeats(true);
    }

    void start() {
        if (!this.timer.isRunning()) {
            this.timer.start();
        }
    }

    void stop() {
        this.timer.stop();
    }

    private void tick() {
        final Editor editor = FileEditorManager.getInstance(this.project).getSelectedTextEditor();
        if (editor == null) {
            return;
        }
        final VirtualFile file = FileDocumentManager.getInstance().getFile(editor.getDocument());
        if (file == null || !file.isInLocalFileSystem()) {
            return;
        }
        final Rectangle area = editor.getScrollingModel().getVisibleArea();
        if (area.height <= 0) {
            return;
        }
        final LogicalPosition top = editor.xyToLogicalPosition(new Point(0, area.y));
        final LogicalPosition bottom = editor.xyToLogicalPosition(new Point(0, area.y + area.height));
        final int fromLine = Math.min(top.line, bottom.line) + 1;
        final int toLine = Math.max(top.line, bottom.line) + 1;
        this.statistics.mark(new File(file.getPath()), fromLine, toLine);
    }

}
