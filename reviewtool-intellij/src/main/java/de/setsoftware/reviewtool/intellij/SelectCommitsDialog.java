package de.setsoftware.reviewtool.intellij;

import java.awt.Component;
import java.awt.Dimension;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.swing.BoxLayout;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.components.JBScrollPane;

import de.setsoftware.reviewtool.changesources.git.GitCommitInfo;

/**
 * Dialog that lets the user select individual Git commits for a review that does not use a ticket
 * system. The selected commits are reviewed together.
 */
public final class SelectCommitsDialog extends DialogWrapper {

    private final List<GitCommitInfo> commits;
    private final List<JCheckBox> checkBoxes = new ArrayList<>();

    public SelectCommitsDialog(Project project, List<GitCommitInfo> commits) {
        super(project);
        this.commits = commits;
        this.setTitle("Select Commits to Review");
        this.init();
    }

    @Override
    protected JComponent createCenterPanel() {
        final JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        if (this.commits.isEmpty()) {
            panel.add(new JLabel("No commits found in the working copy."));
        } else {
            final JLabel label = new JLabel("Select the commits that should be reviewed together:");
            label.setAlignmentX(Component.LEFT_ALIGNMENT);
            panel.add(label);
            final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm");
            for (final GitCommitInfo commit : this.commits) {
                final String shortId = commit.getId().length() >= 8
                        ? commit.getId().substring(0, 8)
                        : commit.getId();
                final JCheckBox checkBox = new JCheckBox(
                        shortId + "  " + dateFormat.format(commit.getDate())
                        + "  " + commit.getSummary() + "  [" + commit.getAuthor() + "]");
                checkBox.setAlignmentX(Component.LEFT_ALIGNMENT);
                this.checkBoxes.add(checkBox);
                panel.add(checkBox);
            }
        }
        final JBScrollPane scrollPane = new JBScrollPane(panel);
        scrollPane.setPreferredSize(new Dimension(700, 400));
        return scrollPane;
    }

    /**
     * Returns the full ids (hashes) of the selected commits.
     */
    public Set<String> getSelectedCommitIds() {
        final Set<String> ret = new LinkedHashSet<>();
        for (int i = 0; i < this.checkBoxes.size(); i++) {
            if (this.checkBoxes.get(i).isSelected()) {
                ret.add(this.commits.get(i).getId());
            }
        }
        return ret;
    }

}
