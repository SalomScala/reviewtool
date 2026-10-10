package de.setsoftware.reviewtool.intellij;

import java.util.List;
import java.util.function.Consumer;

import com.intellij.diff.contents.DiffContent;
import com.intellij.diff.contents.DocumentContent;
import com.intellij.diff.requests.ContentDiffRequest;
import com.intellij.diff.requests.DiffRequest;
import com.intellij.diff.tools.util.DiffDataKeys;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.ex.EditorGutterComponentEx;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;

/**
 * The IDE actions of the review tool (registered in plugin.xml, with keyboard shortcuts in the
 * "CoRT Code Review" group of the Tools menu). They are the IntelliJ counterparts of the Eclipse
 * commands and delegate to the tool window's {@link ReviewToolPanel}, opening the tool window first
 * if necessary.
 */
public final class CortActions {

    /**
     * The id of the tool window. The packaged plugin.xml renames it (see build.gradle.kts), so both
     * ids are tried.
     */
    private static final String[] TOOL_WINDOW_IDS = {"CoRTOriginal", "CoRT"};

    private CortActions() {
    }

    /**
     * Runs the given task with the review panel, opening the CoRT tool window (which creates the
     * panel) if necessary.
     */
    static void withPanel(Project project, Consumer<ReviewToolPanel> task) {
        final ToolWindow toolWindow = findToolWindow(project);
        final ReviewToolPanel panel = ReviewToolService.getInstance(project).getReviewPanel();
        if (panel != null) {
            if (toolWindow != null && !toolWindow.isVisible()) {
                toolWindow.show();
            }
            task.accept(panel);
            return;
        }
        if (toolWindow == null) {
            return;
        }
        toolWindow.show(() -> {
            final ReviewToolPanel openedPanel = ReviewToolService.getInstance(project).getReviewPanel();
            if (openedPanel != null) {
                task.accept(openedPanel);
            }
        });
    }

    private static ToolWindow findToolWindow(Project project) {
        final ToolWindowManager manager = ToolWindowManager.getInstance(project);
        for (final String id : TOOL_WINDOW_IDS) {
            final ToolWindow toolWindow = manager.getToolWindow(id);
            if (toolWindow != null) {
                return toolWindow;
            }
        }
        return null;
    }

    /**
     * Base class for the actions: determines the task from the event (before the tool window is
     * possibly opened asynchronously) and runs it with the panel.
     */
    abstract static class PanelAction extends DumbAwareAction {

        /**
         * Returns the task to perform with the panel, or null if there is nothing to do.
         */
        protected abstract Consumer<ReviewToolPanel> createTask(AnActionEvent e);

        protected boolean needsEditor() {
            return false;
        }

        @Override
        public void actionPerformed(AnActionEvent e) {
            final Project project = e.getProject();
            if (project == null) {
                return;
            }
            final Consumer<ReviewToolPanel> task = this.createTask(e);
            if (task != null) {
                withPanel(project, task);
            }
        }

        @Override
        public void update(AnActionEvent e) {
            e.getPresentation().setEnabled(e.getProject() != null
                    && (!this.needsEditor() || e.getData(CommonDataKeys.EDITOR) != null));
        }

