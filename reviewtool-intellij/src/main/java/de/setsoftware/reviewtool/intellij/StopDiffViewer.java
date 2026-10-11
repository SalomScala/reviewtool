package de.setsoftware.reviewtool.intellij;

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.intellij.diff.DiffContentFactory;
import com.intellij.diff.DiffManager;
import com.intellij.diff.comparison.ComparisonManager;
import com.intellij.diff.comparison.ComparisonPolicy;
import com.intellij.diff.comparison.DiffTooBigException;
import com.intellij.diff.contents.DiffContent;
import com.intellij.diff.contents.DocumentContent;
import com.intellij.diff.requests.SimpleDiffRequest;
import com.intellij.diff.fragments.LineFragment;
import com.intellij.diff.util.DiffUserDataKeys;
import com.intellij.diff.util.DiffUserDataKeysEx;
import com.intellij.diff.util.Side;
import com.intellij.icons.AllIcons;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.progress.DumbProgressIndicator;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.vfs.VirtualFile;

import de.setsoftware.reviewtool.base.Logger;
import de.setsoftware.reviewtool.model.api.IFragment;
import de.setsoftware.reviewtool.model.api.ILocalRevision;
import de.setsoftware.reviewtool.model.api.IRevision;
import de.setsoftware.reviewtool.model.api.IRevisionedFile;
import de.setsoftware.reviewtool.model.api.IUnknownRevision;
import de.setsoftware.reviewtool.model.changestructure.Stop;

/**
 * Shows the before/after diff of a review tour stop in IntelliJ's diff viewer. This is the IntelliJ
 * counterpart of the Eclipse combined diff stop viewer. The diff is scrolled to the stop's code and
 * can be restricted to the changed sections of the stop (instead of the whole file with all of its
 * changes).
 */
final class StopDiffViewer {

    /**
     * The contents of the two revisions of a stop's file (loaded in the background, because this
     * can access the repository).
     */
    static final class StopDiffData {
        private final String fileName;
        private final String oldText;
        private final String newText;
        private final String oldTitle;
        private final String newTitle;
        private final int line;
        private final List<StopDiffExcerpt.Change> changes;
        private final StopDiffExcerpt excerpt;

        private StopDiffData(String fileName, String oldText, String newText, String oldTitle, String newTitle,
                int line, List<StopDiffExcerpt.Change> changes, StopDiffExcerpt excerpt) {
            this.fileName = fileName;
            this.oldText = oldText;
            this.newText = newText;
            this.oldTitle = oldTitle;
            this.newTitle = newTitle;
            this.line = line;
            this.changes = changes;
            this.excerpt = excerpt;
        }

        /**
         * The excerpt with only the stop's changes (null if it could not be determined, e.g. for a
         * stop that covers the whole file).
         */
        StopDiffExcerpt getExcerpt() {
            return this.excerpt;
        }
    }

    /**
     * The stop a diff request shows, so that review remarks can be added in the diff (see
     * {@link CortActions.AddRemark}).
     */
    /**
     * The ids of the "Add Review Remark" action (the packaged plugin.xml renames it, see build.gradle.kts).
     */
    private static final String[] ADD_REMARK_ACTION_IDS = {
        "de.setsoftware.reviewtool.cortoriginal.AddReviewRemarkAction",
        "de.setsoftware.reviewtool.intellij.AddReviewRemarkAction",
    };

    static final Key<StopDiffTarget> STOP_DIFF_TARGET = Key.create("de.setsoftware.reviewtool.stopDiffTarget");

    /**
     * The stop shown in a diff, with the information needed to map a line of either side of the diff
     * to a line of the current file (review remarks refer to the current file).
     */
    static final class StopDiffTarget {
        private final Stop stop;
        private final StopDiffData data;
        private final StopDiffExcerpt excerpt;

        StopDiffTarget(Stop stop, StopDiffData data, StopDiffExcerpt excerpt) {
            this.stop = stop;
            this.data = data;
            this.excerpt = excerpt;
        }

        Stop getStop() {
            return this.stop;
        }

        private static int lineCount(String text) {
            int count = 1;
            for (int i = 0; i < text.length() - 1; i++) {
                if (text.charAt(i) == '\n') {
                    count++;
                }
            }
            return count;
        }

