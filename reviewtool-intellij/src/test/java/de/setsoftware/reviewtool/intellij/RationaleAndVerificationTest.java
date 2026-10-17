package de.setsoftware.reviewtool.intellij;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

import de.setsoftware.reviewtool.model.remarks.ResolutionType;

/**
 * Tests for the rationale of the changes (ticket description, commit messages) and the hints for
 * verifying the remarks of earlier review rounds.
 */
public class RationaleAndVerificationTest {

    @Test
    public void testVerificationHints() {
        assertEquals("code changed nearby", ReviewRemarksPanel.verificationHint(ResolutionType.FIXED, true));
        assertEquals("no change nearby - check", ReviewRemarksPanel.verificationHint(ResolutionType.FIXED, false));
        assertEquals("code changed nearby - fixed?", ReviewRemarksPanel.verificationHint(ResolutionType.OPEN, true));
        assertNull(ReviewRemarksPanel.verificationHint(ResolutionType.OPEN, false));
        // the author's reply explains these
        assertNull(ReviewRemarksPanel.verificationHint(ResolutionType.WONT_FIX, true));
        assertNull(ReviewRemarksPanel.verificationHint(ResolutionType.QUESTION, false));
        // unknown (e.g. a remark without a line)
        assertNull(ReviewRemarksPanel.verificationHint(ResolutionType.FIXED, null));
    }

    @Test
    public void testRationaleWithDescriptionAndCommits() {
        final String html = ReviewSummaryPanel.rationaleHtml("DEMO-2 Add multiply and sum",
                "Customers need **sums**.", Arrays.asList("DEMO-2 Add multiply and sum", "DEMO-2 Javadoc <b>"));
        assertTrue(html, html.contains("<b>Why: DEMO-2 Add multiply and sum</b>"));
        assertTrue(html, html.contains("<b>sums</b>"));
        assertTrue(html, html.contains("<li>DEMO-2 Javadoc &lt;b&gt;</li>"));
    }

    @Test
    public void testRationaleWithoutDescription() {
        final String html = ReviewSummaryPanel.rationaleHtml("DEMO-3", " ", Collections.<String>emptyList());
        assertTrue(html, html.contains("The ticket has no description, the reason for the changes is not stated"));
        assertFalse(html, html.contains("Commits:"));
        assertTrue(ReviewSummaryPanel.rationaleHtml("DEMO-3", null, Collections.<String>emptyList())
                .contains("Loading the ticket description"));
    }

    @Test
    public void testRationaleForTheModel() {
        final ReviewAiPrompts.StopForAi stop = new ReviewAiPrompts.StopForAi(1, "tour", "A.java", "3", null, "+x\n");
        final String withRationale = ReviewAiPrompts.reviewContext("DEMO-2 Sum",
                "Ticket description:\nCustomers need sums.", Arrays.asList(stop), 10000).getText();
        assertTrue(withRationale, withRationale.startsWith("# Changes to review: DEMO-2 Sum\n\n"
                + "## Why the changes have been made (ticket and commits)\n\n"
                + "Ticket description:\nCustomers need sums."));
        final String without = ReviewAiPrompts.reviewContext("DEMO-2 Sum", Arrays.asList(stop), 10000).getText();
        assertFalse(without, without.contains("Why the changes"));
        assertTrue(ReviewAiPrompts.summaryRequest().contains("If they do not state it, say so instead of guessing."));
    }

}