        @Override
        public ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.BGT;
        }
    }

    /**
     * The file and (1-based) caret line of the editor in the event's context, or null.
     */
    private static final class EditorPosition {
        private final VirtualFile file;
        private final int line;
        private final String selectedText;

        private EditorPosition(VirtualFile file, int line, String selectedText) {
            this.file = file;
            this.line = line;
            this.selectedText = selectedText;
        }

        static EditorPosition of(AnActionEvent e) {
            final Editor editor = e.getData(CommonDataKeys.EDITOR);
            if (editor == null) {
                return null;
            }
            VirtualFile file = e.getData(CommonDataKeys.VIRTUAL_FILE);
            if (file == null) {
                file = FileDocumentManager.getInstance().getFile(editor.getDocument());
            }
            if (file == null) {
                return null;
            }
            // for the editor popup menu the caret has been moved to the clicked position, so its line
            // is the line that was right-clicked
            final int line = editor.getCaretModel().getLogicalPosition().line + 1;
            final String selected = editor.getSelectionModel().getSelectedText();
            return new EditorPosition(file, line, selected == null ? "" : selected);
        }
    }

    /**
     * Jumps to the next unvisited stop (review mode) or the next open remark (fixing mode).
     */
    public static final class JumpToNext extends PanelAction {
        @Override
        protected Consumer<ReviewToolPanel> createTask(AnActionEvent e) {
            return ReviewToolPanel::jumpToNext;
        }
    }

    /**
     * Jumps to the next relevant stop that has not been visited yet.
     */
    public static final class NextUnvisitedStop extends PanelAction {
        @Override
        protected Consumer<ReviewToolPanel> createTask(AnActionEvent e) {
            return ReviewToolPanel::jumpToNextUnvisitedStop;
        }
    }

    /**
     * Jumps to the next relevant stop.
     */
    public static final class NextStop extends PanelAction {
        @Override
        protected Consumer<ReviewToolPanel> createTask(AnActionEvent e) {
            return (panel) -> panel.navigateStops(1);
        }
    }

    /**
     * Jumps to the previous relevant stop.
     */
    public static final class PreviousStop extends PanelAction {
        @Override
        protected Consumer<ReviewToolPanel> createTask(AnActionEvent e) {
            return (panel) -> panel.navigateStops(-1);
        }
    }

    /**
     * Jumps to the next remark that still needs fixing.
     */
    public static final class NextOpenRemark extends PanelAction {
        @Override
        protected Consumer<ReviewToolPanel> createTask(AnActionEvent e) {
            return ReviewToolPanel::jumpToNextOpenRemark;
        }
    }

    /**
     * Selects the review stop nearest to the caret in the "Tours" tab.
     */
    public static final class ShowInTours extends PanelAction {
        @Override
        protected boolean needsEditor() {
            return true;
        }

        @Override
        protected Consumer<ReviewToolPanel> createTask(AnActionEvent e) {
            final EditorPosition pos = EditorPosition.of(e);
            if (pos == null) {
                return null;
            }
            return (panel) -> panel.showInTours(pos.file, pos.line);
        }
    }

    /**
     * Adds a review remark at the caret line (or right-clicked line) of the editor, prefilled with
     * the selected text. In the diff of a review stop, the line of either side of the diff is mapped
     * to the current file (a line on the left side to the place where it has been changed or
     * deleted). Without an editor, a global remark is added.
     */
    public static final class AddRemark extends PanelAction {
        @Override
        protected Consumer<ReviewToolPanel> createTask(AnActionEvent e) {
            final Consumer<ReviewToolPanel> inStopDiff = createTaskForStopDiff(e);
            if (inStopDiff != null) {
                return inStopDiff;
            }
            final EditorPosition pos = EditorPosition.of(e);
            if (pos == null) {
                return (panel) -> panel.addRemarkAt(null, 0, "");
            }
            return (panel) -> panel.addRemarkAt(pos.file, pos.line, pos.selectedText);
        }

        private static Consumer<ReviewToolPanel> createTaskForStopDiff(AnActionEvent e) {
            final DiffRequest request = e.getData(DiffDataKeys.DIFF_REQUEST);
            final StopDiffViewer.StopDiffTarget target =
                    request == null ? null : request.getUserData(StopDiffViewer.STOP_DIFF_TARGET);
            if (target == null) {
                return null;
            }
            Editor editor = e.getData(CommonDataKeys.EDITOR);
            if (editor == null) {
                editor = e.getData(DiffDataKeys.CURRENT_EDITOR);
            }
            if (editor == null) {
                return (panel) -> panel.addRemarkForStop(target.getStop());
            }
            final Integer gutterLine = e.getData(EditorGutterComponentEx.LOGICAL_LINE_AT_CURSOR);
            final int diffLine = gutterLine != null ? gutterLine : editor.getCaretModel().getLogicalPosition().line;
            final int line = target.toFileLine(!isLeftSide(request, editor), diffLine);
            final String selected = editor.getSelectionModel().getSelectedText();
            final VirtualFile file = IntellijFileResolver.findByAbsoluteFile(target.getStop().getAbsoluteFile());
            if (file == null) {
                return (panel) -> panel.addRemarkForStop(target.getStop());
            }
            return (panel) -> panel.addRemarkAt(file, line, selected == null ? "" : selected);
        }

        private static boolean isLeftSide(DiffRequest request, Editor editor) {
            if (!(request instanceof ContentDiffRequest)) {
                return false;
            }
            final List<DiffContent> contents = ((ContentDiffRequest) request).getContents();
            return !contents.isEmpty() && contents.get(0) instanceof DocumentContent
                    && ((DocumentContent) contents.get(0)).getDocument() == editor.getDocument();
        }
    }

}