        /**
         * Returns the (1-based) line of the current file for the given (0-based) line of the diff.
         * Lines of the old side are mapped to the place where they have been changed or deleted.
         *
         * @param newSide True for the right ("after") side of the diff, false for the left side.
         */
        int toFileLine(boolean newSide, int diffLine) {
            final int line;
            if (newSide) {
                line = this.excerpt == null ? diffLine : this.excerpt.toNewLine(diffLine);
            } else {
                final int oldLine = this.excerpt == null ? diffLine : this.excerpt.toOldLine(diffLine);
                line = oldLine < 0 || this.data.changes == null
                        ? -1 : StopDiffExcerpt.oldToNewLine(this.data.changes, oldLine);
            }
            if (line < 0) {
                // a separator between the sections of the excerpt
                return this.data.line;
            }
            return Math.min(line, lineCount(this.data.newText) - 1) + 1;
        }
    }

    /**
     * Number of unchanged lines shown above and below the stop's changes.
     */
    static final int CONTEXT_LINES = 3;

    /**
     * The textual form of a git revision: the commit hash and the commit time in seconds.
     */
    private static final Pattern GIT_REVISION = Pattern.compile("([0-9a-f]{40}) \\((\\d+)\\)");

    private static final String ONLY_STOP_CHANGES_KEY = "de.setsoftware.reviewtool.stopDiffOnlyStopChanges";

    private StopDiffViewer() {
    }

    /**
     * Returns true iff the diffs shall only show the changed sections of the stop (the default).
     */
    static boolean isOnlyStopChanges() {
        return PropertiesComponent.getInstance().getBoolean(ONLY_STOP_CHANGES_KEY, true);
    }

    static void setOnlyStopChanges(boolean value) {
        PropertiesComponent.getInstance().setValue(ONLY_STOP_CHANGES_KEY, value, true);
    }

    /**
     * Opens the diff between the oldest and the most recent revision of the given stop's file (only
     * the stop's changes or the whole file, depending on the setting).
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
                    final StopDiffData data = load(stop);
                    ApplicationManager.getApplication().invokeLater(() ->
                            DiffManager.getInstance().showDiff(project,
                                    createRequest(project, stop, data, isOnlyStopChanges())));
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

    /**
     * Loads the contents of the oldest and the most recent revision of the stop's file. Must not be
     * called on the EDT.
     */
    static StopDiffData load(Stop stop) throws Exception {
        final Map<IRevisionedFile, IRevisionedFile> history = stop.getHistory();
        final IRevisionedFile oldFile = history.isEmpty() ? null : history.keySet().iterator().next();
        final IRevisionedFile newFile = stop.getMostRecentFile();
        final String oldText = oldFile == null ? "" : readContents(oldFile);
        final String newText = readContents(newFile);
        final String oldTitle = oldFile == null ? "Before" : "Before (" + describe(oldFile.getRevision()) + ")";
        final String newTitle = "After (" + describe(newFile.getRevision()) + ")";
        final int line = stop.isDetailedFragmentKnown() ? stop.getMostRecentFragment().getFrom().getLine() : 1;
        final List<StopDiffExcerpt.Change> changes = compare(oldText, newText);
        return new StopDiffData(newFile.getPath(), oldText, newText, oldTitle, newTitle, line, changes,
                createExcerpt(stop, oldText, newText, changes));
    }

    /**
     * Returns the changed line ranges between the texts (empty if the texts are too big to compare).
     */
    private static List<StopDiffExcerpt.Change> compare(String oldText, String newText) {
        final List<LineFragment> fragments;
        try {
            fragments = ComparisonManager.getInstance().compareLines(
                    oldText, newText, ComparisonPolicy.DEFAULT, DumbProgressIndicator.INSTANCE);
        } catch (final DiffTooBigException e) {
            return null;
        }
        final List<StopDiffExcerpt.Change> changes = new ArrayList<>();
        for (final LineFragment f : fragments) {
            changes.add(new StopDiffExcerpt.Change(
                    f.getStartLine1(), f.getEndLine1(), f.getStartLine2(), f.getEndLine2()));
        }
        return changes;
    }

    private static StopDiffExcerpt createExcerpt(Stop stop, String oldText, String newText,
            List<StopDiffExcerpt.Change> changes) {
        if (!stop.isDetailedFragmentKnown() || changes == null) {
            return null;
        }
        final IFragment fragment = stop.getMostRecentFragment();
        final int stopStart = fragment.getFrom().getLine() - 1;
        final int stopEnd = Math.max(stopStart,
                fragment.getTo().getColumn() > 1 ? fragment.getTo().getLine() : fragment.getTo().getLine() - 1);
        return StopDiffExcerpt.create(oldText, newText, changes, stopStart, stopEnd, CONTEXT_LINES);
    }

