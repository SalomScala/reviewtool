package de.setsoftware.reviewtool.intellij;

import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.imageio.ImageIO;
import javax.swing.JComponent;
import javax.swing.tree.TreeModel;

import org.eclipse.jgit.api.Git;

import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.ui.treeStructure.Tree;
import com.intellij.util.ui.UIUtil;

import de.setsoftware.reviewtool.base.Multiset;
import de.setsoftware.reviewtool.base.Pair;
import de.setsoftware.reviewtool.base.ReviewtoolException;
import de.setsoftware.reviewtool.changesources.git.GitCommitInfo;
import de.setsoftware.reviewtool.model.api.IChangeData;
import de.setsoftware.reviewtool.model.api.IClassification;
import de.setsoftware.reviewtool.model.api.ICommit;
import de.setsoftware.reviewtool.model.changestructure.Stop;
import de.setsoftware.reviewtool.model.changestructure.Tour;
import de.setsoftware.reviewtool.model.changestructure.ToursInReview;
import de.setsoftware.reviewtool.model.changestructure.ToursInReview.ICreateToursUi;
import de.setsoftware.reviewtool.model.changestructure.ToursInReview.ReviewRoundInfo;
import de.setsoftware.reviewtool.model.changestructure.ToursInReview.UserSelectedReductions;
import de.setsoftware.reviewtool.model.remarks.DummyMarker;
import de.setsoftware.reviewtool.model.remarks.FileLinePosition;
import de.setsoftware.reviewtool.model.remarks.RemarkType;
import de.setsoftware.reviewtool.model.remarks.ResolutionType;
import de.setsoftware.reviewtool.model.remarks.ReviewRemark;

/**
 * Smoke tests for the tool window: the remarks model, the creation of the panels and the tours
 * panel with tours created from a real Git repository. The panels are additionally rendered into
 * PNG files (see the "cort.snapshotDir" system property) for a visual check.
 */
public class ReviewToolUiSmokeTest extends BasePlatformTestCase {

    private static final String TWO_ROUNDS =
            "Review 2:\n"
            + "* sonstige Anmerkungen\n"
            + "*# a general note\n"
            + "* positiv\n"
            + "*# (Calculator.java, 5) nice and simple\n"
            + "* muss\n"
            + "*# (Calculator.java, 16) multiply is O(b), use a * b\n"
            + "*#* DEV: why not?\n"
            + "* direkt eingepflegt\n"
            + "*# (Main.java, 3) removed unused import (/)\n"
            + "\n"
            + "Review 1:\n"
            + "* muss\n"
            + "*# (Calculator.java, 9) missing javadoc (/)\n"
            + "* kann\n"
            + "*# (Main.java, 7) use a logger\n";

    private static ReviewRemarksModel model(StringBuilder text) {
        return new ReviewRemarksModel(text::toString, (t) -> {
            text.setLength(0);
            text.append(t);
        });
    }

    public void testNewRemarksGoToTheCurrentRound() {
        final StringBuilder text = new StringBuilder("Review 1:\n* muss\n*# (Foo.java, 3) old remark\n");
        final ReviewRemarksModel model = model(text);
        model.setRoundInfo(2, "ALICE");
        model.reload();
        assertEquals(1, model.countOpenRemarks());

        model.mergeNewRemark(ReviewRemark.create(new DummyMarker(), model.getReviewer(),
                new FileLinePosition("Bar.java", 7), "new remark", RemarkType.CAN_FIX));

        final String serialized = text.toString();
        final int round2 = serialized.indexOf("Review 2:");
        final int newRemark = serialized.indexOf("new remark");
        final int round1 = serialized.indexOf("Review 1:");
        assertTrue(serialized, round2 >= 0 && round2 < newRemark && newRemark < round1);
        assertEquals(2, model.countOpenRemarks());
    }

    public void testRoundAndReviewerDefaults() {
        final ReviewRemarksModel model = model(new StringBuilder());
        model.setRoundInfo(0, " ");
        assertEquals(1, model.getCurrentRound());
        assertEquals(ReviewRemarksModel.currentUser(), model.getReviewer());
    }

    public void testResolvingTheOpenRemarks() {
        final StringBuilder text = new StringBuilder(TWO_ROUNDS);
        final ReviewRemarksModel model = model(text);
        model.reload();
        assertEquals(2, model.countOpenRemarks());

        // like in Eclipse, the open remarks are processed starting with the oldest review round
        ReviewRemark open = model.findFirstOpenRemark();
        assertEquals("use a logger", open.getText());
        model.resolve(open, ResolutionType.WONT_FIX, null);
        open = model.findFirstOpenRemark();
        assertEquals("multiply is O(b), use a * b", open.getText());
        model.resolve(open, ResolutionType.FIXED, "done");

        assertNull(model.findFirstOpenRemark());
        assertEquals(0, model.countOpenRemarks());
        final String serialized = text.toString();
        assertTrue(serialized, serialized.contains("use a logger (x)"));
        assertTrue(serialized, serialized.contains("(/) " + ReviewRemarksModel.currentUser() + ": done"));
    }

    public void testRemarksWithSyntaxErrorsAreNotOverwritten() {
        final String invalid = "Review 1:\n* muss\n*# (Foo.java, 3) old remark\n*#* dangling\nfree text\n*#* x\n";
        final StringBuilder text = new StringBuilder(invalid);
        final ReviewRemarksModel model = model(text);
        model.reload();
        assertNotNull(model.getParseError());
        try {
            model.mergeNewRemark(ReviewRemark.create(new DummyMarker(), "ALICE",
                    new FileLinePosition("Bar.java", 7), "new remark", RemarkType.CAN_FIX));
            fail("a remark must not be added while the remarks have a syntax error");
        } catch (final ReviewtoolException e) {
            // expected
        }
        assertEquals(invalid, text.toString());

        text.setLength(0);
        text.append("Review 1:\n* muss\n*# (Foo.java, 3) old remark\n");
        model.reload();
        assertNull(model.getParseError());
    }

