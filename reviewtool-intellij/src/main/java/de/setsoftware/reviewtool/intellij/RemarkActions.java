package de.setsoftware.reviewtool.intellij;

import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

import javax.swing.Icon;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;

import de.setsoftware.reviewtool.base.ReviewtoolException;
import de.setsoftware.reviewtool.model.remarks.ResolutionType;
import de.setsoftware.reviewtool.model.remarks.ReviewRemark;

/**
 * Builds the actions that can be performed on a review remark: jump to its code, mark it as fixed /
 * won't fix / unclear, reopen it, add a comment or delete it. The same action group is used for the
 * quick-fix popup of the remark markers in the editor gutter and for the context menu of the
 * "Remarks" tab, so both offer the same operations. Actions that make no sense for the remark's
 * current resolution are hidden.
 */
final class RemarkActions {

    private RemarkActions() {
    }

    /**
     * Creates the action group.
     * @param remarkSupplier Supplies the remark the actions operate on (may supply null, then the
     *      actions are hidden).
     * @param jumpToCode Jumps to the remark's code, or null if no "Jump to code" action shall be offered.
     */
    static DefaultActionGroup create(
            Project project,
            ReviewRemarksModel model,
            Supplier<ReviewRemark> remarkSupplier,
            Consumer<ReviewRemark> jumpToCode) {
        final DefaultActionGroup group = new DefaultActionGroup();
        if (jumpToCode != null) {
            group.add(new RemarkAction(project, "Jump to Code", AllIcons.Actions.EditSource, remarkSupplier,
                    (r) -> true, jumpToCode));
            group.addSeparator();
        }
        group.add(new RemarkAction(project, "Mark as Fixed", AllIcons.Actions.Checked, remarkSupplier,
                (r) -> r.getResolution() != ResolutionType.FIXED,
                (r) -> model.resolve(r, ResolutionType.FIXED, null)));
        group.add(new RemarkAction(project, "Mark as Fixed with Comment...", null, remarkSupplier,
                (r) -> r.getResolution() != ResolutionType.FIXED,
                (r) -> resolveWithComment(project, model, r, ResolutionType.FIXED, "Mark as Fixed")));
        group.add(new RemarkAction(project, "Mark as Won't Fix...", AllIcons.Actions.Cancel, remarkSupplier,
                (r) -> r.getResolution() != ResolutionType.WONT_FIX,
                (r) -> resolveWithComment(project, model, r, ResolutionType.WONT_FIX, "Mark as Won't Fix")));
        group.add(new RemarkAction(project, "Mark as Unclear...", AllIcons.Actions.Help, remarkSupplier,
                (r) -> r.getResolution() != ResolutionType.QUESTION,
                (r) -> resolveWithComment(project, model, r, ResolutionType.QUESTION, "Mark as Unclear")));
        group.add(new RemarkAction(project, "Reopen", AllIcons.Actions.Rollback, remarkSupplier,
                (r) -> r.getResolution() != ResolutionType.OPEN,
                (r) -> model.resolve(r, ResolutionType.OPEN, null)));
        group.addSeparator();
        group.add(new RemarkAction(project, "Add Comment...", AllIcons.General.Balloon, remarkSupplier,
                (r) -> true,
                (r) -> addComment(project, model, r)));
        group.add(new RemarkAction(project, "Delete Remark...", AllIcons.Actions.GC, remarkSupplier,
                (r) -> true,
                (r) -> delete(project, model, r)));
        return group;
    }

    private static void resolveWithComment(
            Project project, ReviewRemarksModel model, ReviewRemark remark, ResolutionType resolution, String title) {
        final String comment = Messages.showMultilineInputDialog(project,
                "Comment (optional):", title, "", null, null);
        if (comment == null) {
            return;
        }
        model.resolve(remark, resolution, comment);
    }

    private static void addComment(Project project, ReviewRemarksModel model, ReviewRemark remark) {
        final String comment = Messages.showMultilineInputDialog(project,
                "Comment on \"" + shorten(remark.getText()) + "\":", "Add Comment", "", null, null);
        if (comment != null && !comment.trim().isEmpty()) {
            model.addComment(remark, comment);
        }
    }

    private static void delete(Project project, ReviewRemarksModel model, ReviewRemark remark) {
        final int answer = Messages.showYesNoDialog(project,
                "Delete the review remark \"" + shorten(remark.getText()) + "\"?",
                "Delete Review Remark", "Delete", "Cancel", Messages.getQuestionIcon());
        if (answer == Messages.YES) {
            model.delete(remark);
        }
    }

    private static String shorten(String text) {
        final String firstLine = text == null ? "" : text.split("\n", 2)[0];
        return firstLine.length() > 60 ? firstLine.substring(0, 57) + "..." : firstLine;
    }

    /**
     * An action on the remark provided by a supplier.
     */
    private static final class RemarkAction extends DumbAwareAction {
        private final Project project;
        private final Supplier<ReviewRemark> remarkSupplier;
        private final Predicate<ReviewRemark> applicable;
        private final Consumer<ReviewRemark> action;

        RemarkAction(Project project, String text, Icon icon, Supplier<ReviewRemark> remarkSupplier,
                Predicate<ReviewRemark> applicable, Consumer<ReviewRemark> action) {
            super(text, null, icon);
            this.project = project;
            this.remarkSupplier = remarkSupplier;
            this.applicable = applicable;
            this.action = action;
        }

        @Override
        public void update(AnActionEvent e) {
            final ReviewRemark remark = this.remarkSupplier.get();
            boolean visible;
            try {
                visible = remark != null && this.applicable.test(remark);
            } catch (final RuntimeException ex) {
                visible = remark != null;
            }
            e.getPresentation().setEnabledAndVisible(visible);
        }

        @Override
        public ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.EDT;
        }

        @Override
        public void actionPerformed(AnActionEvent e) {
            final ReviewRemark remark = this.remarkSupplier.get();
            if (remark == null) {
                return;
            }
            try {
                this.action.accept(remark);
            } catch (final ReviewtoolException ex) {
                IntellijNotifications.warn(this.project, ex.getMessage());
            }
        }
    }

}
