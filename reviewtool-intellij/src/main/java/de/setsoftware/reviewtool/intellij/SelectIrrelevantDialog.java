package de.setsoftware.reviewtool.intellij;

import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.event.ActionListener;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.PriorityQueue;

import javax.swing.BoxLayout;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.ui.components.ActionLink;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;

import de.setsoftware.reviewtool.model.api.IClassification;
import de.setsoftware.reviewtool.model.api.ICommit;
import de.setsoftware.reviewtool.model.changestructure.ToursInReview.ReviewRoundInfo;

/**
 * Dialog that lets the user choose the commits to review and which of the automatically detected
 * change classifications should be treated as irrelevant for the current review. This is the IntelliJ
 * counterpart of the Eclipse {@code SelectIrrelevantDialog}: the commits are listed chronologically with
 * the starts of the earlier review rounds in between, so that a further review round can be limited to
 * the commits since the last round (e.g. the fixes for its remarks).
 */
public final class SelectIrrelevantDialog extends DialogWrapper {

    private final List<IClassification> classifications;
    private final List<Integer> counts;
    private final List<ICommit> commits;
    private final List<ReviewRoundInfo> reviewRounds;
    private final ReviewRoundInfo lastReviewedRound;
    private final Collection<? extends ICommit> preselectedCommits;
    private final List<JCheckBox> checkBoxes = new ArrayList<>();
    private final List<JCheckBox> commitBoxes = new ArrayList<>();

    /**
     * Creates the dialog with only the classifications (all commits are reviewed).
     */
    public SelectIrrelevantDialog(
            Project project, List<IClassification> classifications, List<Integer> counts) {
        this(project, classifications, counts, new ArrayList<>(), new ArrayList<>(), null, new ArrayList<>());
    }

    /**
     * Creates the dialog.
     *
     * @param commits The commits of the review, sorted by time. The commit selection is only shown if
     *      there is more than one.
     * @param reviewRounds The earlier review rounds (shown between the commits).
     * @param lastReviewedRound The round after which there are new commits (null if there is none).
     * @param preselectedCommits The commits that are selected initially.
     */
    public SelectIrrelevantDialog(Project project, List<IClassification> classifications, List<Integer> counts,
            List<ICommit> commits, List<ReviewRoundInfo> reviewRounds, ReviewRoundInfo lastReviewedRound,
            Collection<? extends ICommit> preselectedCommits) {
        super(project);
        this.classifications = classifications;
        this.counts = counts;
        this.commits = commits;
        this.reviewRounds = reviewRounds;
        this.lastReviewedRound = lastReviewedRound;
        this.preselectedCommits = preselectedCommits;
        this.setTitle(this.showsCommits() ? "Select Commits and Irrelevant Changes" : "Select Irrelevant Changes");
        this.init();
    }

    private boolean showsCommits() {
        return this.commits.size() > 1;
    }

    @Override
    protected JComponent createCenterPanel() {
        final JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        if (this.showsCommits()) {
            this.addCommits(panel);
        }
        if (!this.classifications.isEmpty()) {
            final JLabel label = new JLabel(
                    "Mark the automatically detected categories that are irrelevant for this review:");
            label.setAlignmentX(Component.LEFT_ALIGNMENT);
            if (this.showsCommits()) {
                label.setBorder(JBUI.Borders.emptyTop(12));
            }
            panel.add(label);
            for (int i = 0; i < this.classifications.size(); i++) {
                final JCheckBox checkBox = new JCheckBox(
                        this.classifications.get(i).getName() + "  (" + this.counts.get(i) + " changes)");
                checkBox.setAlignmentX(Component.LEFT_ALIGNMENT);
                this.checkBoxes.add(checkBox);
                panel.add(checkBox);
            }
        }
        return new JBScrollPane(panel);
    }