    public void testExampleTextIsValid() {
        final StringBuilder text = new StringBuilder(ReviewRemarksModel.createExampleText());
        final ReviewRemarksModel model = model(text);
        model.reload();
        assertNull(model.getParseError());
        assertEquals(6, model.getAllRemarks().size());
    }

    public void testTicketTableShowsHistoryWhenLoaded() {
        final TicketTableModel model = new TicketTableModel();
        final de.setsoftware.reviewtool.model.TicketInfo ticket = new de.setsoftware.reviewtool.model.TicketInfo(
                "TIC-1", "summary", "Ready for Review", "", "Core", null,
                java.util.Collections.<String>emptySet(), new java.util.Date());
        model.setTickets(java.util.Collections.singletonList(ticket));
        assertEquals("", model.getValueAt(0, TicketTableModel.COLUMN_PREVIOUS_REVIEWERS));

        model.updateTicket(ticket.withHistory("Reopened",
                new java.util.LinkedHashSet<>(java.util.Arrays.asList("ALICE", "BOB")),
                new java.util.Date(System.currentTimeMillis() - 3L * 24 * 60 * 60 * 1000)));
        assertEquals("Reopened", model.getValueAt(0, TicketTableModel.COLUMN_PREVIOUS_STATE));
        assertEquals("ALICE, BOB", model.getValueAt(0, TicketTableModel.COLUMN_PREVIOUS_REVIEWERS));
        assertEquals(3, model.getValueAt(0, TicketTableModel.COLUMN_OPEN_DAYS));
    }

    public void testStopOrderingSettings() {
        // empty = the Eclipse defaults: all relation types active
        assertEquals(StopOrderingSettings.RelationType.values().length,
                StopOrderingSettings.createMatchers("").size());
        assertEquals(StopOrderingSettings.normalize(""),
                StopOrderingSettings.serialize(StopOrderingSettings.defaults()));
        // order, explicitness (limited to the maximum of the type) and unknown entries
        assertEquals("METHOD_CALL:NONE;SAME_FILE:ALWAYS;SIMILARITY:NONE",
                StopOrderingSettings.normalize("METHOD_CALL:NONE;FOO:ALWAYS;SAME_FILE:ALWAYS;SIMILARITY:ALWAYS"));
        // all relation types can be deactivated
        assertTrue(StopOrderingSettings.createMatchers(StopOrderingSettings.serialize(
                java.util.Collections.<StopOrderingSettings.Entry>emptyList())).isEmpty());

        final StopOrderingTable table = new StopOrderingTable();
        table.setSettings("SOURCEFOLDER:ONLY_NONTRIVIAL;SAME_FILE:NONE");
        assertEquals("SOURCEFOLDER:ONLY_NONTRIVIAL;SAME_FILE:NONE", table.getSettings());
    }

    public void testRemarksPanelGroupsTheRemarks() throws IOException {
        final StringBuilder text = new StringBuilder(TWO_ROUNDS);
        final ReviewRemarksModel model = model(text);
        final ReviewRemarksPanel panel = new ReviewRemarksPanel(this.getProject(), model);
        model.setRoundInfo(2, "ALICE");
        model.reload();

        final TreeModel tree = UIUtil.findComponentOfType(panel, Tree.class).getModel();
        // "To fix", "Already fixed", "Positive", "Other remarks" (last round) and "Older remarks"
        assertEquals(5, tree.getChildCount(tree.getRoot()));
        renderToPng(panel, "remarks", 900, 400);
    }

    public void testMarkersHaveGutterIcons() {
        this.myFixture.configureByText("Foo.java", "class Foo {\n    int a;\n    int b;\n    int c;\n}\n");
        final com.intellij.openapi.vfs.VirtualFile file = this.myFixture.getFile().getVirtualFile();
        final IntellijMarkerFactory markerFactory = new IntellijMarkerFactory(this.getProject());
        try {
            markerFactory.addRemarkMarker(file, 2, true, "a remark",
                    new com.intellij.openapi.actionSystem.DefaultActionGroup());
            markerFactory.createStopMarker(file, 3, 4, true, "a stop");
            assertEquals(2, this.myFixture.findAllGutters().size());
            // the gutter component knows the icons of both markers
            final com.intellij.openapi.editor.ex.EditorGutterComponentEx gutter =
                    (com.intellij.openapi.editor.ex.EditorGutterComponentEx) this.myFixture.getEditor().getGutter();
            assertEquals(1, gutter.getGutterRenderers(1).size());
            assertEquals(1, gutter.getGutterRenderers(2).size());
            // a changed color scheme (e.g. light instead of dark) renders the stop markers anew, once
            com.intellij.openapi.application.ApplicationManager.getApplication().getMessageBus()
                    .syncPublisher(com.intellij.openapi.editor.colors.EditorColorsManager.TOPIC)
                    .globalSchemeChange(null);
            assertEquals(2, this.myFixture.findAllGutters().size());
        } finally {
            markerFactory.clearReviewMarkers();
            markerFactory.clearStopMarkers();
        }
    }

    public void testToolWindowPanelCanBeCreated() throws IOException {
        final ReviewToolPanel panel = new ReviewToolPanel(this.getProject());
        Disposer.register(this.getTestRootDisposable(), panel);
        renderToPng(panel, "toolwindow", 1500, 500);
        // the ticket list can be hidden (it is hidden automatically while working on a ticket)
        assertTrue(panel.isTicketListVisible());
        panel.setTicketListVisible(false);
        assertFalse(panel.isTicketListVisible());
        panel.setTicketListVisible(true);
        // without YouTrack settings, a settings change does not try to load tickets
        panel.settingsChanged();

        // the AI support is off by default; the "AI" tab explains it instead of sending anything
        assertFalse(ReviewToolSettings.getInstance(this.getProject()).getState().aiEnabled);
        panel.aiSummarize();
        assertTrue(panel.getAiPanel().getShownText(), panel.getAiPanel().getShownText().contains("AI support is off"));
        ReviewToolSettings.getInstance(this.getProject()).getState().aiEnabled = true;
        try {
            panel.settingsChanged();
            assertTrue(panel.getAiPanel().getShownText().contains("Summarize Changes"));
            // without tours, nothing is sent either
            panel.aiSummarize();
            assertTrue(panel.getAiPanel().getShownText(), panel.getAiPanel().getShownText().contains("no review tours"));
            panel.aiExplain(null);
            assertTrue(panel.getAiPanel().getShownText().contains("Select a stop"));
        } finally {
            ReviewToolSettings.getInstance(this.getProject()).getState().aiEnabled = false;
        }
    }

