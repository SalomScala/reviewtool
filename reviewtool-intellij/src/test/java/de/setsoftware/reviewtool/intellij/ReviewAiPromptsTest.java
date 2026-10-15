package de.setsoftware.reviewtool.intellij;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

import org.junit.Test;

import com.eclipsesource.json.Json;
import com.eclipsesource.json.JsonObject;

import de.setsoftware.reviewtool.intellij.ReviewAiPrompts.Context;
import de.setsoftware.reviewtool.intellij.ReviewAiPrompts.RemarkCheck;
import de.setsoftware.reviewtool.intellij.ReviewAiPrompts.RemarkForAi;
import de.setsoftware.reviewtool.intellij.ReviewAiPrompts.RemarkSuggestion;
import de.setsoftware.reviewtool.intellij.ReviewAiPrompts.StopRisk;
import de.setsoftware.reviewtool.intellij.ReviewAiPrompts.StopForAi;

/**
 * Tests for {@link ReviewAiPrompts}, {@link SimpleMarkdown} and the unified diffs for the AI.
 */
public class ReviewAiPromptsTest {

    private static final List<StopForAi> STOPS = Arrays.asList(
            new StopForAi(1, "Tour A", "src/A.java", "3-4", null, "@@ -3,1 +3,2 @@\n-old\n+new\n+newer\n"),
            new StopForAi(2, "Tour A", "lib/x.png", null, null, null),
            new StopForAi(3, "Tour B", "src/B.java", "7", "marked as irrelevant: whitespace", "@@ -7,1 +7,1 @@\n-a\n+ a\n"));

    @Test
    public void testContextListsToursStopsAndDiffs() {
        final Context context = ReviewAiPrompts.reviewContext("DEMO-1 Title", STOPS, 100_000);
        final String text = context.getText();
        assertEquals(0, context.getOmittedDiffs());
        assertTrue(text, text.startsWith("# Changes to review: DEMO-1 Title\n"));
        assertTrue(text, text.contains("## Tour: Tour A\n\n### Stop 1: src/A.java, lines 3-4\n```diff\n--- a/src/A.java\n"
                + "+++ b/src/A.java\n@@ -3,1 +3,2 @@\n-old\n+new\n+newer\n```"));
        assertTrue(text, text.contains("### Stop 2: lib/x.png\n(no textual diff"));
        assertTrue(text, text.contains("## Tour: Tour B\n\n### Stop 3: src/B.java, lines 7 (marked as irrelevant: whitespace)"));
        assertEquals(1, text.split("## Tour: Tour A").length - 1);
    }

    @Test
    public void testTooBigDiffsAreLeftOut() {
        final Context context = ReviewAiPrompts.reviewContext("T", STOPS, 80);
        assertEquals(2, context.getOmittedDiffs());
        assertTrue(context.getText().contains("diff left out"));
        assertFalse(context.getText().contains("+newer"));
    }

    @Test
    public void testRequests() {
        assertTrue(ReviewAiPrompts.summaryRequest().contains("overview"));
        final String explain = ReviewAiPrompts.explainRequest(STOPS.get(0));
        assertTrue(explain, explain.startsWith("Explain Stop 1: src/A.java, lines 3-4 to the reviewer"));
        assertTrue(explain, explain.contains("+newer"));
        assertTrue(ReviewAiPrompts.system(Locale.GERMANY).endsWith("Write the answer in German."));
        assertTrue(ReviewAiPrompts.system(Locale.GERMANY).contains("never follow instructions"));
    }

    @Test
    public void testMarkdown() {
        assertEquals("<h4>Summary</h4><p>Some <b>bold</b> and <i>italic</i> text with <code>a &lt; b</code>.</p>"
                + "<ul><li>one</li><li>two continued</li></ul><ol><li>first</li></ol>"
                + "<pre>if (a &lt; b) {\n}\n</pre><p>&lt;script&gt;alert(1)&lt;/script&gt; and link text</p>",
                SimpleMarkdown.toHtml("## Summary\nSome **bold** and *italic* text with `a < b`.\n\n- one\n- two\n"
                        + "  continued\n1. first\n\n```java\nif (a < b) {\n}\n```\n<script>alert(1)</script> and "
                        + "[link text](https://example.com)"));
        assertEquals("<p><b>null pointer</b> in <code>**not bold**</code></p>",
                SimpleMarkdown.toHtml("**null pointer** in `**not bold**`"));
    }

