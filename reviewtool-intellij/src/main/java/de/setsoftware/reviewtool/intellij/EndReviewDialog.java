package de.setsoftware.reviewtool.intellij;

import java.awt.BorderLayout;
import java.util.ArrayList;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JRadioButton;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;

import de.setsoftware.reviewtool.model.EndTransition;

/**
 * Dialog that is shown before the review is ended, the IntelliJ counterpart of the Eclipse
 * "EndReviewDialog". It shows the review remarks (which can still be adjusted) and lets the user
 * choose the kind of end: "Pause" only saves the remarks, the other options also change the
 * ticket's state. The option matching the remarks is preselected: "Pause" when there are temporary
 * markers, a rejection when there are remarks that need fixing and "OK" otherwise.
 */
final class EndReviewDialog extends DialogWrapper {

    private final List<EndTransition> transitions;
    private final List<JRadioButton> radioButtons = new ArrayList<>();
    private final JBTextArea remarksArea = new JBTextArea(15, 60);
    private final String summary;
    private final String warning;

    /**
     * Creates the dialog.
     * @param transitions The possible transitions, including the "Pause" pseudo transition.
     * @param remarks The current review remarks.
     * @param preferredType The type of transition that should be preselected.
     * @param summary A short summary of the remarks (e.g. "2 remarks need fixing"), shown above the options.
     * @param warning A warning shown prominently above the options (e.g. about stops that have not been
     *      visited), or null.
     */
    EndReviewDialog(Project project, String ticketKey, List<EndTransition> transitions, String remarks,
            EndTransition.Type preferredType, String summary, String warning) {
        super(project);
        this.transitions = transitions;
        this.summary = summary;
        this.warning = warning;
        this.setTitle("End Review - " + ticketKey);
        this.setOKButtonText("End Review");
        this.remarksArea.setText(remarks);
        this.remarksArea.setLineWrap(true);
        this.remarksArea.setWrapStyleWord(true);

        final ButtonGroup group = new ButtonGroup();
        for (final EndTransition t : transitions) {
            final JRadioButton button = new JRadioButton(label(t));
            group.add(button);
            this.radioButtons.add(button);
        }
        this.selectPreferred(preferredType);
        this.init();
    }

    private static String label(EndTransition t) {
        switch (t.getType()) {
        case PAUSE:
            return t.getNameForUser() + " (only save the remarks, keep the ticket state)";
        case OK:
            return t.getNameForUser() + " (review OK)";
        case REJECTION:
            return t.getNameForUser() + " (remarks need fixing)";
        default:
            return t.getNameForUser();
        }
    }

    private void selectPreferred(EndTransition.Type preferredType) {
        for (int i = 0; i < this.transitions.size(); i++) {
            if (this.transitions.get(i).getType() == preferredType) {
                this.radioButtons.get(i).setSelected(true);
                return;
            }
        }
        if (!this.radioButtons.isEmpty()) {
            this.radioButtons.get(0).setSelected(true);
        }
    }

    @Override
    protected JComponent createCenterPanel() {
        final JPanel panel = new JPanel(new BorderLayout(0, JBUI.scale(8)));
        panel.add(new JBScrollPane(this.remarksArea), BorderLayout.CENTER);

        final JPanel options = new JPanel();
        options.setLayout(new BoxLayout(options, BoxLayout.Y_AXIS));
        options.setBorder(BorderFactory.createTitledBorder("Kind of end"));
        if (this.summary != null && !this.summary.isEmpty()) {
            final JBLabel summaryLabel = new JBLabel(this.summary);
            summaryLabel.setFontColor(UIUtil.FontColor.BRIGHTER);
            options.add(summaryLabel);
        }
        for (final JRadioButton button : this.radioButtons) {
            options.add(button);
        }
        if (this.warning != null) {
            final JBLabel warningLabel = new JBLabel(this.warning, AllIcons.General.Warning, JBLabel.LEADING);
            warningLabel.setBorder(JBUI.Borders.emptyBottom(4));
            final JPanel south = new JPanel(new BorderLayout());
            south.add(warningLabel, BorderLayout.NORTH);
            south.add(options, BorderLayout.CENTER);
            panel.add(south, BorderLayout.SOUTH);
        } else {
            panel.add(options, BorderLayout.SOUTH);
        }
        return panel;
    }

    @Override
    public JComponent getPreferredFocusedComponent() {
        for (final JRadioButton button : this.radioButtons) {
            if (button.isSelected()) {
                return button;
            }
        }
        return this.remarksArea;
    }

    @Override
    protected String getDimensionServiceKey() {
        return "de.setsoftware.reviewtool.EndReviewDialog";
    }

    /**
     * Returns the chosen transition, or null if none is selected.
     */
    EndTransition getSelectedTransition() {
        for (int i = 0; i < this.radioButtons.size(); i++) {
            if (this.radioButtons.get(i).isSelected()) {
                return this.transitions.get(i);
            }
        }
        return null;
    }

    /**
     * Returns the (possibly adjusted) review remarks.
     */
    String getRemarks() {
        return this.remarksArea.getText();
    }

}