    public void testFilesAreResolvedInTheBackground() {
        this.myFixture.addFileToProject("pkg/Resolved.java", "package pkg;\nclass Resolved {}\n");
        final java.util.concurrent.atomic.AtomicReference<java.util.Map<String, com.intellij.openapi.vfs.VirtualFile>>
                result = new java.util.concurrent.atomic.AtomicReference<>();
        IntellijFileResolver.findByShortNamesAsync(this.getProject(),
                java.util.Arrays.asList("Resolved.java", "Missing.java"), result::set);
        com.intellij.testFramework.PlatformTestUtil.waitWithEventsDispatching(
                "the files were not resolved", () -> result.get() != null, 20);
        assertEquals("Resolved.java", result.get().get("Resolved.java").getName());
        assertFalse(result.get().containsKey("Missing.java"));
    }

    /**
     * Shows remark suggestions and risk ratings of the AI for the real stops (without opencode).
     */
    private void checkAiSuggestionsAndRisks(ReviewToursPanel panel, ToursInReview tours, File calculatorFile) {
        final java.util.Map<Stop, ReviewAiPrompts.StopForAi> stops = new java.util.LinkedHashMap<>();
        for (final de.setsoftware.reviewtool.model.changestructure.Tour tour : tours.getTopmostTours()) {
            for (final Stop stop : tour.getStops()) {
                stops.put(stop, new ReviewAiPrompts.StopForAi(stops.size() + 1, "tour",
                        stop.getAbsoluteFile().getName(), null, null, null));
            }
        }
        final Stop calculatorStop = tours.getStopsFor(calculatorFile.getAbsoluteFile()).get(0);
        final int number = stops.get(calculatorStop).getNumber();
        final ReviewAiPanel ai = new ReviewAiPanel(this.getProject(), () -> tours, () -> "DEMO-2", () -> null,
                (stop) -> { }, () -> { });
        final List<Object[]> accepted = new ArrayList<>();
        ai.setRemarkAcceptor((stop, line, type, text) -> accepted.add(new Object[] {stop, line, type, text}));
        ai.setRiskListener(panel::setAiRisks);
        ReviewToolSettings.getInstance(this.getProject()).getState().aiEnabled = true;
        try {
            ai.remarksSuggested(new OpencodeClient.Answer("", "acme/model", com.eclipsesource.json.Json.parse(
                    "{\"remarks\":[{\"stop\":" + number + ",\"line\":0,\"kind\":\"can\",\"text\":\"Use a constant\","
                    + "\"reason\":\"magic number\"},{\"stop\":999,\"line\":1,\"kind\":\"must\",\"text\":\"x\","
                    + "\"reason\":\"\"}]}").asObject()), stops, 0);
            String shown = ai.getShownText();
            assertTrue(shown, shown.contains("[can fix] Use a constant"));
            assertTrue(shown, shown.contains("magic number"));
            assertTrue(shown, shown.contains("1 suggestion was left out: 1 for an unknown stop"));
            ai.accept(0);
            assertEquals(1, accepted.size());
            assertSame(calculatorStop, accepted.get(0)[0]);
            assertEquals(0, accepted.get(0)[1]);
            assertEquals(de.setsoftware.reviewtool.model.remarks.RemarkType.CAN_FIX, accepted.get(0)[2]);
            assertEquals("Use a constant", accepted.get(0)[3]);
            assertTrue(ai.getShownText().contains("Added to the review remarks"));

            ai.risksRated(new OpencodeClient.Answer("", null, com.eclipsesource.json.Json.parse(
                    "{\"stops\":[{\"stop\":" + number + ",\"risk\":\"high\",\"reason\":\"overflow\"}]}").asObject()),
                    stops, tours, 0);
            shown = ai.getShownText();
            assertTrue(shown, shown.contains("high Stop " + number + ": Calculator.java - overflow")
                    || shown.contains("high Stop " + number + ": Calculator.java \u2013 overflow"));
            assertEquals("high", panel.getAiRisk(calculatorStop).getRisk());
            // the ratings belong to the tours
            panel.setAiRisks(null, java.util.Collections.emptyMap());
            assertNull(panel.getAiRisk(calculatorStop));

            this.checkAiAnswersAreSavedAndRestored(panel, tours, calculatorStop);
            this.checkAiSuggestionsAfterOwnReview(tours, calculatorStop);
        } finally {
            ReviewToolSettings.getInstance(this.getProject()).getState().aiEnabled = false;
        }
    }

