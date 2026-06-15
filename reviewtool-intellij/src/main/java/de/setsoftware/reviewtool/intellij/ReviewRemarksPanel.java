package de.setsoftware.reviewtool.intellij;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Collections;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javax.swing.JButton;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JTree;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;

import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.components.JBScrollPane;

import de.setsoftware.reviewtool.base.Logger;
import de.setsoftware.reviewtool.model.remarks.DummyMarker;
import de.setsoftware.reviewtool.model.remarks.Position;
import de.setsoftware.reviewtool.model.remarks.ResolutionType;
import de.setsoftware.reviewtool.model.remarks.ReviewData;
import de.setsoftware.reviewtool.model.remarks.ReviewRemark;
import de.setsoftware.reviewtool.model.remarks.ReviewRound;

/**
 * Shows the review remarks of the current review as a tree (grouped by review round) and offers the
 * resolution actions of the Eclipse "fixing tasks" view via a context menu: jump to the remark's
 * code, mark it as fixed / won't fix / unclear, reopen it, add a comment or delete it. Changes are
 * written back into the review remarks text.
 */
public final class ReviewRemarksPanel extends JPanel {

    private static final long serialVersionUID = 1L;

    /**
     * Tree node payload for a remark.
     */
    private static final class RemarkNode {
        private final ReviewRemark remark;

        RemarkNode(ReviewRemark remark) {
            this.remark = remark;
        }

        @Override
        public String toString() {
            try {
                final Position p = Position.parse(this.remark.getPositionString());
                final String location = p.getShortFileName() == null
                        ? "(global)"
                        : p.getShortFileName() + (p.getLine() > 0 ? ":" + p.getLine() : "");
                return "[" + this.remark.getRemarkType() + " / " + this.remark.getResolution() + "] "
                        + location + " — " + firstLine(this.remark.getText());
            } catch (final RuntimeException e) {
                return this.remark.getText();
            }
        }
    }

    private static String firstLine(String s) {
        if (s == null) {
            return "";
        }
        final int nl = s.indexOf('\n');
        return nl < 0 ? s : s.substring(0, nl);
    }

    private final Project project;
    private final Supplier<String> remarksGetter;
    private final Consumer<String> remarksSetter;
    private final DefaultMutableTreeNode treeRoot = new DefaultMutableTreeNode("No remarks");
    private final DefaultTreeModel treeModel = new DefaultTreeModel(this.treeRoot);
    private final JTree tree = new JTree(this.treeModel);

    private ReviewData reviewData;

    public ReviewRemarksPanel(
            Project project, Supplier<String> remarksGetter, Consumer<String> remarksSetter) {
        super(new BorderLayout());
        this.project = project;
        this.remarksGetter = remarksGetter;
        this.remarksSetter = remarksSetter;
        this.buildUi();
    }

