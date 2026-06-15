package de.setsoftware.reviewtool.intellij;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import com.intellij.diff.DiffContentFactory;
import com.intellij.diff.DiffManager;
import com.intellij.diff.contents.DocumentContent;
import com.intellij.diff.requests.SimpleDiffRequest;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;

import de.setsoftware.reviewtool.base.Logger;
import de.setsoftware.reviewtool.model.api.IRevisionedFile;
import de.setsoftware.reviewtool.model.changestructure.Stop;

/**
 * Shows the before/after diff of a review tour stop in IntelliJ's diff viewer. This is the IntelliJ
 * counterpart of the Eclipse combined diff stop viewer.
 */
final class StopDiffViewer {

    private StopDiffViewer() {
    }

    /**
     * Opens the diff between the oldest and the most recent revision of the given stop's file.
     */
    static void show(Project project, Stop stop) {
        if (stop.isBinaryChange()) {
            Messages.showInfoMessage(project,
                    "The selected stop is a binary change; no textual diff can be shown.",
                    "Code Review Tool");
            return;
        }
        new Task.Backgroundable(project, "Loading diff for review stop", false) {
            @Override
            public void run(ProgressIndicator indicator) {
                try {
                    final Map<IRevisionedFile, IRevisionedFile> history = stop.getHistory();
                    final IRevisionedFile oldFile = history.isEmpty() ? null : history.keySet().iterator().next();
                    final IRevisionedFile newFile = stop.getMostRecentFile();
                    final String oldText = oldFile == null ? "" : readContents(oldFile);
                    final String newText = readContents(newFile);
                    final String fileName = newFile.getPath();
                    final String oldTitle = oldFile == null ? "Before" : "Before (" + oldFile.getRevision() + ")";
                    final String newTitle = "After (" + newFile.getRevision() + ")";
                    ApplicationManager.getApplication().invokeLater(() ->
                            showDiff(project, stop, fileName, oldText, newText, oldTitle, newTitle));
                } catch (final Exception e) {
                    Logger.warn("could not load diff for stop", e);
                    ApplicationManager.getApplication().invokeLater(() ->
                            Messages.showErrorDialog(project,
                                    "Could not load the diff for the selected stop: " + e,
                                    "Code Review Tool"));
                }
            }
        }.queue();
    }

    private static void showDiff(
            Project project, Stop stop, String fileName,
            String oldText, String newText, String oldTitle, String newTitle) {
        final VirtualFile vf = IntellijFileResolver.findByAbsoluteFile(stop.getAbsoluteFile());
        final FileType fileType = vf != null
                ? vf.getFileType()
                : FileTypeManager.getInstance().getFileTypeByFileName(fileName);
        final DiffContentFactory factory = DiffContentFactory.getInstance();
        final DocumentContent left = factory.create(project, oldText, fileType);
        final DocumentContent right = factory.create(project, newText, fileType);
        final SimpleDiffRequest request =
                new SimpleDiffRequest("Review stop: " + fileName, left, right, oldTitle, newTitle);
        DiffManager.getInstance().showDiff(project, request);
    }

    private static String readContents(IRevisionedFile file) throws Exception {
        final byte[] contents = file.getContents();
        return contents == null ? "" : new String(contents, StandardCharsets.UTF_8);
    }

}