    /**
     * Creates the diff request for the loaded data, scrolled to the stop's code. If "onlyStopChanges" is
     * set, only the changed sections of the stop are shown (with their original line numbers), otherwise
     * the whole file. Must be called on the EDT.
     */
    static SimpleDiffRequest createRequest(Project project, Stop stop, StopDiffData data, boolean onlyStopChanges) {
        final VirtualFile vf = IntellijFileResolver.findByAbsoluteFile(stop.getAbsoluteFile());
        final FileType fileType = vf != null
                ? vf.getFileType()
                : FileTypeManager.getInstance().getFileTypeByFileName(data.fileName);
        final DiffContentFactory factory = DiffContentFactory.getInstance();
        final StopDiffExcerpt excerpt = onlyStopChanges ? data.excerpt : null;
        if (excerpt == null) {
            final DiffContent left = oldContent(project, factory, data.oldText, fileType);
            final DocumentContent right = factory.create(project, data.newText, fileType);
            final SimpleDiffRequest request = new SimpleDiffRequest(
                    "Review stop: " + data.fileName, left, right, data.oldTitle, data.newTitle);
            request.putUserData(DiffUserDataKeys.SCROLL_TO_LINE, Pair.create(Side.RIGHT, Math.max(0, data.line - 1)));
            addRemarkSupport(request, new StopDiffTarget(stop, data, null));
            return request;
        }
        final DiffContent left = oldContent(project, factory, excerpt.getOldText(), fileType);
        left.putUserData(DiffUserDataKeysEx.LINE_NUMBER_CONVERTOR, excerpt::toOldLine);
        final DocumentContent right = factory.create(project, excerpt.getNewText(), fileType);
        right.putUserData(DiffUserDataKeysEx.LINE_NUMBER_CONVERTOR, excerpt::toNewLine);
        final SimpleDiffRequest request = new SimpleDiffRequest(
                "Review stop: " + data.fileName + " (only the stop's changes)", left, right,
                data.oldTitle + " - stop's changes only", data.newTitle + " - stop's changes only");
        request.putUserData(DiffUserDataKeys.SCROLL_TO_LINE,
                Pair.create(Side.RIGHT, excerpt.toNewExcerptLine(Math.max(0, data.line - 1))));
        addRemarkSupport(request, new StopDiffTarget(stop, data, excerpt));
        return request;
    }

    /**
     * Makes it possible to add review remarks in the diff: the action is shown in the diff's toolbar
     * (and in its context menus, see plugin.xml) and maps the line to the current file.
     */
    private static void addRemarkSupport(SimpleDiffRequest request, StopDiffTarget target) {
        request.putUserData(STOP_DIFF_TARGET, target);
        request.putUserData(DiffUserDataKeys.CONTEXT_ACTIONS, Collections.singletonList(addRemarkAction()));
    }

    private static AnAction addRemarkAction() {
        for (final String id : ADD_REMARK_ACTION_IDS) {
            final AnAction action = ActionManager.getInstance().getAction(id);
            if (action != null) {
                return action;
            }
        }
        final AnAction action = new CortActions.AddRemark();
        action.getTemplatePresentation().setText("Add Review Remark (CoRT)...");
        action.getTemplatePresentation().setIcon(AllIcons.General.Add);
        return action;
    }

    /**
     * The content for the old side of the diff. An empty old text (a new file) is shown as empty
     * content, otherwise the diff would match an empty line of the new file with the empty old
     * "line" and show the new file as two changes.
     */
    private static DiffContent oldContent(Project project, DiffContentFactory factory, String text, FileType type) {
        return text.isEmpty() ? factory.createEmpty() : factory.create(project, text, type);
    }

    private static String describe(IRevision revision) {
        if (revision instanceof ILocalRevision) {
            return "local changes";
        } else if (revision instanceof IUnknownRevision) {
            return "new file";
        }
        return describeRevisionText(revision.toString());
    }

    /**
     * Shortens the textual form of a git revision ("hash (seconds)") to the abbreviated hash and a
     * readable commit time. Other revisions (e.g. SVN revision numbers) are returned unchanged.
     */
    static String describeRevisionText(String text) {
        final Matcher m = GIT_REVISION.matcher(text);
        if (!m.matches()) {
            return text;
        }
        final Date time = new Date(Long.parseLong(m.group(2)) * 1000L);
        return m.group(1).substring(0, 7) + ", " + new SimpleDateFormat("yyyy-MM-dd HH:mm").format(time);
    }

    private static String readContents(IRevisionedFile file) throws Exception {
        final byte[] contents = file.getContents();
        return contents == null ? "" : new String(contents, StandardCharsets.UTF_8);
    }

}