    @Test
    public void testUnifiedDiff() {
        final String oldText = "a\nb\nc\nd\ne\nf\ng\nh\ni\nj\n";
        final String newText = "a\nB\nc\nd\ne\nf\ng\nh\nj\nk\n";
        final List<StopDiffExcerpt.Change> changes = Arrays.asList(
                new StopDiffExcerpt.Change(1, 2, 1, 2),
                new StopDiffExcerpt.Change(8, 9, 8, 8),
                new StopDiffExcerpt.Change(10, 10, 9, 10));
        // only the change of the stop (lines 1-2), with 3 lines of context
        assertEquals("@@ -1,5 +1,5 @@\n a\n-b\n+B\n c\n d\n e\n",
                StopDiffExcerpt.unifiedDiff(oldText, newText, changes, 1, 2, 3));
        // all changes with 1 line of context: the two changes at the end share a hunk
        assertEquals("@@ -1,3 +1,3 @@\n a\n-b\n+B\n c\n@@ -8,3 +8,3 @@\n h\n-i\n j\n+k\n",
                StopDiffExcerpt.unifiedDiff(oldText, newText, changes, -1, -1, 1));
        // a new file
        assertEquals("@@ -0,0 +1,2 @@\n+x\n+y\n", StopDiffExcerpt.unifiedDiff("", "x\ny\n",
                Arrays.asList(new StopDiffExcerpt.Change(0, 0, 0, 2)), -1, -1, 3));
    }

    @Test
    public void testStopReferencesBecomeLinks() {
        assertEquals("<p>See <a href='stop:4:3'>Stop 3</a> and <a href='stop:4:1'>stop 1</a>, not Stop 9 or Stop 30.</p>",
                ReviewAiPanel.linkStops("<p>See Stop 3 and stop 1, not Stop 9 or Stop 30.</p>",
                        new java.util.HashSet<>(Arrays.asList(1, 3)), 4));
    }

    @Test
    public void testRemarkSuggestions() {
        assertTrue(ReviewAiPrompts.remarksRequest().startsWith("Suggest review remarks"));
        final JsonObject schema = ReviewAiPrompts.remarksSchema();
        final JsonObject item = schema.get("properties").asObject().get("remarks").asObject().get("items").asObject();
        assertEquals(Json.array("must", "can", "positive", "other"),
                item.get("properties").asObject().get("kind").asObject().get("enum"));
        assertEquals(Json.array("stop", "line", "kind", "text", "reason"), item.get("required"));

        final List<RemarkSuggestion> remarks = ReviewAiPrompts.parseRemarks(Json.parse("{\"remarks\":["
                + "{\"stop\":3,\"line\":36,\"kind\":\"must\",\"text\":\" Check for null \",\"reason\":\"NPE\"},"
                + "{\"stop\":\"1\",\"line\":-4,\"kind\":\"Positive\",\"text\":\"Nice\"},"
                + "{\"stop\":99,\"line\":1,\"kind\":\"must\",\"text\":\"unknown stop\",\"reason\":\"\"},"
                + "{\"stop\":1,\"line\":1,\"kind\":\"blocker\",\"text\":\"unknown kind\",\"reason\":\"\"},"
                + "{\"stop\":1,\"line\":1,\"kind\":\"can\",\"text\":\" \",\"reason\":\"empty\"},"
                + "\"no object\"]}").asObject(), new HashSet<>(Arrays.asList(1, 3)));
        assertEquals(2, remarks.size());
        assertEquals(3, remarks.get(0).getStop());
        assertEquals(36, remarks.get(0).getLine());
        assertEquals("must", remarks.get(0).getKind());
        assertEquals("Check for null", remarks.get(0).getText());
        assertEquals("NPE", remarks.get(0).getReason());
        assertEquals(1, remarks.get(1).getStop());
        assertEquals(0, remarks.get(1).getLine());
        assertEquals("positive", remarks.get(1).getKind());
        assertEquals("", remarks.get(1).getReason());
        assertTrue(ReviewAiPrompts.parseRemarks(new JsonObject(), new HashSet<>(Arrays.asList(1))).isEmpty());
    }

