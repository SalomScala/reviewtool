package de.setsoftware.reviewtool.intellij;

import javax.swing.JComponent;
import javax.swing.JPanel;

import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.project.Project;
import com.intellij.ui.components.JBPasswordField;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;

import de.setsoftware.reviewtool.intellij.ReviewToolSettings.SettingsState;

/**
 * Settings page for the review tool (Settings | Tools | Code Review Tool (CoRT)).
 */
public class ReviewToolConfigurable implements Configurable {

    private final Project project;

    private JPanel panel;
    private JBTextField urlField;
    private JBPasswordField tokenField;
    private JBTextField reviewFieldField;
    private JBTextField stateFieldField;
    private JBTextField componentFieldField;
    private JBTextField reviewStateField;
    private JBTextField implementationStateField;
    private JBTextField readyForReviewStateField;
    private JBTextField rejectedStateField;
    private JBTextField doneStateField;
    private JBTextField reviewFilterField;
    private JBTextField fixingFilterField;
    private JBTextField ticketLinkPatternField;
    private JBTextField logMessagePatternField;
    private JBTextField maxDiffThresholdField;

    public ReviewToolConfigurable(Project project) {
        this.project = project;
    }

    @Override
    public String getDisplayName() {
        return "Code Review Tool (CoRT)";
    }

    @Override
    public JComponent createComponent() {
        this.urlField = new JBTextField();
        this.tokenField = new JBPasswordField();
        this.reviewFieldField = new JBTextField();
        this.stateFieldField = new JBTextField();
        this.componentFieldField = new JBTextField();
        this.reviewStateField = new JBTextField();
        this.implementationStateField = new JBTextField();
        this.readyForReviewStateField = new JBTextField();
        this.rejectedStateField = new JBTextField();
        this.doneStateField = new JBTextField();
        this.reviewFilterField = new JBTextField();
        this.fixingFilterField = new JBTextField();
        this.ticketLinkPatternField = new JBTextField();
        this.logMessagePatternField = new JBTextField();
        this.maxDiffThresholdField = new JBTextField();

        this.panel = FormBuilder.createFormBuilder()
                .addLabeledComponent("YouTrack URL:", this.urlField)
                .addLabeledComponent("YouTrack permanent token:", this.tokenField)
                .addLabeledComponent("Review remark field (text custom field):", this.reviewFieldField)
                .addLabeledComponent("State field:", this.stateFieldField)
                .addLabeledComponent("Component/subsystem field:", this.componentFieldField)
                .addLabeledComponent("State name 'in review':", this.reviewStateField)
                .addLabeledComponent("State name 'in implementation':", this.implementationStateField)
                .addLabeledComponent("State name 'ready for review':", this.readyForReviewStateField)
                .addLabeledComponent("State name 'rejected':", this.rejectedStateField)
                .addLabeledComponent("State name 'done':", this.doneStateField)
                .addLabeledComponent("Query for tickets to review:", this.reviewFilterField)
                .addLabeledComponent("Query for tickets to fix:", this.fixingFilterField)
                .addLabeledComponent("Ticket link pattern (empty = derive from URL):", this.ticketLinkPatternField)
                .addLabeledComponent("Commit message pattern (with ${key}):", this.logMessagePatternField)
                .addLabeledComponent("Max file size for textual diff (bytes):", this.maxDiffThresholdField)
                .addComponentFillVertically(new JPanel(), 0)
                .getPanel();
        return this.panel;
    }

    private ReviewToolSettings getSettings() {
        return ReviewToolSettings.getInstance(this.project);
    }

    @Override
    public boolean isModified() {
        final SettingsState s = this.getSettings().getState();
        return !this.urlField.getText().equals(s.youtrackUrl)
                || !new String(this.tokenField.getPassword()).equals(this.getSettings().getYoutrackToken())
                || !this.reviewFieldField.getText().equals(s.reviewFieldName)
                || !this.stateFieldField.getText().equals(s.stateFieldName)
                || !this.componentFieldField.getText().equals(s.componentFieldName)
                || !this.reviewStateField.getText().equals(s.reviewStateName)
                || !this.implementationStateField.getText().equals(s.implementationStateName)
                || !this.readyForReviewStateField.getText().equals(s.readyForReviewStateName)
                || !this.rejectedStateField.getText().equals(s.rejectedStateName)
                || !this.doneStateField.getText().equals(s.doneStateName)
                || !this.reviewFilterField.getText().equals(s.reviewFilterQuery)
                || !this.fixingFilterField.getText().equals(s.fixingFilterQuery)
                || !this.ticketLinkPatternField.getText().equals(s.ticketLinkPattern)
                || !this.logMessagePatternField.getText().equals(s.logMessagePattern)
                || !this.maxDiffThresholdField.getText().equals(Long.toString(s.maxTextDiffFileSizeThreshold));
    }

    @Override
    public void apply() throws ConfigurationException {
        final long threshold;
        try {
            threshold = Long.parseLong(this.maxDiffThresholdField.getText().trim());
        } catch (final NumberFormatException e) {
            throw new ConfigurationException("The max file size for textual diff must be a number.");
        }
        final SettingsState s = this.getSettings().getState();
        s.youtrackUrl = this.urlField.getText().trim();
        s.reviewFieldName = this.reviewFieldField.getText().trim();
        s.stateFieldName = this.stateFieldField.getText().trim();
        s.componentFieldName = this.componentFieldField.getText().trim();
        s.reviewStateName = this.reviewStateField.getText().trim();
        s.implementationStateName = this.implementationStateField.getText().trim();
        s.readyForReviewStateName = this.readyForReviewStateField.getText().trim();
        s.rejectedStateName = this.rejectedStateField.getText().trim();
        s.doneStateName = this.doneStateField.getText().trim();
        s.reviewFilterQuery = this.reviewFilterField.getText().trim();
        s.fixingFilterQuery = this.fixingFilterField.getText().trim();
        s.ticketLinkPattern = this.ticketLinkPatternField.getText().trim();
        s.logMessagePattern = this.logMessagePatternField.getText().trim();
        s.maxTextDiffFileSizeThreshold = threshold;
        this.getSettings().setYoutrackToken(new String(this.tokenField.getPassword()));
        ReviewToolService.getInstance(this.project).settingsChanged();
    }

    @Override
    public void reset() {
        final SettingsState s = this.getSettings().getState();
        this.urlField.setText(s.youtrackUrl);
        this.tokenField.setText(this.getSettings().getYoutrackToken());
        this.reviewFieldField.setText(s.reviewFieldName);
        this.stateFieldField.setText(s.stateFieldName);
        this.componentFieldField.setText(s.componentFieldName);
        this.reviewStateField.setText(s.reviewStateName);
        this.implementationStateField.setText(s.implementationStateName);
        this.readyForReviewStateField.setText(s.readyForReviewStateName);
        this.rejectedStateField.setText(s.rejectedStateName);
        this.doneStateField.setText(s.doneStateName);
        this.reviewFilterField.setText(s.reviewFilterQuery);
        this.fixingFilterField.setText(s.fixingFilterQuery);
        this.ticketLinkPatternField.setText(s.ticketLinkPattern);
        this.logMessagePatternField.setText(s.logMessagePattern);
        this.maxDiffThresholdField.setText(Long.toString(s.maxTextDiffFileSizeThreshold));
    }

    @Override
    public void disposeUIResources() {
        this.panel = null;
    }

}
