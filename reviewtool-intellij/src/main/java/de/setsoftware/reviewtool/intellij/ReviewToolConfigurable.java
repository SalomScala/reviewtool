package de.setsoftware.reviewtool.intellij;

import java.awt.FlowLayout;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.TitledSeparator;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPasswordField;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;

import de.setsoftware.reviewtool.intellij.ReviewToolSettings.SettingsState;

/**
 * Settings page for the review tool (Settings | Tools | Code Review Tool (CoRT)). The settings are
 * grouped by topic, and the YouTrack connection and field names can be checked with "Test Connection".
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
    private StopOrderingTable stopOrderingTable;
    /** The token as loaded from the settings (null while it is loaded in the background). */
    private String loadedToken;

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
        this.stopOrderingTable = new StopOrderingTable();

        this.urlField.getEmptyText().setText("e.g. https://youtrack.example.com");
        final JButton testButton = new JButton("Test Connection");
        testButton.addActionListener((e) -> this.testConnection());
        final JPanel testPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        testPanel.add(testButton);

        this.panel = FormBuilder.createFormBuilder()
                .addComponent(new TitledSeparator("YouTrack Connection"))
                .addLabeledComponent("URL:", this.urlField)
                .addLabeledComponent("Permanent token:", this.tokenField)
                .addComponentToRightColumn(hint("In YouTrack: Profile | Account Security | Tokens"))
                .addComponentToRightColumn(testPanel)
                .addComponent(new TitledSeparator("Ticket Fields"))
                .addLabeledComponent("Review remarks (text field):", this.reviewFieldField)
                .addLabeledComponent("State:", this.stateFieldField)
                .addLabeledComponent("Component/subsystem:", this.componentFieldField)
                .addComponent(new TitledSeparator("Ticket States"))
                .addLabeledComponent("In review:", this.reviewStateField)
                .addLabeledComponent("In implementation (fixing):", this.implementationStateField)
                .addLabeledComponent("Ready for review:", this.readyForReviewStateField)
                .addLabeledComponent("Rejected (remarks to fix):", this.rejectedStateField)
                .addLabeledComponent("Done:", this.doneStateField)
                .addComponent(new TitledSeparator("Ticket Lists"))
                .addLabeledComponent("Tickets to review (query):", this.reviewFilterField)
                .addLabeledComponent("Tickets to fix (query):", this.fixingFilterField)
                .addComponentToRightColumn(hint("YouTrack search queries, e.g. State: {Ready for Review}"))
                .addLabeledComponent("Ticket link pattern:", this.ticketLinkPatternField)
                .addComponentToRightColumn(hint("Empty = derived from the URL; otherwise a URL with ${key}"))
                .addComponent(new TitledSeparator("Commits and Diffs"))
                .addLabeledComponent("Commit message pattern:", this.logMessagePatternField)
                .addComponentToRightColumn(hint("Regular expression for the commits of a ticket; ${key} is the"
                        + " ticket key"))
                .addLabeledComponent("Max file size for textual diff (bytes):", this.maxDiffThresholdField)
                .addComponent(new TitledSeparator("Grouping and Order of the Stops in a Tour"))
                .addComponent(new JBLabel("<html>The active relations are used (in this priority order) to group"
                        + " and sort the stops when the tours are created.</html>"))
                .addComponent(this.stopOrderingTable.getComponent())
                .addComponentFillVertically(new JPanel(), 0)
                .getPanel();
        return this.panel;
    }

    private static JBLabel hint(String text) {
        final JBLabel label = new JBLabel(text);
        label.setComponentStyle(UIUtil.ComponentStyle.SMALL);
        label.setFontColor(UIUtil.FontColor.BRIGHTER);
        label.setBorder(JBUI.Borders.emptyBottom(4));
        return label;
    }

    /**
     * Checks the entered (not yet saved) connection settings and field names against YouTrack.
     */
    private void testConnection() {
        final SettingsState s = new SettingsState();
        try {
            this.copyFormTo(s);
        } catch (final ConfigurationException e) {
            Messages.showErrorDialog(this.panel, e.getMessageHtml().toString(), "Test Connection");
            return;
        }
        final String enteredToken = new String(this.tokenField.getPassword());
        final boolean tokenLoaded = this.loadedToken != null;
        final AtomicReference<String> report = new AtomicReference<>();
        final AtomicReference<Exception> error = new AtomicReference<>();
        ProgressManager.getInstance().runProcessWithProgressSynchronously(() -> {
            try {
                final String token = enteredToken.isEmpty() && !tokenLoaded
                        ? this.getSettings().getYoutrackToken() : enteredToken;
                report.set(ReviewToolService.getInstance(this.project).createTicketConnector(s, token)
                        .testConnection());
            } catch (final RuntimeException e) {
                error.set(e);
            }
        }, "Testing the YouTrack Connection", true, this.project);
        if (error.get() != null) {
            Messages.showErrorDialog(this.panel, "The connection to YouTrack failed:\n" + error.get().getMessage(),
                    "Test Connection");
        } else if (report.get() != null) {
            if (report.get().contains("Warning")) {
                Messages.showWarningDialog(this.panel, report.get(), "Test Connection");
            } else {
                Messages.showInfoMessage(this.panel, report.get() + "The settings look fine.", "Test Connection");
            }
        }
    }

    private ReviewToolSettings getSettings() {
        return ReviewToolSettings.getInstance(this.project);
    }

    @Override
    public boolean isModified() {
        final SettingsState s = this.getSettings().getState();
        return !this.urlField.getText().equals(s.youtrackUrl)
                || this.isTokenModified()
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
                || !this.maxDiffThresholdField.getText().equals(Long.toString(s.maxTextDiffFileSizeThreshold))
                || !this.stopOrderingTable.getSettings().equals(StopOrderingSettings.normalize(s.stopOrdering));
    }

    @Override
    public void apply() throws ConfigurationException {
        this.copyFormTo(this.getSettings().getState());
        if (this.isTokenModified()) {
            this.loadedToken = new String(this.tokenField.getPassword());
            this.getSettings().setYoutrackToken(this.loadedToken);
        }
        ReviewToolService.getInstance(this.project).settingsChanged();
    }

    private void copyFormTo(SettingsState s) throws ConfigurationException {
        final long threshold;
        try {
            threshold = Long.parseLong(this.maxDiffThresholdField.getText().trim());
        } catch (final NumberFormatException e) {
            throw new ConfigurationException("The max file size for textual diff must be a number.");
        }
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
        s.stopOrdering = this.stopOrderingTable.getSettings();
    }

    @Override
    public void reset() {
        final SettingsState s = this.getSettings().getState();
        this.urlField.setText(s.youtrackUrl);
        this.resetToken();
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
        this.stopOrderingTable.setSettings(s.stopOrdering);
    }

    private boolean isTokenModified() {
        final String entered = new String(this.tokenField.getPassword());
        // while the token is loading, only a typed token counts as change
        return this.loadedToken == null ? !entered.isEmpty() : !entered.equals(this.loadedToken);
    }

    /**
     * Shows the stored token. Reading the password safe can be slow, so it is done in the background
     * if the token has not been read yet.
     */
    private void resetToken() {
        final ReviewToolSettings settings = this.getSettings();
        final String cached = settings.getYoutrackTokenIfLoaded();
        if (cached != null) {
            this.loadedToken = cached;
            this.tokenField.setText(cached);
            return;
        }
        this.loadedToken = null;
        this.tokenField.setText("");
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            final String token = settings.getYoutrackToken();
            ApplicationManager.getApplication().invokeLater(() -> {
                if (this.panel != null && this.loadedToken == null && this.tokenField.getPassword().length == 0) {
                    this.loadedToken = token;
                    this.tokenField.setText(token);
                }
            }, ModalityState.any());
        });
    }

    @Override
    public void disposeUIResources() {
        this.panel = null;
    }

}