    /**
     * Remark suggestions are a second opinion: they are offered after the reviewer's own review, minor
     * ones are collapsed, and the decisions are counted per model.
     */
    private void checkAiSuggestionsAfterOwnReview(ToursInReview tours, Stop calculatorStop) {
        final java.util.Set<Stop> lookedAt = new java.util.HashSet<>();
        final ReviewAiPanel ai = new ReviewAiPanel(this.getProject(), () -> tours, () -> "DEMO-2", () -> null,
                (stop) -> { }, () -> { });
        ai.setReviewerProgress(lookedAt::contains, () -> 3);
        ai.setRemarkAcceptor((stop, line, type, text) -> true);
        ai.suggestRemarks();
        String shown = ai.getShownText();
        assertTrue(shown, shown.contains("Suggested remarks: your own review first"));
        assertTrue(shown, shown.contains("3 relevant stops have not been looked at yet."));
        assertTrue(shown, shown.contains("Ask the AI anyway"));
        assertTrue(ai.hasDeferredRequest());
        ai.suggestRemarks(calculatorStop);
        shown = ai.getShownText();
        assertTrue(shown, shown.contains("You have not looked at the stop"));
        // only the latest offer is shown
        assertFalse(shown, shown.contains("3 relevant stops"));

        final java.util.Map<Stop, ReviewAiPrompts.StopForAi> stops = ai.describeStopsWithoutDiffs(tours);
        final int number = stops.get(calculatorStop).getNumber();
        final String model = "test/model-" + System.nanoTime();
        ai.remarksSuggested(new OpencodeClient.Answer("", model, com.eclipsesource.json.Json.parse(
                "{\"remarks\":[{\"stop\":" + number + ",\"line\":0,\"kind\":\"can\",\"importance\":\"low\","
                + "\"text\":\"Rename x\",\"reason\":\"\"},{\"stop\":" + number + ",\"line\":0,\"kind\":\"must\","
                + "\"importance\":\"high\",\"text\":\"Overflow\",\"reason\":\"\"}]}").asObject()), stops, 0, number);
        shown = ai.getShownText();
        assertTrue(shown, shown.contains("[must fix, important] Overflow"));
        assertTrue(shown, shown.contains("1 minor suggestion (e.g. naming or style)"));
        assertFalse(shown, shown.contains("Rename x"));
        ai.showMinorSuggestions(1);
        assertTrue(ai.getShownText(), ai.getShownText().contains("[can fix, minor] Rename x"));

        // the decisions are counted locally per model and kind
        ai.reject(1);
        ai.accept(0);
        ai.accept(1);
        assertEquals("2 of 2 suggestions accepted (100%; must 1/1, can 1/1)",
                AiSuggestionStats.getInstance().describe(model));
        assertNull(AiSuggestionStats.getInstance().describe(model + "-other"));

        // the settings show the statistics of the model (for the default model: of the last one used)
        final ReviewToolConfigurable configurable = new ReviewToolConfigurable(this.getProject());
        configurable.createComponent();
        configurable.reset();
        final String shownStats = configurable.getShownModelStats();
        assertNotNull(shownStats);
        assertTrue(shownStats, shownStats.contains(model + ":<br>2 of 2 suggestions accepted"));
        configurable.disposeUIResources();
    }

    /**
     * The answers of the AI are saved and shown again for the same tours (e.g. after a restart of the IDE),
     * with the state of the suggestions and the risk ratings, but not for other tours.
     */
    private void checkAiAnswersAreSavedAndRestored(ReviewToursPanel panel, ToursInReview tours, Stop calculatorStop) {
        final java.util.concurrent.atomic.AtomicInteger changes = new java.util.concurrent.atomic.AtomicInteger();
        final ReviewAiPanel first = new ReviewAiPanel(this.getProject(), () -> tours, () -> "DEMO-2", () -> null,
                (stop) -> { }, () -> { });
        first.setRiskListener(panel::setAiRisks);
        first.setHistoryListener(changes::incrementAndGet);
        assertNull(first.exportHistory());
        final java.util.Map<Stop, ReviewAiPrompts.StopForAi> stops = first.describeStopsWithoutDiffs(tours);
        final int number = stops.get(calculatorStop).getNumber();
        final int other = number == 1 ? 2 : 1;
        // suggestions for one stop: the ones for other stops are left out
        first.remarksSuggested(new OpencodeClient.Answer("", "acme/model", com.eclipsesource.json.Json.parse(
                "{\"remarks\":[{\"stop\":" + number + ",\"line\":3,\"kind\":\"must\",\"text\":\"Check overflow\","
                + "\"reason\":\"int\"},{\"stop\":" + other + ",\"line\":1,\"kind\":\"can\",\"text\":\"elsewhere\","
                + "\"reason\":\"\"}]}").asObject()), stops, 0, number);
        String shown = first.getShownText();
        assertTrue(shown, shown.contains("Suggested remarks for Stop " + number + ":"));
        assertTrue(shown, shown.contains("[must fix] Check overflow"));
        assertFalse(shown, shown.contains("elsewhere"));
        assertTrue(shown, shown.contains("1 suggestion was left out: 1 for another stop"));
        first.reject(0);
        first.risksRated(new OpencodeClient.Answer("", null, com.eclipsesource.json.Json.parse(
                "{\"stops\":[{\"stop\":" + number + ",\"risk\":\"medium\",\"reason\":\"overflow\"}]}").asObject()),
                stops, tours, 0);
        assertEquals(3, changes.get());
        final String history = first.exportHistory();
        assertNotNull(history);
        panel.setAiRisks(null, java.util.Collections.emptyMap());

        final ReviewAiPanel second = new ReviewAiPanel(this.getProject(), () -> tours, () -> "DEMO-2", () -> null,
                (stop) -> { }, () -> { });
        second.setRiskListener(panel::setAiRisks);
        final java.util.concurrent.atomic.AtomicInteger restoredChanges =
                new java.util.concurrent.atomic.AtomicInteger();
        second.setHistoryListener(restoredChanges::incrementAndGet);
        assertEquals(2, second.restoreHistory(history, tours));
        shown = second.getShownText();
        assertTrue(shown, shown.contains("Check overflow"));
        assertTrue(shown, shown.contains("Rejected."));
        assertTrue(shown, shown.contains("medium Stop " + number));
        assertTrue(shown, shown.contains("acme/model"));
        assertEquals("medium", panel.getAiRisk(calculatorStop).getRisk());
        assertEquals(0, restoredChanges.get());
        // saved again, the answers are the same
        assertEquals(history, second.exportHistory());
        panel.setAiRisks(null, java.util.Collections.emptyMap());

        // answers about other stops are not shown
        final com.eclipsesource.json.JsonObject changed = com.eclipsesource.json.Json.parse(history).asObject();
        changed.get("stops").asArray().get(0).asObject().set("path", "Other.java");
        final ReviewAiPanel third = new ReviewAiPanel(this.getProject(), () -> tours, () -> "DEMO-2", () -> null,
                (stop) -> { }, () -> { });
        assertEquals(0, third.restoreHistory(changed.toString(), tours));
        assertEquals(0, third.restoreHistory("not json", tours));
        assertNull(third.exportHistory());
    }