    private void buildUi() {
        final JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT));
        final JButton reloadButton = new JButton("Reload from Editor");
        reloadButton.addActionListener((e) -> this.reload());
        toolbar.add(reloadButton);
        this.add(toolbar, BorderLayout.NORTH);

        this.tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        this.tree.setRootVisible(true);
        this.tree.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    final ReviewRemark remark = ReviewRemarksPanel.this.getSelectedRemark();
                    if (remark != null) {
                        ReviewRemarksPanel.this.jumpToRemark(remark);
                    }
                }
            }

            @Override
            public void mousePressed(MouseEvent e) {
                this.maybeShowPopup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                this.maybeShowPopup(e);
            }

            private void maybeShowPopup(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    ReviewRemarksPanel.this.showContextMenu(e);
                }
            }
        });
        this.add(new JBScrollPane(this.tree), BorderLayout.CENTER);
    }

    /**
     * Re-parses the review remarks text and rebuilds the tree.
     */
    public void reload() {
        try {
            this.reviewData = ReviewData.parse(
                    Collections.<Integer, String>emptyMap(),
                    DummyMarker.FACTORY,
                    this.remarksGetter.get());
        } catch (final RuntimeException e) {
            Logger.warn("could not parse review remarks", e);
            this.reviewData = new ReviewData();
        }
        this.rebuildTree();
    }

    private void rebuildTree() {
        this.treeRoot.removeAllChildren();
        int remarkCount = 0;
        for (final ReviewRound round : this.reviewData.getReviewRounds()) {
            if (round.getRemarks().isEmpty()) {
                continue;
            }
            final DefaultMutableTreeNode roundNode =
                    new DefaultMutableTreeNode("Review " + round.getNumber());
            for (final ReviewRemark remark : round.getRemarks()) {
                roundNode.add(new DefaultMutableTreeNode(new RemarkNode(remark)));
                remarkCount++;
            }
            this.treeRoot.add(roundNode);
        }
        this.treeRoot.setUserObject(remarkCount == 0 ? "No remarks" : remarkCount + " remarks");
        this.treeModel.reload();
        for (int i = 0; i < this.tree.getRowCount(); i++) {
            this.tree.expandRow(i);
        }
    }

    private ReviewRemark getSelectedRemark() {
        final Object node = this.tree.getLastSelectedPathComponent();
        if (node instanceof DefaultMutableTreeNode
                && ((DefaultMutableTreeNode) node).getUserObject() instanceof RemarkNode) {
            return ((RemarkNode) ((DefaultMutableTreeNode) node).getUserObject()).remark;
        }
        return null;
    }

    private void showContextMenu(MouseEvent e) {
        final TreePath path = this.tree.getPathForLocation(e.getX(), e.getY());
        if (path == null) {
            return;
        }
        this.tree.setSelectionPath(path);
        final ReviewRemark remark = this.getSelectedRemark();
        if (remark == null) {
            return;
        }
        final JPopupMenu menu = new JPopupMenu();
        menu.add(menuItem("Jump to code", () -> this.jumpToRemark(remark)));
        menu.addSeparator();
        menu.add(menuItem("Mark as fixed", () -> this.resolve(remark, ResolutionType.FIXED, false)));
        menu.add(menuItem("Mark as fixed (with comment)...",
                () -> this.resolve(remark, ResolutionType.FIXED, true)));
        menu.add(menuItem("Mark as won't fix...", () -> this.resolve(remark, ResolutionType.WONT_FIX, true)));
        menu.add(menuItem("Mark as unclear...", () -> this.resolve(remark, ResolutionType.QUESTION, true)));
        menu.add(menuItem("Reopen", () -> this.resolve(remark, ResolutionType.OPEN, false)));
        menu.addSeparator();
        menu.add(menuItem("Add comment...", () -> this.addComment(remark)));
        menu.add(menuItem("Delete remark", () -> this.deleteRemark(remark)));
        menu.show(this.tree, e.getX(), e.getY());
    }

    private static JMenuItem menuItem(String text, Runnable action) {
        final JMenuItem item = new JMenuItem(text);
        item.addActionListener((e) -> action.run());
        return item;
    }

    private void resolve(ReviewRemark remark, ResolutionType resolution, boolean withComment) {
        try {
            if (withComment) {
                final String comment = Messages.showInputDialog(this.project,
                        "Comment (optional):", "Resolve Review Remark", null);
                if (comment == null) {
                    return;
                }
                if (!comment.trim().isEmpty()) {
                    remark.addComment(currentUser(), comment.trim());
                }
            }
            remark.setResolution(resolution);
            this.persist();
        } catch (final RuntimeException e) {
            Logger.warn("could not resolve remark", e);
        }
    }

    private void addComment(ReviewRemark remark) {
        final String comment = Messages.showInputDialog(this.project,
                "Comment:", "Add Comment", null);
        if (comment == null || comment.trim().isEmpty()) {
            return;
        }
        try {
            remark.addComment(currentUser(), comment.trim());
            this.persist();
        } catch (final RuntimeException e) {
            Logger.warn("could not add comment", e);
        }
    }

    private void deleteRemark(ReviewRemark remark) {
        try {
            this.reviewData.deleteRemark(remark);
            this.persist();
        } catch (final RuntimeException e) {
            Logger.warn("could not delete remark", e);
        }
    }

    /**
     * Writes the (modified) review data back to the editor text and rebuilds the tree.
     */
    private void persist() {
        final String serialized = this.reviewData.serialize();
        this.remarksSetter.accept(serialized);
        this.rebuildTree();
    }

    private void jumpToRemark(ReviewRemark remark) {
        final Position position = Position.parse(remark.getPositionString());
        final String shortName = position.getShortFileName();
        if (shortName == null) {
            return;
        }
        final int line = position.getLine();
        IntellijMarkerFactory.runOnEdt(() -> {
            final VirtualFile vf = IntellijFileResolver.findByShortName(this.project, shortName);
            if (vf == null) {
                Messages.showInfoMessage(this.project,
                        "The file " + shortName + " could not be found in the project.",
                        "Code Review Tool");
                return;
            }
            new OpenFileDescriptor(this.project, vf, Math.max(0, line - 1), 0).navigate(true);
        });
    }

    private static String currentUser() {
        return System.getProperty("user.name", "reviewer");
    }

}
