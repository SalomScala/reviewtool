package de.setsoftware.reviewtool.intellij;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

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

import de.setsoftware.reviewtool.model.remarks.Position;
import de.setsoftware.reviewtool.model.remarks.ResolutionType;
import de.setsoftware.reviewtool.model.remarks.ReviewRemark;
import de.setsoftware.reviewtool.model.remarks.ReviewRound;

/**
 * Shows the review remarks of the current review as a tree (grouped by review round) and offers the
 * resolution actions of the Eclipse "fixing tasks" view via a context menu: jump to the remark's
 * code, mark it as fixed / won't fix / unclear, reopen it, add a comment or delete it. All
 * operations go through the shared {@link ReviewRemarksModel}.
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
    private final ReviewRemarksModel model;
    private final DefaultMutableTreeNode treeRoot = new DefaultMutableTreeNode("No remarks");
    private final DefaultTreeModel treeModel = new DefaultTreeModel(this.treeRoot);
    private final JTree tree = new JTree(this.treeModel);

    public ReviewRemarksPanel(Project project, ReviewRemarksModel model) {
        super(new BorderLayout());
        this.project = project;
        this.model = model;
        this.buildUi();
        this.model.addListener(() -> IntellijMarkerFactory.runOnEdt(this::rebuildTree));
    }

    private void buildUi() {
        final JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT));
        final JButton reloadButton = new JButton("Reload from Editor");
        reloadButton.addActionListener((e) -> this.model.reload());
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

    private void rebuildTree() {
        this.treeRoot.removeAllChildren();
        int remarkCount = 0;
        for (final ReviewRound round : this.model.getReviewData().getReviewRounds()) {
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
        menu.add(menuItem("Mark as fixed", () -> this.model.resolve(remark, ResolutionType.FIXED, null)));
        menu.add(menuItem("Mark as fixed (with comment)...",
                () -> this.resolveWithComment(remark, ResolutionType.FIXED)));
        menu.add(menuItem("Mark as won't fix...", () -> this.resolveWithComment(remark, ResolutionType.WONT_FIX)));
        menu.add(menuItem("Mark as unclear...", () -> this.resolveWithComment(remark, ResolutionType.QUESTION)));
        menu.add(menuItem("Reopen", () -> this.model.resolve(remark, ResolutionType.OPEN, null)));
        menu.addSeparator();
        menu.add(menuItem("Add comment...", () -> this.addComment(remark)));
        menu.add(menuItem("Delete remark", () -> this.model.delete(remark)));
        menu.show(this.tree, e.getX(), e.getY());
    }

    private static JMenuItem menuItem(String text, Runnable action) {
        final JMenuItem item = new JMenuItem(text);
        item.addActionListener((e) -> action.run());
        return item;
    }

    private void resolveWithComment(ReviewRemark remark, ResolutionType resolution) {
        final String comment = Messages.showInputDialog(this.project,
                "Comment (optional):", "Resolve Review Remark", null);
        if (comment == null) {
            return;
        }
        this.model.resolve(remark, resolution, comment);
    }

    private void addComment(ReviewRemark remark) {
        final String comment = Messages.showInputDialog(this.project, "Comment:", "Add Comment", null);
        if (comment == null || comment.trim().isEmpty()) {
            return;
        }
        this.model.addComment(remark, comment);
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

}