    public void testWorkingCopyWithCrlfOnlyShowsTheRealChanges() {
        // on Windows, Git often has LF and the working copy CRLF (core.autocrlf): only the really changed
        // line must count as changed, not every line
        final byte[] committed = "class A {\n    int a;\n    int b;\n}\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        final byte[] workingCopy = "class A {\r\n    int a;\r\n    int c;\r\n}\r\n"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        final java.nio.charset.Charset charset = FileTexts.charsetFor(committed, workingCopy);
        assertEquals("[[2,3) -> [2,3)]", String.valueOf(StopDiffViewer.compare(
                FileTexts.decode(committed, charset), FileTexts.decode(workingCopy, charset))));
    }

    public void testRemarkTextsAtTheEndOfTheLines() {
        final com.intellij.psi.PsiFile psiFile =
                this.myFixture.addFileToProject("Remarks.java", "class Remarks {\n    int a;\n    int b;\n}\n");
        final com.intellij.openapi.vfs.VirtualFile vf = psiFile.getVirtualFile();
        final com.intellij.openapi.editor.Document document =
                com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(vf);
        final IntellijMarkerFactory factory = new IntellijMarkerFactory(this.getProject());
        factory.addRemarkMarker(vf, 2, true, "tooltip", null,
                new RemarkLinePainter.RemarkText("must fix: a is never used", true));
        final RemarkLinePainter painter = new RemarkLinePainter();
        assertNull(painter.getLineExtensions(this.getProject(), vf, 0));
        java.util.Collection<com.intellij.openapi.editor.LineExtensionInfo> texts =
                painter.getLineExtensions(this.getProject(), vf, 1);
        assertEquals(1, texts.size());
        assertTrue(texts.iterator().next().getText(), texts.iterator().next().getText().contains("must fix: a is never used"));
        // the text moves with its line
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(this.getProject(),
                () -> document.insertString(0, "// new first line\n"));
        assertNull(painter.getLineExtensions(this.getProject(), vf, 1));
        assertEquals(1, painter.getLineExtensions(this.getProject(), vf, 2).size());
        // it can be switched off
        ReviewToolSettings.getInstance(this.getProject()).getState().showRemarksInEditor = false;
        try {
            assertNull(painter.getLineExtensions(this.getProject(), vf, 2));
        } finally {
            ReviewToolSettings.getInstance(this.getProject()).getState().showRemarksInEditor = true;
        }
        factory.clearReviewMarkers();
        assertNull(painter.getLineExtensions(this.getProject(), vf, 2));
    }

