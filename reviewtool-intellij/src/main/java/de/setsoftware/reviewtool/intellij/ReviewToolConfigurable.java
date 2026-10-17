package de.setsoftware.reviewtool.intellij;

import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.ui.ComboboxSpeedSearch;
import com.intellij.ui.TitledSeparator;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPasswordField;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;

import de.setsoftware.reviewtool.intellij.OpencodeClient.ModelInfo;
import de.setsoftware.reviewtool.intellij.OpencodeServer.Probe;
import de.setsoftware.reviewtool.intellij.ReviewToolSettings.SettingsState;

/**
 * Settings page for the review tool (Settings | Tools | Code Review Tool (CoRT)). The settings are
 * grouped by topic, and the YouTrack connection and field names can be checked with "Test Connection".
 */
public class ReviewToolConfigurable implements Configurable {

    /**
     * An entry of the model selection: the default of the opencode configuration (empty id) or a model.
     */
    static final class ModelItem {
        private final String id;
        private final String label;

        ModelItem(String id, String label) {
            this.id = id;
            this.label = label;
        }

        String getId() {
            return this.id;
        }

        @Override
        public String toString() {
            return this.label;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof ModelItem && ((ModelItem) o).id.equals(this.id);
        }

        @Override
        public int hashCode() {
            return this.id.hashCode();
        }
    }

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
    private JBCheckBox aiEnabledBox;
    private JBCheckBox showRemarksInEditorBox;
    private JBCheckBox refactoringsFirstBox;
    private JBCheckBox testsFirstBox;
    private JBTextField opencodePathField;
    private ComboBox<ModelItem> modelCombo;
    private JBLabel modelStatsLabel;
    private JBCheckBox aiAfterOwnReviewBox;
    /** The token as loaded from the settings (null while it is loaded in the background). */
    private String loadedToken;

    public ReviewToolConfigurable(Project project) {
        this.project = project;
    }

