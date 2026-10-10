package de.setsoftware.reviewtool.intellij;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

import javax.swing.Icon;
import javax.swing.JButton;
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
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.treeStructure.Tree;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import com.intellij.util.ui.tree.TreeUtil;

import de.setsoftware.reviewtool.intellij.ChangeSummaryGenerator.FileItem;
import de.setsoftware.reviewtool.intellij.ChangeSummaryGenerator.SummaryResult;
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

    /**
     * Kinds of nodes in the summary tree (determine the icon and the text style).
     */
    private enum Kind {
        OVERVIEW, GROUP, REFACTORING, FILE, PART
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
        final JBLabel hint = new JBLabel("Double click opens the file or declaration");
        hint.setComponentStyle(UIUtil.ComponentStyle.SMALL);
        hint.setFontColor(UIUtil.FontColor.BRIGHTER);
        hint.setBorder(JBUI.Borders.emptyLeft(8));
        toolbar.add(hint);
        this.add(toolbar, BorderLayout.NORTH);

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

    private void showResult(SummaryResult result) {
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
            case FILE:
                return FileTypeManager.getInstance().getFileTypeByFileName(node.label).getIcon();
            case PART:
            default:
                return AllIcons.Nodes.Class;
            }
        }
    }

}