    public void testNavigationDescriptorUsesTheLineStart() throws Exception {
        final com.intellij.psi.PsiFile file =
                this.myFixture.addFileToProject("Lines.java", "class Lines {\n    int a;\n    int b;\n}\n");
        final com.intellij.openapi.vfs.VirtualFile vf = file.getVirtualFile();
        assertEquals("class Lines {\n".length(),
                IntellijFileResolver.descriptorForLine(this.getProject(), vf, 1).getOffset());
        // lines outside the file are clamped
        assertEquals(0, IntellijFileResolver.descriptorForLine(this.getProject(), vf, -5).getOffset());
        assertTrue(IntellijFileResolver.descriptorForLine(this.getProject(), vf, 100).getOffset() > 0);
        // the document is read in a read action: since IntelliJ 2026, UI event handlers have no implicit
        // read access (like a background thread here), and the double click on a stop logged an error
        final int offset = com.intellij.openapi.application.ApplicationManager.getApplication()
                .executeOnPooledThread(() -> IntellijFileResolver.descriptorForLine(this.getProject(), vf, 2).getOffset())
                .get(20, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals("class Lines {\n    int a;\n".length(), offset);
    }

    public void testToursFromGitChanges() throws Exception {
        final File repo = this.createDemoRepository();
        final ReviewToolService service = ReviewToolService.getInstance(this.getProject());
        service.getChangeSource().addProject(repo);
        final ChangeSourceUiAdapter ui = new ChangeSourceUiAdapter(this.getProject(), new EmptyProgressIndicator());

        final Set<String> ids = new LinkedHashSet<>();
        for (final GitCommitInfo commit : service.getRecentCommits(10, ui)) {
            if (commit.getSummary().startsWith("DEMO-2")) {
                ids.add(commit.getId());
            }
        }
        assertEquals(1, ids.size());
        final IChangeData changes = service.getChangesForCommits(ids, ui);
        final ToursInReview tours = service.createTours(changes, ui, new AcceptAllCreateToursUi());
        assertNotNull(tours);
        assertFalse(tours.getTopmostTours().isEmpty());

        final IntellijMarkerFactory markerFactory = new IntellijMarkerFactory(this.getProject());
        final ReviewToursPanel panel = new ReviewToursPanel(this.getProject(), markerFactory);
        try {
            panel.setTours(tours);
            assertTrue(panel.hasTours());
            final TreeModel tree = UIUtil.findComponentOfType(panel, Tree.class).getModel();
            assertEquals(tours.getTopmostTours().size(), tree.getChildCount(tree.getRoot()));

            // graphical stop icons like in Eclipse: unvisited stops in the text color, checked ones with a check mark
            final StopIcons icons = panel.icons();
            for (final Tour tour : tours.getTopmostTours()) {
                assertPaintsSomething(icons.forTour(tour, false));
                assertSame(StopIcons.activeTourDot(), icons.forTour(tour, true));
                for (final Stop stop : tour.getStops()) {
                    final javax.swing.Icon icon = icons.forStop(stop);
                    assertEquals(com.intellij.util.ui.JBUI.scale(16), icon.getIconWidth());
                    assertPaintsSomething(icon);
                    assertEquals(StopIcons.shape(stop), StopIcons.shape(stop));
                }
            }
            final Stop firstStop = tours.getTopmostTours().get(0).getStops().get(0);
            final int notVisited = panel.countRelevantStopsNotFullyVisited();
            assertTrue(notVisited > 0);
            panel.toggleChecked(firstStop);
            assertSame(StopIcons.checkMark(), panel.icons().forStop(firstStop));
            // checked stops do not count as "not visited" when the review is ended
            assertEquals(notVisited - 1, panel.countRelevantStopsNotFullyVisited());
            panel.toggleChecked(firstStop);

            // when the last relevant stop has been checked, the listener (offering to end the review) is called
            final java.util.concurrent.atomic.AtomicInteger allVisited = new java.util.concurrent.atomic.AtomicInteger();
            panel.setAllStopsVisitedListener(allVisited::incrementAndGet);
            final List<Stop> checked = new ArrayList<>();
            for (final Tour tour : tours.getTopmostTours()) {
                for (final Stop stop : tour.getStops()) {
                    if (!stop.isIrrelevantForReview(tours.getIrrelevantCategories())) {
                        panel.toggleChecked(stop);
                        checked.add(stop);
                    }
                }
            }
            assertEquals(1, allVisited.get());
            for (final Stop stop : checked) {
                panel.toggleChecked(stop);
            }
            panel.setAllStopsVisitedListener(null);

            // the progress (viewed lines, checked stops, tour choices) survives a restart
            final Stop checkedStop = tours.getTopmostTours().get(0).getStops().get(0);
            panel.toggleChecked(checkedStop);
            panel.getStatistics().mark(checkedStop.getAbsoluteFile(), 1, 3);
            final java.nio.file.Path progressFile = Files.createTempDirectory("cort-progress").resolve("DEMO-1.properties");
            ReviewProgress.capture(2, "one tour per commit", Collections.singleton("whitespace"), null,
                    panel.getStatistics(), tours).save(progressFile);
            final ReviewProgress loaded = ReviewProgress.load(progressFile);
            assertNotNull(loaded);
            assertEquals(2, loaded.getRound());
            assertEquals("one tour per commit", loaded.getTourStructure());
            assertEquals(Collections.singleton("whitespace"), loaded.getIrrelevantClassifications());
            assertEquals(1, loaded.getCheckedStopCount());
            final de.setsoftware.reviewtool.model.viewtracking.ViewStatistics fresh =
                    new de.setsoftware.reviewtool.model.viewtracking.ViewStatistics();
            assertEquals(1, loaded.applyTo(fresh, tours));
            assertTrue(fresh.isMarkedAsChecked(checkedStop));
            assertEquals(panel.getStatistics().getStatisticsPerFile().get(checkedStop.getAbsoluteFile()).getCountsPerLine(),
                    fresh.getStatisticsPerFile().get(checkedStop.getAbsoluteFile()).getCountsPerLine());
            assertNull("a missing file means no saved progress",
                    ReviewProgress.load(progressFile.resolveSibling("missing.properties")));
            panel.toggleChecked(checkedStop);

            // a new file is compared with empty content (and not with an empty line)
            final Stop mainStop = tours.getStopsFor(new File(repo, "src/main/java/demo/Main.java").getAbsoluteFile())
                    .get(0);
            final com.intellij.diff.requests.SimpleDiffRequest request = StopDiffViewer.createRequest(
                    this.getProject(), mainStop, StopDiffViewer.load(mainStop), false);
            assertTrue(request.getContents().get(0) instanceof com.intellij.diff.contents.EmptyContent);
            // the titles show the abbreviated commit hash and a readable time instead of "hash (seconds)"
            final String afterTitle = request.getContentTitles().get(1);
            assertTrue(afterTitle, afterTitle.matches("After \\([0-9a-f]{7}, \\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}\\)"));
            assertEquals("4711", StopDiffViewer.describeRevisionText("4711"));

            panel.navigate(1);
            assertNotNull("navigating to the first stop opens its file",
                    FileEditorManager.getInstance(this.getProject()).getSelectedTextEditor());
            final StopDetailsPanel details = panel.getDetailsPanel();
            final Stop shown = details.getShownStop();
            assertNotNull("the stop details show the current stop", shown);
            // repeated updates of the same stop (e.g. by the view tracking) must not discard the loading diff
            details.showStop(shown, "updated description");
            details.showStop(shown, "updated description again");
            com.intellij.testFramework.PlatformTestUtil.waitWithEventsDispatching(
                    "the diff of the stop was not loaded", () -> details.getLoadedStop() == shown, 20);
            // by default, the diff only shows the stop's changes, not the whole file
            assertTrue(details.isShowingOnlyStopChanges());
            details.setOnlyStopChanges(false);
            assertFalse(details.isShowingOnlyStopChanges());
            details.setOnlyStopChanges(true);
            final File calculatorFile = new File(repo, "src/main/java/demo/Calculator.java");
            for (final Stop stop : tours.getStopsFor(calculatorFile.getAbsoluteFile())) {
                // the AI gets the stop's changes as a unified diff
                final String unified = StopDiffViewer.load(stop).unifiedDiff();
                assertTrue(unified, unified.startsWith("@@ -") && unified.contains("\n+"));
                final StopDiffExcerpt excerpt = StopDiffViewer.load(stop).getExcerpt();
                assertNotNull(excerpt);
                final boolean importStop = stop.getMostRecentFragment().getFrom().getLine() < 5;
                assertEquals(excerpt.getNewText(), importStop, excerpt.getNewText().contains("import java.util.List"));
                assertEquals(excerpt.getNewText(), !importStop, excerpt.getNewText().contains("multiply"));

                // remarks can be added in the diff: the lines of both sides are mapped to the current file
                final com.intellij.diff.requests.SimpleDiffRequest stopRequest = StopDiffViewer.createRequest(
                        this.getProject(), stop, StopDiffViewer.load(stop), true);
                final StopDiffViewer.StopDiffTarget target = stopRequest.getUserData(StopDiffViewer.STOP_DIFF_TARGET);
                assertNotNull(target);
                assertSame(stop, target.getStop());
                final int stopLine = stop.getMostRecentFragment().getFrom().getLine();
                assertEquals(stopLine, target.toFileLine(true, excerpt.toNewExcerptLine(stopLine - 1)));
                final int oldSideLine = target.toFileLine(false, 0);
                assertTrue("line " + oldSideLine, oldSideLine >= 1 && oldSideLine <= stopLine);
                final List<com.intellij.openapi.actionSystem.AnAction> diffActions =
                        stopRequest.getUserData(com.intellij.diff.util.DiffUserDataKeys.CONTEXT_ACTIONS);
                assertTrue("the diff toolbar offers \"Add Review Remark\"",
                        diffActions != null && diffActions.get(0) instanceof CortActions.AddRemark);
            }
            this.checkAiSuggestionsAndRisks(panel, tours, calculatorFile);

            // marking the selected stop as checked keeps it selected, navigating selects the new stop
            assertSame(shown, panel.getSelectedStop());
            panel.toggleChecked(shown);
            assertSame(shown, panel.getSelectedStop());
            panel.toggleChecked(shown);
            panel.navigate(1);
            assertNotSame(shown, panel.getSelectedStop());
            assertSame(details.getShownStop(), panel.getSelectedStop());
            panel.jumpToNextUnvisitedStop();
            panel.showNearestStop(new File(repo, "src/main/java/demo/Calculator.java"), 18);

            renderToPng(panel, "tours", 1000, 400);

            // the stops follow local (not yet committed) edits
            final File calculator = new File(repo, "src/main/java/demo/Calculator.java");
            final List<Integer> linesBefore = stopStartLines(tours, calculator);
            write(calculator, "// added line 1\n// added line 2\n// added line 3\n"
                    + new String(Files.readAllBytes(calculator.toPath()), StandardCharsets.UTF_8));
            assertTrue(panel.getLocalChangeTracker().updateNow());
            final List<Integer> linesAfter = stopStartLines(tours, calculator);
            assertEquals(linesBefore.size(), linesAfter.size());
            for (int i = 0; i < linesBefore.size(); i++) {
                assertEquals(linesBefore.get(i) + 3, (int) linesAfter.get(i));
            }
        } finally {
            panel.dispose();
            markerFactory.clearStopMarkers();
            final FileEditorManager editorManager = FileEditorManager.getInstance(this.getProject());
            for (final com.intellij.openapi.vfs.VirtualFile file : editorManager.getOpenFiles()) {
                editorManager.closeFile(file);
            }
        }
    }

    public void testIconGrammarCreatesDifferentShapes() {
        final Set<String> shapes = new LinkedHashSet<>();
        for (int i = 0; i < 12; i++) {
            shapes.add(StopIconGrammar.create(i, i, i + 1).toString());
        }
        assertEquals(12, shapes.size());
        assertEquals(StopIconGrammar.create(3, 5, 7), StopIconGrammar.create(15, 17, 19));
        assertEquals(StopIconGrammar.create(1, 1, 1), StopIconGrammar.create(-1, -1, -1));
    }

    private static void assertPaintsSomething(javax.swing.Icon icon) {
        final BufferedImage image = new BufferedImage(icon.getIconWidth(), icon.getIconHeight(),
                BufferedImage.TYPE_INT_ARGB);
        final Graphics2D g = image.createGraphics();
        try {
            icon.paintIcon(null, g, 0, 0);
        } finally {
            g.dispose();
        }
        for (int x = 0; x < image.getWidth(); x++) {
            for (int y = 0; y < image.getHeight(); y++) {
                if ((image.getRGB(x, y) >>> 24) != 0) {
                    return;
                }
            }
        }
        fail("the icon is empty: " + icon);
    }

    public void testSummaryDetectsRefactorings() throws Exception {
        final File repo = this.createDemoRepository();
        try (Git git = Git.open(repo)) {
            final File calculator = new File(repo, "src/main/java/demo/Calculator.java");
            final String content = new String(Files.readAllBytes(calculator.toPath()), StandardCharsets.UTF_8);
            Files.delete(calculator.toPath());
            write(new File(repo, "src/main/java/demo/Computer.java"), content
                    .replace("class Calculator", "class Computer")
                    .replace("multiply(", "times(")
                    .replace("        int total = 0;\n"
                            + "        for (final int v : values) {\n            total = this.add(total, v);\n"
                            + "        }\n        return total;\n",
                            "        return this.addAll(values);\n    }\n\n"
                            + "    private int addAll(List<Integer> values) {\n        int total = 0;\n"
                            + "        for (final int v : values) {\n            total = this.add(total, v);\n"
                            + "        }\n        return total;\n"));
            write(new File(repo, "src/main/java/demo/Main.java"), "package demo;\n\npublic class Main {\n"
                    + "    public static void main(String[] args) {\n"
                    + "        System.out.println(new Computer().times(3, 4));\n    }\n}\n");
            git.add().addFilepattern(".").call();
            git.rm().addFilepattern("src/main/java/demo/Calculator.java").call();
            git.commit().setMessage("DEMO-3 Refactoring").setSign(false)
                    .setAuthor("Demo", "demo@example.com").setCommitter("Demo", "demo@example.com").call();
        }
        final ReviewToolService service = ReviewToolService.getInstance(this.getProject());
        service.getChangeSource().addProject(repo);
        final ChangeSourceUiAdapter ui = new ChangeSourceUiAdapter(this.getProject(), new EmptyProgressIndicator());
        final Set<String> ids = new LinkedHashSet<>();
        for (final GitCommitInfo commit : service.getRecentCommits(10, ui)) {
            if (commit.getSummary().startsWith("DEMO-3")) {
                ids.add(commit.getId());
            }
        }
        final ToursInReview tours = service.createTours(
                service.getChangesForCommits(ids, ui), ui, new AcceptAllCreateToursUi());
        final ChangeSummaryGenerator.SummaryResult summary = ChangeSummaryGenerator.analyze(tours);
        final List<String> refactorings = new ArrayList<>();
        for (final RefactoringDetector.Refactoring r : summary.getRefactorings()) {
            refactorings.add(r.toString());
        }
        assertTrue(refactorings.toString(), refactorings.contains("Rename class: demo.Calculator -> demo.Computer"));
        // the refactorings know where the new declaration is (for the navigation from the summary)
        for (final RefactoringDetector.Refactoring r : summary.getRefactorings()) {
            assertTrue(r.getAfterPath(), r.getAfterPath().endsWith("Computer.java"));
            assertTrue(r.toString(), r.getAfterLine() > 0);
        }
        // a change within a line (Calculator -> Computer, multiply -> times) counts as one changed line
        for (final ChangeSummaryGenerator.FileItem file : summary.getFiles()) {
            if (file.getPath().endsWith("Main.java")) {
                assertEquals(1, file.getAdded());
                assertEquals(1, file.getRemoved());
            }
        }
        assertTrue(refactorings.toString(),
                refactorings.contains("Rename method: Calculator.multiply(int, int) -> Computer.times(int, int)"));
        assertTrue(refactorings.toString(),
                refactorings.contains("Extract method: Calculator.sum(List<Integer>) -> Computer.addAll(List<Integer>)"));

        final ReviewSummaryPanel panel = new ReviewSummaryPanel(this.getProject());
        panel.setTours(tours);
        com.intellij.testFramework.PlatformTestUtil.waitWithEventsDispatching("the summary was not shown",
                () -> String.valueOf(UIUtil.findComponentOfType(panel, javax.swing.JTree.class).getModel().getRoot())
                        .startsWith("Review summary"), 20);
        renderToPng(panel, "summary", 900, 400);
    }

    private static List<Integer> stopStartLines(ToursInReview tours, File file) {
        final List<Integer> ret = new ArrayList<>();
        for (final Stop stop : tours.getStopsFor(file.getAbsoluteFile())) {
            if (stop.isDetailedFragmentKnown()) {
                ret.add(stop.getMostRecentFragment().getFrom().getLine());
            }
        }
        assertFalse("no stops for " + file, ret.isEmpty());
        return ret;
    }

    private File createDemoRepository() throws Exception {
        final File repo = FileUtil.createTempDirectory("cort-demo", null, true);
        final File source = new File(repo, "src/main/java/demo/Calculator.java");
        source.getParentFile().mkdirs();
        try (Git git = Git.init().setDirectory(repo).setInitialBranch("main").call()) {
            write(source, "package demo;\n\npublic class Calculator {\n\n"
                    + "    public int add(int a, int b) {\n        return a + b;\n    }\n}\n");
            git.add().addFilepattern(".").call();
            git.commit().setMessage("DEMO-1 Initial calculator").setSign(false)
                    .setAuthor("Demo", "demo@example.com").setCommitter("Demo", "demo@example.com").call();

            write(source, "package demo;\n\nimport java.util.List;\n\npublic class Calculator {\n\n"
                    + "    public int add(int a, int b) {\n        return a + b;\n    }\n\n"
                    + "    public int multiply(int a, int b) {\n        int result = 0;\n"
                    + "        for (int i = 0; i < b; i++) {\n            result = this.add(result, a);\n"
                    + "        }\n        return result;\n    }\n\n"
                    + "    public int sum(List<Integer> values) {\n        int total = 0;\n"
                    + "        for (final int v : values) {\n            total = this.add(total, v);\n"
                    + "        }\n        return total;\n    }\n}\n");
            write(new File(repo, "src/main/java/demo/Main.java"), "package demo;\n\npublic class Main {\n"
                    + "    public static void main(String[] args) {\n"
                    + "        System.out.println(new Calculator().multiply(3, 4));\n    }\n}\n");
            git.add().addFilepattern(".").call();
            git.commit().setMessage("DEMO-2 Add multiply and sum").setSign(false)
                    .setAuthor("Demo", "demo@example.com").setCommitter("Demo", "demo@example.com").call();
        }
        return repo;
    }

    private static void write(File file, String content) throws IOException {
        Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Renders the component offscreen into a PNG file (for a visual check of the layout).
     */
    private static void renderToPng(JComponent component, String name, int width, int height) throws IOException {
        final String dir = System.getProperty("cort.snapshotDir");
        component.setSize(width, height);
        layoutRecursively(component);
        if (dir == null) {
            return;
        }
        final BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        final Graphics2D g = image.createGraphics();
        try {
            component.paint(g);
        } finally {
            g.dispose();
        }
        new File(dir).mkdirs();
        ImageIO.write(image, "png", new File(dir, name + ".png"));
    }

    private static void layoutRecursively(Component component) {
        if (component instanceof Container) {
            ((Container) component).doLayout();
            for (final Component child : ((Container) component).getComponents()) {
                layoutRecursively(child);
            }
        }
    }

    /**
     * Tour creation UI that takes the first tour structure and does not mark anything as irrelevant.
     */
    private static final class AcceptAllCreateToursUi implements ICreateToursUi {
        @Override
        public List<? extends Tour> selectInitialTours(List<? extends Pair<String, List<? extends Tour>>> choices) {
            return choices.get(0).getSecond();
        }

        @Override
        public UserSelectedReductions selectIrrelevant(List<? extends ICommit> changes,
                Multiset<IClassification> strategyResults, List<ReviewRoundInfo> reviewRounds) {
            return new UserSelectedReductions(new ArrayList<>(changes), Collections.<IClassification>emptySet());
        }
    }

}
