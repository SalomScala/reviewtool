package de.setsoftware.reviewtool.intellij;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;

/**
 * Editor action that adds a CoRT review remark at the line of the current caret / right click.
 * It is contributed to the editor context menu and delegates to the CoRT tool window's panel,
 * opening the tool window first if it is not visible yet.
 */
public class AddReviewRemarkAction extends AnAction {

    private static final String TOOL_WINDOW_ID = "CoRT";

    @Override
    public void actionPerformed(AnActionEvent e) {
        final Project project = e.getProject();
        final Editor editor = e.getData(CommonDataKeys.EDITOR);
        if (project == null || editor == null) {
            return;
        }
        VirtualFile resolved = e.getData(CommonDataKeys.VIRTUAL_FILE);
        if (resolved == null) {
            resolved = FileDocumentManager.getInstance().getFile(editor.getDocument());
        }
        if (resolved == null) {
            return;
        }
        final VirtualFile file = resolved;
        // for the editor popup menu the caret has been moved to the clicked position, so its line
        // is the line that was right-clicked
        final int line = editor.getCaretModel().getLogicalPosition().line + 1;

        final ReviewToolPanel panel = ReviewToolService.getInstance(project).getReviewPanel();
        if (panel != null) {
            panel.addRemarkAt(file, line);
            return;
        }

        // the tool window has not been opened yet; open it (which creates the panel) and then add
        final ToolWindow toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID);
        if (toolWindow == null) {
            return;
        }
        toolWindow.show(() -> {
            final ReviewToolPanel openedPanel = ReviewToolService.getInstance(project).getReviewPanel();
            if (openedPanel != null) {
                openedPanel.addRemarkAt(file, line);
            }
        });
    }

    @Override
    public void update(AnActionEvent e) {
        e.getPresentation().setEnabledAndVisible(
                e.getProject() != null && e.getData(CommonDataKeys.EDITOR) != null);
    }

    @Override
    public ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

}
