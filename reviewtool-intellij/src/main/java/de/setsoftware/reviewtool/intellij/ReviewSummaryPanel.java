package de.setsoftware.reviewtool.intellij;

import java.awt.BorderLayout;
import java.awt.FlowLayout;

import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JTree;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.ui.components.JBScrollPane;

import de.setsoftware.reviewtool.intellij.ChangeSummaryGenerator.FileItem;
import de.setsoftware.reviewtool.intellij.ChangeSummaryGenerator.SummaryResult;
import de.setsoftware.reviewtool.model.changestructure.ToursInReview;

/**
 * Shows a structured summary of the changes under review (see {@link ChangeSummaryGenerator}) as a
 * collapsible tree: an overview node, then one node per changed file (with line counts), and below
 * each file the changed types/methods. The tree's expand/collapse provides the folding that the
 * Eclipse summary view offers via hyperlinks.
 */
public final class ReviewSummaryPanel extends JPanel {

    private static final long serialVersionUID = 1L;

    private final Project project;
    private final DefaultMutableTreeNode treeRoot = new DefaultMutableTreeNode("No summary");
    private final DefaultTreeModel treeModel = new DefaultTreeModel(this.treeRoot);
    private final JTree tree = new JTree(this.treeModel);

    private ToursInReview tours;

    public ReviewSummaryPanel(Project project) {
        super(new BorderLayout());
        this.project = project;
        this.buildUi();
    }

    private void buildUi() {
        final JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT));
        final JButton refreshButton = new JButton("Regenerate Summary");
        refreshButton.addActionListener((e) -> this.regenerate());
        toolbar.add(refreshButton);
        this.add(toolbar, BorderLayout.NORTH);

        this.tree.setRootVisible(true);
        this.add(new JBScrollPane(this.tree), BorderLayout.CENTER);
    }

    /**
     * Sets the tours the summary is generated for and regenerates it.
     */
    public void setTours(ToursInReview tours) {
        this.tours = tours;
        this.regenerate();
    }

    private void regenerate() {
        final ToursInReview current = this.tours;
        new Task.Backgroundable(this.project, "Generating review summary", false) {
            @Override
            public void run(ProgressIndicator indicator) {
                final SummaryResult result = ChangeSummaryGenerator.analyze(current);
                ApplicationManager.getApplication().invokeLater(() -> ReviewSummaryPanel.this.showResult(result));
            }
        }.queue();
    }

    private void showResult(SummaryResult result) {
        this.treeRoot.removeAllChildren();
        if (result == null) {
            this.treeRoot.setUserObject("No tours created yet - use \"Create Tours\" first.");
        } else {
            this.treeRoot.setUserObject(String.format(
                    "Review summary: %d tours, %d stops (relevant %d, irrelevant %d), %d files (+%d / -%d)",
                    result.getTourCount(), result.getStopCount(), result.getRelevantCount(),
                    result.getIrrelevantCount(), result.getFiles().size(),
                    result.getTotalAdded(), result.getTotalRemoved()));
            for (final FileItem file : result.getFiles()) {
                final String header = file.isBinary()
                        ? file.getPath() + "  (binary)"
                        : file.getPath() + "  (+" + file.getAdded() + " / -" + file.getRemoved() + ")";
                final DefaultMutableTreeNode fileNode = new DefaultMutableTreeNode(header);
                for (final String part : file.getParts()) {
                    fileNode.add(new DefaultMutableTreeNode(part));
                }
                this.treeRoot.add(fileNode);
            }
        }
        this.treeModel.reload();
        for (int i = 0; i < this.tree.getRowCount(); i++) {
            this.tree.expandRow(i);
        }
    }

}