    private void addCommits(JPanel panel) {
        final JLabel label = new JLabel("Commits to review:");
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(label);
        if (this.lastReviewedRound != null) {
            final JPanel links = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            links.setAlignmentX(Component.LEFT_ALIGNMENT);
            final int newCommits = CommitSelection.since(this.commits, this.lastReviewedRound).size();
            links.add(new ActionLink("Only the commits since review " + this.lastReviewedRound.getNumber()
                    + " (" + newCommits + ")", (ActionListener) (e) -> this.selectSinceLastRound()));
            links.add(new JLabel("   "));
            links.add(new ActionLink("All commits (" + this.commits.size() + ")", (ActionListener) (e) -> this.selectAll()));
            links.setBorder(JBUI.Borders.empty(2, 0, 4, 0));
            panel.add(links);
        }
        final SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm");
        final PriorityQueue<ReviewRoundInfo> rounds = new PriorityQueue<>(
                (a, b) -> a.getTime().compareTo(b.getTime()));
        for (final ReviewRoundInfo round : this.reviewRounds) {
            if (round.getTime() != null) {
                rounds.add(round);
            }
        }
        for (final ICommit commit : this.commits) {
            while (!rounds.isEmpty() && rounds.peek().getTime().before(commit.getTime())) {
                panel.add(this.roundLabel(rounds.remove(), format));
            }
            final JCheckBox box = new JCheckBox(format.format(commit.getTime()) + "   "
                    + firstLine(commit.getMessage()) + "  (" + commit.getChanges().size()
                    + (commit.getChanges().size() == 1 ? " change)" : " changes)"));
            box.setAlignmentX(Component.LEFT_ALIGNMENT);
            box.setSelected(this.preselectedCommits.contains(commit));
            this.commitBoxes.add(box);
            panel.add(box);
        }
    }

    private JComponent roundLabel(ReviewRoundInfo round, SimpleDateFormat format) {
        final JBLabel label = new JBLabel("──  Review " + round.getNumber() + " started "
                + format.format(round.getTime())
                + (round.getReviewer() == null || round.getReviewer().isEmpty() ? "" : " by " + round.getReviewer())
                + "  ──");
        label.setFontColor(UIUtil.FontColor.BRIGHTER);
        label.setBorder(JBUI.Borders.empty(2, 4));
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        return label;
    }

    private void selectSinceLastRound() {
        final List<ICommit> since = CommitSelection.since(this.commits, this.lastReviewedRound);
        for (int i = 0; i < this.commits.size(); i++) {
            this.commitBoxes.get(i).setSelected(since.contains(this.commits.get(i)));
        }
    }

    private void selectAll() {
        for (final JCheckBox box : this.commitBoxes) {
            box.setSelected(true);
        }
    }

    private static String firstLine(String message) {
        final String trimmed = message == null ? "" : message.trim();
        final int newline = trimmed.indexOf('\n');
        return newline < 0 ? trimmed : trimmed.substring(0, newline).trim();
    }

    @Override
    protected ValidationInfo doValidate() {
        if (this.showsCommits() && this.getSelectedCommits().isEmpty()) {
            return new ValidationInfo("Select at least one commit.");
        }
        return null;
    }

    /**
     * Returns the classifications the user selected as irrelevant.
     */
    public List<IClassification> getSelectedClassifications() {
        final List<IClassification> ret = new ArrayList<>();
        for (int i = 0; i < this.checkBoxes.size(); i++) {
            if (this.checkBoxes.get(i).isSelected()) {
                ret.add(this.classifications.get(i));
            }
        }
        return ret;
    }

    /**
     * Returns the commits the user selected (all commits if the selection was not shown).
     */
    public List<ICommit> getSelectedCommits() {
        if (!this.showsCommits()) {
            return new ArrayList<>(this.commits);
        }
        final List<ICommit> ret = new ArrayList<>();
        for (int i = 0; i < this.commitBoxes.size(); i++) {
            if (this.commitBoxes.get(i).isSelected()) {
                ret.add(this.commits.get(i));
            }
        }
        return ret;
    }

}
