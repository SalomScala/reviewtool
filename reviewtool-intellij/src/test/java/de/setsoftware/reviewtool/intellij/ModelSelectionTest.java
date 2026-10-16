package de.setsoftware.reviewtool.intellij;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import de.setsoftware.reviewtool.intellij.OpencodeClient.ModelInfo;
import de.setsoftware.reviewtool.intellij.OpencodeServer.Probe;
import de.setsoftware.reviewtool.intellij.ReviewToolConfigurable.ModelItem;

/**
 * Tests for the model selection in the settings.
 */
public class ModelSelectionTest {

    private static Probe probe() {
        return new Probe("1.19.0", "acme/review-model", List.of(
                new ModelInfo("acme", "Acme AI", "big", "Big Model"),
                new ModelInfo("acme", "Acme AI", "review-model", "Review Model")));
    }

    private static List<String> labels(List<ModelItem> items) {
        final List<String> ret = new ArrayList<>();
        for (final ModelItem item : items) {
            ret.add(item.getId() + "=" + item);
        }
        return ret;
    }

    @Test
    public void testItemsBeforeTheModelsAreLoaded() {
        assertEquals(List.of("=Default of the opencode configuration"),
                labels(ReviewToolConfigurable.modelItems(null, "")));
        // the chosen model is kept even though its name is not known yet
        assertEquals(List.of("=Default of the opencode configuration", "acme/big=acme/big"),
                labels(ReviewToolConfigurable.modelItems(null, "acme/big")));
    }

    @Test
    public void testItemsWithLoadedModels() {
        assertEquals(List.of("=Default of the opencode configuration (acme/review-model)",
                "acme/big=Acme AI: Big Model  (acme/big)",
                "acme/review-model=Acme AI: Review Model  (acme/review-model)"),
                labels(ReviewToolConfigurable.modelItems(probe(), "acme/big")));
        // a chosen model opencode does not offer (any more) is marked
        final List<String> withMissing = labels(ReviewToolConfigurable.modelItems(probe(), "other/gone"));
        assertEquals(4, withMissing.size());
        assertEquals("other/gone=other/gone  (not available in opencode)", withMissing.get(3));
    }

    @Test
    public void testReport() {
        final String withDefault = probe().report("");
        assertTrue(withDefault, withDefault.startsWith("opencode 1.19.0 works."));
        assertTrue(withDefault, withDefault.contains("Model (from the opencode configuration): acme/review-model"));
        assertTrue(withDefault, withDefault.contains("Available models: 2"));
        assertTrue(probe().report("acme/big").contains("Model (chosen in the settings): acme/big"));
        final String missing = probe().report("other/gone");
        assertTrue(missing, missing.contains("The model chosen in the settings (other/gone) is not available"));
        final String noDefault = new Probe("1.19.0", null, List.of()).report(null);
        assertTrue(noDefault, noDefault.contains("Model (from the opencode configuration): opencode's default"));
    }

}
