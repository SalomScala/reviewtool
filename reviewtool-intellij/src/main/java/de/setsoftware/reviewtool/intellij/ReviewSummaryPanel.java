package de.setsoftware.reviewtool.intellij;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Insets;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JEditorPane;
import javax.swing.JPanel;
import javax.swing.JTree;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.ColoredTreeCellRenderer;
import com.intellij.ui.JBColor;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.treeStructure.Tree;
import com.intellij.util.ui.HTMLEditorKitBuilder;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import com.intellij.util.ui.tree.TreeUtil;

import de.setsoftware.reviewtool.intellij.ChangeSummaryGenerator.FileItem;
import de.setsoftware.reviewtool.intellij.ChangeSummaryGenerator.SummaryResult;
import de.setsoftware.reviewtool.model.changestructure.Stop;
import de.setsoftware.reviewtool.model.changestructure.ToursInReview;

/**
 * Shows a structured summary of the changes under review (see {@link ChangeSummaryGenerator}) as a
 * collapsible tree: an overview node, the detected refactorings (renamed/moved classes and methods,
 * extracted methods, ...), then one node per changed file (with line counts, the path relative to the
 * project), and below each file the changed types/methods. Double click or Enter opens the file or
 * the declaration after the refactoring. The tree's expand/collapse provides the folding that the
 * Eclipse summary view offers via hyperlinks.
 */
public final class ReviewSummaryPanel extends JPanel {

    private static final long serialVersionUID = 1L;

    /** The maximal height of the rationale (unscaled), a longer description gets a scroll bar. */
    private static final int MAX_RATIONALE_HEIGHT = 180;

    /**
     * Kinds of nodes in the summary tree (determine the icon and the text style).
     */
    private enum Kind {
        OVERVIEW, GROUP, REFACTORING, FILE, PART, CHANGE_PART
    }

    /**
     * User object of the tree nodes: a label (with optional gray details) and an optional target to
     * navigate to.
     */
    static final class SummaryNode {
        private final Kind kind;
        private final String label;
        private final String details;
        private final String path;
        private final int line;

        SummaryNode(Kind kind, String label, String details, String path, int line) {
            this.kind = kind;
            this.label = label;
            this.details = details;
            this.path = path;
            this.line = line;
        }

        String getPath() {
            return this.path;
        }

        int getLine() {
            return this.line;
        }

        @Override
        public String toString() {
            return this.details == null ? this.label : this.label + "  " + this.details;
        }
    }

    private final transient Project project;
    private final DefaultMutableTreeNode treeRoot = new DefaultMutableTreeNode(
            new SummaryNode(Kind.OVERVIEW, "No summary", null, null, 0));
    private final DefaultTreeModel treeModel = new DefaultTreeModel(this.treeRoot);
    private final Tree tree = new Tree(this.treeModel);

    private transient ToursInReview tours;
    private transient SummaryResult lastResult;
    private transient StopContext stopContext;
    private transient Runnable aiSummaryListener;
    /** Shows why the changes have been made: the ticket and its description, and the commit messages. */
    private final JEditorPane rationalePane = new JEditorPane();
    private final JBScrollPane rationaleScroll = new JBScrollPane(this.rationalePane) {
        private static final long serialVersionUID = 1L;

        @Override
        public Dimension getPreferredSize() {
            // as high as the text (wrapped to the current width), but a long description gets a scroll bar
            final Insets insets = this.getInsets();
            final int width = Math.max(this.getWidth(), JBUI.scale(400)) - insets.left - insets.right;
            ReviewSummaryPanel.this.rationalePane.setSize(width, Short.MAX_VALUE);
            final int height = ReviewSummaryPanel.this.rationalePane.getPreferredSize().height
                    + insets.top + insets.bottom;
            return new Dimension(JBUI.scale(400), Math.min(height, JBUI.scale(MAX_RATIONALE_HEIGHT)));
        }
    };

    public ReviewSummaryPanel(Project project) {
        super(new BorderLayout());
        this.project = project;
        this.buildUi();
    }

