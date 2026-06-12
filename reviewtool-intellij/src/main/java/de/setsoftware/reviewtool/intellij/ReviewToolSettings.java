package de.setsoftware.reviewtool.intellij;

import com.intellij.credentialStore.CredentialAttributes;
import com.intellij.credentialStore.CredentialAttributesKt;
import com.intellij.ide.passwordSafe.PasswordSafe;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.project.Project;

/**
 * Project level settings for the review tool. The YouTrack token is not part of the
 * persisted state but stored in the IDE's password safe.
 */
@Service(Service.Level.PROJECT)
@State(name = "CortReviewTool", storages = @Storage("cortReviewTool.xml"))
public final class ReviewToolSettings implements PersistentStateComponent<ReviewToolSettings.SettingsState> {

    /**
     * The serialized form of the settings.
     */
    public static class SettingsState {
        public String youtrackUrl = "";
        public String reviewFieldName = "Review remarks";
        public String stateFieldName = "State";
        public String componentFieldName = "Subsystem";
        public String reviewStateName = "In Review";
        public String implementationStateName = "In Progress";
        public String readyForReviewStateName = "Ready for Review";
        public String rejectedStateName = "Reopened";
        public String doneStateName = "Done";
        public String reviewFilterQuery = "State: {Ready for Review}";
        public String fixingFilterQuery = "State: Reopened";
        public String ticketLinkPattern = "";
        public String logMessagePattern = ".*${key}([^0-9].*)?";
        public long maxTextDiffFileSizeThreshold = 1048576;
    }

    private static final CredentialAttributes TOKEN_ATTRIBUTES =
            new CredentialAttributes(CredentialAttributesKt.generateServiceName("CoRT", "YouTrack"));

    private SettingsState state = new SettingsState();

    public static ReviewToolSettings getInstance(Project project) {
        return project.getService(ReviewToolSettings.class);
    }

    @Override
    public SettingsState getState() {
        return this.state;
    }

    @Override
    public void loadState(SettingsState state) {
        this.state = state;
    }

    public String getYoutrackToken() {
        final String token = PasswordSafe.getInstance().getPassword(TOKEN_ATTRIBUTES);
        return token == null ? "" : token;
    }

    public void setYoutrackToken(String token) {
        PasswordSafe.getInstance().setPassword(TOKEN_ATTRIBUTES, token);
    }

}
