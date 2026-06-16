package de.setsoftware.reviewtool.intellij;

import java.awt.Component;
import java.util.ArrayList;
import java.util.List;

import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.components.JBScrollPane;

/**
 * Dialog that lets the user choose how the changes should be grouped into review tours (e.g. "one
 * tour per commit" vs. a merged structure). This is the IntelliJ counterpart of the Eclipse
 * {@code SelectTourStructureDialog}; it shows the available structures as radio buttons so the
 * merge option is clearly visible and selectable.
 */
public final class SelectTourStructureDialog extends DialogWrapper {

    private final List<String> options;
    private final List<JRadioButton> buttons = new ArrayList<>();

    public SelectTourStructureDialog(Project project, List<String> options) {
        super(project);
        this.options = options;
        this.setTitle("Choose Review Tour Structure");
        this.init();
    }

    @Override
    protected JComponent createCenterPanel() {
        final JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        final JLabel label = new JLabel("Choose how the changes should be grouped into review tours:");
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(label);
        final ButtonGroup group = new ButtonGroup();
        for (int i = 0; i < this.options.size(); i++) {
            final JRadioButton button = new JRadioButton(this.options.get(i));
            button.setAlignmentX(Component.LEFT_ALIGNMENT);
            if (i == 0) {
                button.setSelected(true);
            }
            group.add(button);
            this.buttons.add(button);
            panel.add(button);
        }
        return new JBScrollPane(panel);
    }

    /**
     * Returns the index of the selected structure (0 if none, which cannot normally happen).
     */
    public int getSelectedIndex() {
        for (int i = 0; i < this.buttons.size(); i++) {
            if (this.buttons.get(i).isSelected()) {
                return i;
            }
        }
        return 0;
    }

}
