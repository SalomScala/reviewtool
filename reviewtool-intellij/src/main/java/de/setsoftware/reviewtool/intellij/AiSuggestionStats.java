package de.setsoftware.reviewtool.intellij;

import java.util.ArrayList;
import java.util.List;

import com.intellij.ide.util.PropertiesComponent;

/**
 * Counts locally (in the IDE, not in the project) how many remark suggestions of the AI the reviewer
 * accepted and rejected, per model and kind of remark. Explicit feedback on AI suggestions is rare, but
 * accepting or rejecting a suggestion is a reliable signal: the counts show whether a model (or a kind of
 * suggestion) is worth the reviewer's time.
 */
final class AiSuggestionStats {

    private static final String PREFIX = "de.setsoftware.reviewtool.aiSuggestions.";

    private final PropertiesComponent properties;

    AiSuggestionStats(PropertiesComponent properties) {
        this.properties = properties;
    }

    static AiSuggestionStats getInstance() {
        return new AiSuggestionStats(PropertiesComponent.getInstance());
    }

    /**
     * Counts a decision of the reviewer.
     *
     * @param model The model that made the suggestion ("provider/model", null if unknown).
     * @param kind The kind of the suggestion (one of {@link ReviewAiPrompts#REMARK_KINDS}).
     * @param accepted True if the suggestion has been accepted, false if it has been rejected.
     * @param delta 1 to count the decision, -1 to take it back (e.g. "Accept anyway" after "Reject").
     */
    void count(String model, String kind, boolean accepted, int delta) {
        final String key = key(model, kind, accepted);
        this.properties.setValue(key, Math.max(0, this.properties.getInt(key, 0) + delta), 0);
        if (model != null && !model.isEmpty()) {
            this.properties.setValue(PREFIX + "lastModel", model);
        }
    }

    /**
     * Returns the model of the last counted decision (e.g. the default model of the opencode configuration,
     * which the settings do not know by name), or null if there was none.
     */
    String getLastModel() {
        return this.properties.getValue(PREFIX + "lastModel");
    }

    int get(String model, String kind, boolean accepted) {
        return this.properties.getInt(key(model, kind, accepted), 0);
    }

    private static String key(String model, String kind, boolean accepted) {
        return PREFIX + (model == null || model.isEmpty() ? "unknown" : model) + "." + kind
                + (accepted ? ".accepted" : ".rejected");
    }

    /**
     * Describes the decisions on the suggestions of the model, e.g. "4 of 10 suggestions accepted (40%;
     * must 2/3, can 1/5, positive 1/2)", or returns null if there were none.
     */
    String describe(String model) {
        int accepted = 0;
        int all = 0;
        final List<String> perKind = new ArrayList<>();
        for (final String kind : ReviewAiPrompts.REMARK_KINDS) {
            final int a = this.get(model, kind, true);
            final int n = a + this.get(model, kind, false);
            if (n > 0) {
                perKind.add(kind + " " + a + "/" + n);
            }
            accepted += a;
            all += n;
        }
        if (all == 0) {
            return null;
        }
        return accepted + " of " + all + (all == 1 ? " suggestion" : " suggestions") + " accepted ("
                + Math.round(100.0 * accepted / all) + "%; " + String.join(", ", perKind) + ")";
    }

}