    private void buildUi() {
        final JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT));
        final JButton refreshButton = new JButton("Regenerate Summary");
        refreshButton.addActionListener((e) -> this.regenerate());
        toolbar.add(refreshButton);
        final JButton aiButton = new JButton("Summarize with AI", AllIcons.Actions.Lightning);
        aiButton.setToolTipText("Asks the AI model configured in opencode for an overview of the changes (\"AI\" tab)");
        aiButton.addActionListener((e) -> {
            if (this.aiSummaryListener != null) {
                this.aiSummaryListener.run();
            }
        });
        toolbar.add(aiButton);
        final JBLabel hint = new JBLabel("Double click opens the file or declaration");
        hint.setComponentStyle(UIUtil.ComponentStyle.SMALL);
        hint.setFontColor(UIUtil.FontColor.BRIGHTER);
        hint.setBorder(JBUI.Borders.emptyLeft(8));
        toolbar.add(hint);
        this.rationalePane.setEditable(false);
        this.rationalePane.setEditorKit(new HTMLEditorKitBuilder().withWordWrapViewFactory().build());
        this.rationalePane.setBackground(UIUtil.getPanelBackground());
        this.rationalePane.setBorder(JBUI.Borders.empty(2, 8, 6, 8));
        this.rationaleScroll.setBorder(JBUI.Borders.customLineBottom(JBColor.border()));
        this.rationaleScroll.setVisible(false);
        final JPanel north = new JPanel(new BorderLayout());
        north.add(toolbar, BorderLayout.NORTH);
        north.add(this.rationaleScroll, BorderLayout.CENTER);
        this.add(north, BorderLayout.NORTH);

        this.tree.setRootVisible(true);
        this.tree.setCellRenderer(new SummaryRenderer());
        this.tree.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    ReviewSummaryPanel.this.openSelected();
                }
            }
        });
        this.tree.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER) {
                    ReviewSummaryPanel.this.openSelected();
                }
            }
        });
        this.add(new JBScrollPane(this.tree), BorderLayout.CENTER);
    }

    /**
     * Sets the action of the "Summarize with AI" button.
     */
    void setAiSummaryListener(Runnable listener) {
        this.aiSummaryListener = listener;
    }

    /**
     * Shows why the changes have been made (the rationale is what reviewers miss most), or hides it.
     *
     * @param ticket The ticket (e.g. "DEMO-2 Add multiply and sum"), or null to hide the rationale.
     * @param description The description of the ticket (Markdown), empty if it has none, or null if it
     *      is unknown (e.g. not loaded yet).
     * @param commitMessages The messages of the commits of the review.
     */
    void setRationale(String ticket, String description, List<String> commitMessages) {
        if (ticket == null) {
            this.rationaleScroll.setVisible(false);
        } else {
            this.rationalePane.setText(rationaleHtml(ticket, description, commitMessages));
            this.rationalePane.setCaretPosition(0);
            this.rationaleScroll.setVisible(true);
        }
        this.revalidate();
    }

    /**
     * The HTML text of the rationale (package-private for tests).
     */
    static String rationaleHtml(String ticket, String description, List<String> commitMessages) {
        final StringBuilder html = new StringBuilder("<html><body><b>Why: ")
                .append(SimpleMarkdown.escape(ticket)).append("</b>");
        if (description == null) {
            html.append("<p class='hint'>Loading the ticket description...</p>");
        } else if (description.trim().isEmpty()) {
            html.append("<p><i>The ticket has no description, the reason for the changes is not stated. If it is"
                    + " unclear, ask the author before judging the changes.</i></p>");
        } else {
            html.append(SimpleMarkdown.toHtml(description));
        }
        if (!commitMessages.isEmpty()) {
            html.append("<p><b>Commits:</b></p><ul>");
            for (final String message : commitMessages) {
                html.append("<li>").append(SimpleMarkdown.escape(message.trim())).append("</li>");
            }
            html.append("</ul>");
        }
        return html.append("</body></html>").toString();
    }

    /**
     * Returns the shown rationale text (for tests), or null if it is hidden.
     */
    String getShownRationale() {
        if (!this.rationaleScroll.isVisible()) {
            return null;
        }
        try {
            return this.rationalePane.getDocument().getText(0, this.rationalePane.getDocument().getLength());
        } catch (final javax.swing.text.BadLocationException e) {
            return "";
        }
    }

    /**
     * Sets the tours the summary is generated for and regenerates it.
     */
    public void setTours(ToursInReview tours) {
        this.tours = tours;
        this.regenerate();
    }

    private void regenerate() {
        final ToursInReview current = this.tours;
        new Task.Backgroundable(this.project, "Generating review summary", false) {
            @Override
            public void run(ProgressIndicator indicator) {
                final SummaryResult result = ChangeSummaryGenerator.analyze(current);
                ApplicationManager.getApplication().invokeLater(() -> ReviewSummaryPanel.this.showResult(result));
            }
        }.queue();
    }

    /**
     * Sets the context of the stops (computed in the background), which adds the "Tests" overview.
     */
    void setStopContext(StopContext context) {
        this.stopContext = context;
        if (this.lastResult != null) {
            this.showResult(this.lastResult);
        }
    }

    private void showResult(SummaryResult result) {
        this.lastResult = result;
        this.treeRoot.removeAllChildren();
        if (result == null) {
            this.treeRoot.setUserObject(new SummaryNode(Kind.OVERVIEW,
                    "No tours created yet - use \"Create Tours\" first.", null, null, 0));
        } else {
            this.treeRoot.setUserObject(new SummaryNode(Kind.OVERVIEW, String.format(
                    "Review summary: %d tours, %d stops (relevant %d, irrelevant %d), %d files (+%d / -%d)",
                    result.getTourCount(), result.getStopCount(), result.getRelevantCount(),
                    result.getIrrelevantCount(), result.getFiles().size(),
                    result.getTotalAdded(), result.getTotalRemoved()), null, null, 0));
            if (!result.getRefactorings().isEmpty()) {
                final DefaultMutableTreeNode refactorings = new DefaultMutableTreeNode(new SummaryNode(Kind.GROUP,
                        "Detected refactorings", "(" + result.getRefactorings().size() + ")", null, 0));
                for (final RefactoringDetector.Refactoring r : result.getRefactorings()) {
                    refactorings.add(new DefaultMutableTreeNode(new SummaryNode(Kind.REFACTORING,
                            r.getType() + ": " + r.getBefore() + " → " + r.getAfter(), null,
                            r.getAfterPath(), r.getAfterLine())));
                }
                this.treeRoot.add(refactorings);
            }
            this.addPartsOverview();
            this.addTestOverview();
            for (final FileItem file : result.getFiles()) {
                final String counts = file.isBinary()
                        ? "(binary)"
                        : "(+" + file.getAdded() + " / -" + file.getRemoved() + ")";
                final DefaultMutableTreeNode fileNode = new DefaultMutableTreeNode(new SummaryNode(
                        Kind.FILE, this.relativize(file.getPath()), counts, file.getPath(), 0));
                for (final String part : file.getParts()) {
                    fileNode.add(new DefaultMutableTreeNode(
                            new SummaryNode(Kind.PART, part, null, file.getPath(), 0)));
                }
                this.treeRoot.add(fileNode);
            }
        }
        this.treeModel.reload();
        TreeUtil.expandAll(this.tree);
    }

    /**
     * Adds the "Independent parts" overview if the changes consist of several parts that are not connected by
     * their files, calls, tests or refactorings (see {@link StopAnalysis.Info#getPart()}).
     */
    private void addPartsOverview() {
        final StopContext context = this.stopContext;
        if (context == null || context.getTours() != this.tours) {
            return;
        }
        final List<StopContext.Part> parts = context.parts();
        if (parts.size() < 2) {
            return;
        }
        int trivial = 0;
        final DefaultMutableTreeNode group = new DefaultMutableTreeNode();
        for (final StopContext.Part part : parts) {
            if (part.isTrivial()) {
                trivial++;
            }
            final Stop first = part.getStops().get(0);
            group.add(new DefaultMutableTreeNode(new SummaryNode(Kind.CHANGE_PART, part.describe(),
                    "(" + part.getStops().size() + (part.getStops().size() == 1 ? " stop" : " stops")
                    + (part.isTrivial() ? ", only refactorings/formatting/imports" : "") + ")",
                    first.getAbsoluteFile().getPath(),
                    first.isDetailedFragmentKnown() ? first.getMostRecentFragment().getFrom().getLine() : 0)));
        }
        group.setUserObject(new SummaryNode(Kind.GROUP, "Independent parts", "(" + parts.size() + " parts, "
                + trivial + " of them trivial; \"one tour per independent part\" when creating the tours)",
                null, 0));
        this.treeRoot.add(group);
    }

    /**
     * Adds the "Tests" overview: the changed tests, and the changed production code without a changed test
     * (with the tests outside of the review that use it, if there are any).
     */
    private void addTestOverview() {
        final StopContext context = this.stopContext;
        if (context == null || context.getTours() != this.tours) {
            return;
        }
        final List<StopContext.FileTests> files = context.testsPerFile();
        final DefaultMutableTreeNode group = new DefaultMutableTreeNode();
        int changedTests = 0;
        int untested = 0;
        for (final StopContext.FileTests file : files) {
            final String details;
            if (file.isTest()) {
                changedTests++;
                details = file.getTested().isEmpty() ? "(changed test)"
                        : "(changed test of " + String.join(", ", file.getTested()) + ")";
            } else if (file.getTestsInReview().isEmpty()) {
                untested++;
                if (!file.getTestsOutside().isEmpty()) {
                    final List<String> names = new ArrayList<>();
                    for (final String path : file.getTestsOutside()) {
                        names.add(new File(path).getName());
                    }
                    details = "(no changed test; unchanged tests: " + String.join(", ", names) + ")";
                } else {
                    details = file.isOutsideKnown() ? "(no test uses " + String.join(", ", file.getTypes()) + ")"
                            : "(no changed test)";
                }
            } else {
                details = "(tested by " + String.join(", ", file.getTestsInReview()) + ")";
            }
            group.add(new DefaultMutableTreeNode(new SummaryNode(
                    Kind.FILE, this.relativize(file.getPath()), details, file.getPath(), 0)));
        }
        if (group.getChildCount() == 0) {
            return;
        }
        group.setUserObject(new SummaryNode(Kind.GROUP, "Tests", "(" + changedTests
                + (changedTests == 1 ? " changed test, " : " changed tests, ") + untested
                + (untested == 1 ? " changed production file" : " changed production files")
                + " without a changed test)", null, 0));
        this.treeRoot.add(group);
    }

    /**
     * Returns the path relative to the project directory (or the path itself if it is outside).
     */
    private String relativize(String path) {
        final String base = this.project.getBasePath();
        final String normalized = path.replace('\\', '/');
        if (base != null && normalized.startsWith(base + "/")) {
            return normalized.substring(base.length() + 1);
        }
        return path;
    }

    private void openSelected() {
        final Object node = this.tree.getLastSelectedPathComponent();
        if (!(node instanceof DefaultMutableTreeNode)
                || !(((DefaultMutableTreeNode) node).getUserObject() instanceof SummaryNode)) {
            return;
        }
        final SummaryNode summaryNode = (SummaryNode) ((DefaultMutableTreeNode) node).getUserObject();
        if (summaryNode.getPath() == null) {
            return;
        }
        final VirtualFile file = LocalFileSystem.getInstance().findFileByPath(
                summaryNode.getPath().replace('\\', '/'));
        if (file == null) {
            IntellijNotifications.warn(this.project,
                    "The file " + summaryNode.getPath() + " does not exist (anymore).");
            return;
        }
        IntellijFileResolver.descriptorForLine(this.project, file, summaryNode.getLine() - 1).navigate(true);
    }

    /**
     * Shows icons for the kinds of nodes and the details (e.g. the line counts) in gray.
     */
    private static final class SummaryRenderer extends ColoredTreeCellRenderer {
        private static final long serialVersionUID = 1L;

        @Override
        public void customizeCellRenderer(JTree tree, Object value, boolean selected, boolean expanded,
                boolean leaf, int row, boolean hasFocus) {
            if (!(value instanceof DefaultMutableTreeNode)
                    || !(((DefaultMutableTreeNode) value).getUserObject() instanceof SummaryNode)) {
                this.append(String.valueOf(value));
                return;
            }
            final SummaryNode node = (SummaryNode) ((DefaultMutableTreeNode) value).getUserObject();
            this.setIcon(icon(node));
            this.append(node.label, node.kind == Kind.OVERVIEW || node.kind == Kind.GROUP
                    ? SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES : SimpleTextAttributes.REGULAR_ATTRIBUTES);
            if (node.details != null) {
                this.append("  " + node.details, SimpleTextAttributes.GRAYED_ATTRIBUTES);
            }
        }

        private static Icon icon(SummaryNode node) {
            switch (node.kind) {
            case OVERVIEW:
                return AllIcons.General.Information;
            case GROUP:
                return AllIcons.Nodes.Folder;
            case REFACTORING:
                return AllIcons.Actions.Edit;
            case CHANGE_PART:
                return AllIcons.Nodes.Module;
            case FILE:
                return FileTypeManager.getInstance().getFileTypeByFileName(node.label).getIcon();
            case PART:
            default:
                return AllIcons.Nodes.Class;
            }
        }
    }

}
