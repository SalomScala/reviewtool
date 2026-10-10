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
        } finally {
            markerFactory.clearReviewMarkers();
            markerFactory.clearStopMarkers();
        }
    }

    public void testToolWindowPanelCanBeCreated() throws IOException {
        final ReviewToolPanel panel = new ReviewToolPanel(this.getProject());
        Disposer.register(this.getTestRootDisposable(), panel);
        renderToPng(panel, "toolwindow", 1500, 500);
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