    /**
     * Opens the CoRT settings in a dialog of their own. (Opening the whole settings dialog creates all
     * settings pages of the IDE on the EDT, which the IDE reports as a slow operation of CoRT.)
     */
    public static void show(Project project) {
        ShowSettingsUtil.getInstance().editConfigurable(project, new ReviewToolConfigurable(project));
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
        this.refactoringsFirstBox = new JBCheckBox("Stops that are only refactorings first (e.g. a renamed method and"
                + " its calls)");
        this.testsFirstBox = new JBCheckBox("Tests before the production code");
        this.showRemarksInEditorBox = new JBCheckBox("Show the text of the review remarks at the end of their lines in the editor");
        this.aiEnabledBox = new JBCheckBox("Enable AI support via opencode (summary of the changes, explanations of stops)");
        this.opencodePathField = new JBTextField();
        this.opencodePathField.getEmptyText().setText("opencode (from the PATH)");
        final JButton checkOpencodeButton = new JButton("Check opencode");
        checkOpencodeButton.addActionListener((e) -> this.checkOpencode());
        final JButton loadModelsButton = new JButton("Load Models");
        loadModelsButton.addActionListener((e) -> this.loadModels());
        final JPanel checkOpencodePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        checkOpencodePanel.add(checkOpencodeButton);
        checkOpencodePanel.add(Box.createHorizontalStrut(JBUI.scale(6)));
        checkOpencodePanel.add(loadModelsButton);
        // a fixed width: opencode can offer hundreds of models with long names
        this.modelCombo = new ComboBox<>(JBUI.scale(420));
        ComboboxSpeedSearch.installSpeedSearch(this.modelCombo, ModelItem::toString);
        this.modelStatsLabel = hint("");
        this.modelCombo.addActionListener((e) -> this.updateModelStats());
        this.aiAfterOwnReviewBox = new JBCheckBox("Offer AI remark suggestions only after my own review of the code"
                + " (recommended)");

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
                .addComponent(this.showRemarksInEditorBox)
                .addComponent(new TitledSeparator("AI Support (opencode)"))
                .addComponent(this.aiEnabledBox)
                .addComponent(hint("<html>CoRT starts your local opencode in the project directory and sends the diffs"
                        + " of the review<br>to the chosen model. The model may read the project's"
                        + " files but cannot change anything.</html>"))
                .addLabeledComponent("opencode executable:", this.opencodePathField)
                .addLabeledComponent("Model:", this.modelCombo)
                .addComponentToRightColumn(hint("\"Load Models\" lists the models opencode can use; type to search"))
                .addComponentToRightColumn(this.modelStatsLabel)
                .addComponentToRightColumn(checkOpencodePanel)
                .addComponent(this.aiAfterOwnReviewBox)
                .addComponent(hint("<html>The suggestions are a second opinion: in a study, reviewers who saw an AI"
                        + " review first looked at less of the code<br>and did not find more of the severe problems."
                        + " Otherwise, \"Ask the AI anyway\" is offered.</html>"))
                .addComponent(new TitledSeparator("Grouping and Order of the Stops in a Tour"))
                .addComponent(new JBLabel("<html>The active relations are used (in this priority order) to group"
                        + " and sort the stops when the tours are created.</html>"))
                .addComponent(this.stopOrderingTable.getComponent())
                .addComponent(this.refactoringsFirstBox)
                .addComponent(this.testsFirstBox)
                .addComponent(hint("<html>They are moved to the front of their group; the groups stay together."
                        + " Applies to tours created afterwards (\"Create Tours\").</html>"))
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

    /**
     * Starts opencode with the entered executable and shows its version and model (and updates the list
     * of the models).
     */
    private void checkOpencode() {
        final Probe probe = this.probeOpencode("Checking opencode", "Check opencode");
        if (probe != null) {
            this.setModels(probe);
            Messages.showInfoMessage(this.panel, probe.report(this.getSelectedModel()), "Check opencode");
        }
    }

    /**
     * Asks opencode for the models it offers and shows them in the model selection.
     */
    private void loadModels() {
        final Probe probe = this.probeOpencode("Loading the models of opencode", "Load Models");
        if (probe != null) {
            this.setModels(probe);
            if (probe.getModels().isEmpty()) {
                Messages.showWarningDialog(this.panel, "opencode offers no models. Configure a provider in"
                        + " opencode, e.g. with \"opencode auth login\".", "Load Models");
            } else {
                // with the focus in the combo box, typing searches the models
                this.modelCombo.requestFocusInWindow();
                this.modelCombo.showPopup();
            }
        }
    }

    /**
     * Starts opencode with the entered executable in the background and asks it for its version and
     * models. Shows errors and returns null if there was one (or the user canceled).
     */
    private Probe probeOpencode(String progressTitle, String dialogTitle) {
        final String executable = this.opencodePathField.getText().trim().isEmpty()
                ? "opencode" : this.opencodePathField.getText().trim();
        final AtomicReference<Probe> result = new AtomicReference<>();
        final AtomicReference<Exception> error = new AtomicReference<>();
        final BooleanSupplier canceled = () -> ProgressManager.getInstance().getProgressIndicator() != null
                && ProgressManager.getInstance().getProgressIndicator().isCanceled();
        ProgressManager.getInstance().runProcessWithProgressSynchronously(() -> {
            try {
                result.set(OpencodeServer.getInstance(this.project).probe(executable, canceled));
            } catch (final RuntimeException e) {
                error.set(e);
            }
        }, progressTitle, true, this.project);
        if (error.get() != null) {
            Messages.showErrorDialog(this.panel, String.valueOf(error.get().getMessage()), dialogTitle);
            return null;
        }
        return result.get();
    }

    /**
     * Shows the models of the probe in the model selection (keeping the selected model).
     */
    private void setModels(Probe probe) {
        this.showModels(probe, this.getSelectedModel());
    }

    private void showModels(Probe probe, String selected) {
        this.modelCombo.removeAllItems();
        for (final ModelItem item : modelItems(probe, selected)) {
            this.modelCombo.addItem(item);
            if (item.getId().equals(selected)) {
                this.modelCombo.setSelectedItem(item);
            }
        }
    }

    /**
     * Returns the entries of the model selection: the default of the opencode configuration, the models
     * opencode offers (if known) and the selected model (if it is not one of them).
     *
     * @param probe The models opencode offers, or null if they are unknown (not loaded yet).
     */
    static List<ModelItem> modelItems(Probe probe, String selected) {
        final List<ModelItem> ret = new ArrayList<>();
        final String configured = probe == null ? null : probe.getConfiguredModel();
        ret.add(new ModelItem("", "Default of the opencode configuration"
                + (configured == null ? "" : " (" + configured + ")")));
        if (probe != null) {
            for (final ModelInfo model : probe.getModels()) {
                ret.add(new ModelItem(model.getId(), model.toString()));
            }
        }
        if (!selected.isEmpty() && !ret.contains(new ModelItem(selected, ""))) {
            ret.add(new ModelItem(selected, probe == null ? selected : selected + "  (not available in opencode)"));
        }
        return ret;
    }

    /**
     * Shows how many suggestions of the selected model the reviewer accepted so far (counted locally).
     */
    private void updateModelStats() {
        final AiSuggestionStats allStats = AiSuggestionStats.getInstance();
        String model = this.getSelectedModel();
        if (model.isEmpty()) {
            final Probe probe = OpencodeServer.getInstance(this.project).getLastProbe();
            model = probe == null || probe.getConfiguredModel() == null ? allStats.getLastModel()
                    : probe.getConfiguredModel();
        }
        final String stats = model == null ? null : allStats.describe(model);
        this.modelStatsLabel.setText(stats == null ? "" : "<html>Your decisions on the remark suggestions of "
                + StringUtil.escapeXmlEntities(model) + ":<br>" + StringUtil.escapeXmlEntities(stats) + "</html>");
        this.modelStatsLabel.setVisible(stats != null);
    }

    /**
     * Returns the shown acceptance statistics of the model, or null if none are shown (for tests).
     */
    String getShownModelStats() {
        return this.modelStatsLabel.isVisible() ? this.modelStatsLabel.getText() : null;
    }

    private String getSelectedModel() {
        final Object item = this.modelCombo.getSelectedItem();
        return item instanceof ModelItem ? ((ModelItem) item).getId() : "";
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
                || !this.stopOrderingTable.getSettings().equals(StopOrderingSettings.normalize(s.stopOrdering))
                || this.aiEnabledBox.isSelected() != s.aiEnabled
                || this.showRemarksInEditorBox.isSelected() != s.showRemarksInEditor
                || this.refactoringsFirstBox.isSelected() != s.refactoringStopsFirst
                || this.testsFirstBox.isSelected() != s.testStopsFirst
                || !this.opencodePathField.getText().trim().equals(s.opencodePath)
                || !this.getSelectedModel().equals(Objects.toString(s.aiModel, ""))
                || this.aiAfterOwnReviewBox.isSelected() != s.aiSuggestionsAfterOwnReview;
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
        s.aiEnabled = this.aiEnabledBox.isSelected();
        s.showRemarksInEditor = this.showRemarksInEditorBox.isSelected();
        s.refactoringStopsFirst = this.refactoringsFirstBox.isSelected();
        s.testStopsFirst = this.testsFirstBox.isSelected();
        s.opencodePath = this.opencodePathField.getText().trim();
        s.aiModel = this.getSelectedModel();
        s.aiSuggestionsAfterOwnReview = this.aiAfterOwnReviewBox.isSelected();
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
        this.aiEnabledBox.setSelected(s.aiEnabled);
        this.showRemarksInEditorBox.setSelected(s.showRemarksInEditor);
        this.refactoringsFirstBox.setSelected(s.refactoringStopsFirst);
        this.testsFirstBox.setSelected(s.testStopsFirst);
        this.opencodePathField.setText(s.opencodePath);
        this.showModels(OpencodeServer.getInstance(this.project).getLastProbe(), Objects.toString(s.aiModel, ""));
        this.aiAfterOwnReviewBox.setSelected(s.aiSuggestionsAfterOwnReview);
        this.updateModelStats();
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