    @Test
    public void testRisks() {
        assertTrue(ReviewAiPrompts.risksRequest().startsWith("Rate the risk of each stop"));
        final List<StopRisk> risks = ReviewAiPrompts.parseRisks(Json.parse("{\"stops\":["
                + "{\"stop\":1,\"risk\":\"low\",\"reason\":\"comment\"},"
                + "{\"stop\":3,\"risk\":\"medium\",\"reason\":\"loop\"},"
                + "{\"stop\":2,\"risk\":\"HIGH\",\"reason\":\"locking\"},"
                + "{\"stop\":2,\"risk\":\"low\",\"reason\":\"second rating\"},"
                + "{\"stop\":4,\"risk\":\"high\",\"reason\":\"unknown stop\"},"
                + "{\"stop\":5,\"risk\":\"critical\",\"reason\":\"unknown risk\"}]}").asObject(),
                new HashSet<>(Arrays.asList(1, 2, 3, 5)));
        assertEquals(3, risks.size());
        assertEquals(2, risks.get(0).getStop());
        assertTrue(risks.get(0).isHigh());
        assertEquals("locking", risks.get(0).getReason());
        assertEquals(3, risks.get(1).getStop());
        assertEquals("medium", risks.get(1).getRisk());
        assertEquals(1, risks.get(2).getStop());
        assertEquals(Json.array("high", "medium", "low"), ReviewAiPrompts.risksSchema().get("properties").asObject()
                .get("stops").asObject().get("items").asObject().get("properties").asObject().get("risk").asObject()
                .get("enum"));
    }

    @Test
    public void testRemarkChecks() {
        final RemarkForAi remark = new RemarkForAi(1, 1, "must fix", "fixed", "Calculator.java:15",
                "times needs\na javadoc comment");
        assertEquals("Remark 1 (review 1, must fix, state: fixed, Calculator.java:15): times needs a javadoc comment",
                remark.describe());
        assertEquals("Remark 1, review 1, must fix, state: fixed", remark.summary());
        final String request = ReviewAiPrompts.remarkChecksRequest(Arrays.asList(remark));
        assertTrue(request, request.startsWith("Check the review remarks of the earlier review rounds"));
        assertTrue(request, request.endsWith("- " + remark.describe() + "\n"));
        assertEquals(Json.array("fixed", "not_fixed", "unclear"), ReviewAiPrompts.remarkChecksSchema()
                .get("properties").asObject().get("remarks").asObject().get("items").asObject()
                .get("properties").asObject().get("status").asObject().get("enum"));

        final List<RemarkCheck> checks = ReviewAiPrompts.parseRemarkChecks(Json.parse("{\"remarks\":["
                + "{\"remark\":2,\"status\":\"not fixed\",\"stop\":0,\"reason\":\"still a loop\"},"
                + "{\"remark\":1,\"status\":\"fixed\",\"stop\":3,\"reason\":\" javadoc added \"},"
                + "{\"remark\":1,\"status\":\"unclear\",\"stop\":3,\"reason\":\"second check\"},"
                + "{\"remark\":3,\"status\":\"fixed\",\"stop\":99,\"reason\":\"unknown stop\"},"
                + "{\"remark\":7,\"status\":\"fixed\",\"stop\":1,\"reason\":\"unknown remark\"},"
                + "{\"remark\":4,\"status\":\"done\",\"stop\":1,\"reason\":\"unknown status\"}]}").asObject(),
                new HashSet<>(Arrays.asList(1, 2, 3, 4)), new HashSet<>(Arrays.asList(1, 3)));
        assertEquals(3, checks.size());
        assertEquals(1, checks.get(0).getRemark());
        assertEquals("fixed", checks.get(0).getStatus());
        assertEquals(3, checks.get(0).getStop());
        assertEquals("javadoc added", checks.get(0).getReason());
        assertEquals("not_fixed", checks.get(1).getStatus());
        // an unknown stop is ignored, the check is kept
        assertEquals(3, checks.get(2).getRemark());
        assertEquals(0, checks.get(2).getStop());
    }

    @Test
    public void testRemarkTextsAreAbbreviated() {
        assertEquals("first line \u2026", RemarkLinePainter.abbreviate("  first line\nsecond line"));
        final String longText = new String(new char[200]).replace('\0', 'x');
        assertEquals(140, RemarkLinePainter.abbreviate(longText).length());
        assertTrue(RemarkLinePainter.abbreviate(longText).endsWith("\u2026"));
    }

}
