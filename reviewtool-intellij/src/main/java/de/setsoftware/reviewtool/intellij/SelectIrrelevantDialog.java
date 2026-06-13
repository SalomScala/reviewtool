package de.setsoftware.reviewtool.intellij;

import java.awt.Component;
import java.util.ArrayList;
import java.util.List;

import javax.swing.BoxLayout;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.components.JBScrollPane;

import de.setsoftware.reviewtool.model.api.IClassification;

/**
 * Dialog that lets the user choose which of the automatically detected change classifications
 * should be treated as irrelevant for the current review. This is the IntelliJ counterpart of the
 * Eclipse {@code SelectIrrelevantDialog} (reduced to the classification filters).
 */
public final class SelectIrrelevantDialog extends DialogWrapper {

    private final List<IClassification> classifications;
    private final List<Integer> counts;
    private final List<JCheckBox> checkBoxes = new ArrayList<>();

    public SelectIrrelevantDialog(
            Project project, List<IClassification> classifications, List<Integer> counts) {
        super(project);
        this.classifications = classifications;
        this.counts = counts;
        this.setTitle("Select Irrelevant Changes");
        this.init();
    }

    @Override
    protected JComponent createCenterPanel() {
        final JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        final JLabel label = new JLabel(
                "Mark the automatically detected categories that are irrelevant for this review:");
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(label);
        for (int i = 0; i < this.classifications.size(); i++) {
            final JCheckBox checkBox = new JCheckBox(
                    this.classifications.get(i).getName() + "  (" + this.counts.get(i) + " changes)");
            checkBox.setAlignmentX(Component.LEFT_ALIGNMENT);
            this.checkBoxes.add(checkBox);
            panel.add(checkBox);
        }
        return new JBScrollPane(panel);
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

}
