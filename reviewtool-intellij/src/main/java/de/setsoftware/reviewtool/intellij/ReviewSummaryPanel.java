package de.setsoftware.reviewtool.intellij;

import java.awt.BorderLayout;
import java.awt.FlowLayout;

import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JTextArea;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.ui.components.JBScrollPane;

import de.setsoftware.reviewtool.model.changestructure.ToursInReview;

/**
 * Shows a human readable summary of the changes under review (see {@link ChangeSummaryGenerator}).
 */
public final class ReviewSummaryPanel extends JPanel {

    private static final long serialVersionUID = 1L;

    private final Project project;
    private final JTextArea summaryArea = new JTextArea();

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

        this.summaryArea.setEditable(false);
        this.summaryArea.setLineWrap(false);
        this.summaryArea.setText("No tours created yet. Use \"Create Tours\" to build the review tours first.");
        this.add(new JBScrollPane(this.summaryArea), BorderLayout.CENTER);
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
                final String summary = ChangeSummaryGenerator.generate(current);
                ApplicationManager.getApplication().invokeLater(() -> {
                    ReviewSummaryPanel.this.summaryArea.setText(summary);
                    ReviewSummaryPanel.this.summaryArea.setCaretPosition(0);
                });
            }
        }.queue();
    }

}
