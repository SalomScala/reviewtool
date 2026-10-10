package de.setsoftware.reviewtool.intellij;

import java.awt.BorderLayout;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import javax.swing.Icon;
import javax.swing.JPanel;
import javax.swing.JTree;
import javax.swing.ToolTipManager;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogBuilder;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.ColoredTreeCellRenderer;
import com.intellij.ui.EditorNotificationPanel;
import com.intellij.ui.JBColor;
import com.intellij.ui.PopupHandler;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.treeStructure.Tree;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.tree.TreeUtil;

import de.setsoftware.reviewtool.model.remarks.Position;
import de.setsoftware.reviewtool.model.remarks.RemarkType;
import de.setsoftware.reviewtool.model.remarks.ResolutionType;
import de.setsoftware.reviewtool.model.remarks.ReviewRemark;
import de.setsoftware.reviewtool.model.remarks.ReviewRemarkComment;
import de.setsoftware.reviewtool.model.remarks.ReviewRound;

/**
 * Shows the review remarks of the current review as a tree, the IntelliJ counterpart of the Eclipse
 * "fixing tasks" view: the remarks are grouped into "To fix", "Already fixed", "Positive", "Other
 * remarks" and "Older remarks", the follow-up comments are shown below each remark and the icon
 * shows the kind and resolution of a remark. The context menu (and the gutter popup of the remark
 * markers) offers the resolution actions: jump to the remark's code, mark it as fixed / won't fix /
 * unclear, reopen it, add a comment or delete it. All operations go through the shared
 * {@link ReviewRemarksModel}.
 */
public final class ReviewRemarksPanel extends JPanel {

    private static final long serialVersionUID = 1L;

    private static final SimpleTextAttributes FIXED_ATTRIBUTES =
            new SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, new JBColor(0x3D8F58, 0x5FB878));
    private static final SimpleTextAttributes WONT_FIX_ATTRIBUTES =
            new SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, new JBColor(0xC7222D, 0xE55B5B));
    private static final SimpleTextAttributes QUESTION_ATTRIBUTES =
            new SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, new JBColor(0x2B5FB8, 0x6E9DE0));

    /**
     * The categories of the top level of the tree (in display order).
     */
    private enum Category {
        WORK("To fix"),
        RECENT_DONE("Already fixed"),
        RECENT_POSITIVE("Positive"),
        RECENT_OTHER("Other remarks"),
        OLDER("Older remarks");

        private final String text;

        Category(String text) {
            this.text = text;
        }

        /**
         * Same classification as in the Eclipse fixing tasks view: remarks of the last round are
         * grouped by their kind, remarks of older rounds only by whether they still need fixing.
         */
        static Category classify(ReviewRemark remark, boolean lastRound) {
            if (lastRound) {
                switch (remark.getRemarkType()) {
                case OTHER:
                    return RECENT_OTHER;
                case POSITIVE:
                    return RECENT_POSITIVE;
                case ALREADY_FIXED:
                    return RECENT_DONE;
                case CAN_FIX:
                case MUST_FIX:
                case TEMPORARY:
                default:
                    return WORK;
                }
            } else {
                return remark.needsFixing() ? WORK : OLDER;
            }
        }
    }

    /**
     * Tree node payload for a category.
     */
    private static final class CategoryNode {
        private final Category category;
        private final int count;
        private final int open;

        CategoryNode(Category category, int count, int open) {
            this.category = category;
            this.count = count;
            this.open = open;
        }
    }

    /**
     * Tree node payload for a remark.
     */
    private static final class RemarkNode {
        private final ReviewRemark remark;
        private final int round;

        RemarkNode(ReviewRemark remark, int round) {
            this.remark = remark;
            this.round = round;
        }
    }

    /**
     * Tree node payload for a follow-up comment of a remark.
     */
    private static final class CommentNode {
        private final ReviewRemark remark;
        private final ReviewRemarkComment comment;

        CommentNode(ReviewRemark remark, ReviewRemarkComment comment) {
            this.remark = remark;
            this.comment = comment;
        }
    }

    private final Project project;
    private final ReviewRemarksModel model;
    private final DefaultMutableTreeNode treeRoot = new DefaultMutableTreeNode("No remarks");
    private final DefaultTreeModel treeModel = new DefaultTreeModel(this.treeRoot);
    private final Tree tree = new Tree(this.treeModel);
    private final JBLabel statusLabel = new JBLabel();
    private final EditorNotificationPanel syntaxErrorBanner =
            new EditorNotificationPanel(EditorNotificationPanel.Status.Error);

    public ReviewRemarksPanel(Project project, ReviewRemarksModel model) {
        super(new BorderLayout());
        this.project = project;
        this.model = model;
        this.buildUi();
        this.model.addListener(() -> IntellijMarkerFactory.runOnEdt(this::rebuildTree));
    }

    private void buildUi() {
        final DefaultActionGroup toolbarGroup = new DefaultActionGroup();
        toolbarGroup.add(DumbAwareAction.create("Jump to Next Open Remark", AllIcons.Actions.NextOccurence,
                (e) -> this.jumpToNextOpenRemark()));
        toolbarGroup.add(DumbAwareAction.create("Reload Remarks from Text", AllIcons.Actions.Refresh,
                (e) -> this.model.reload()));
        toolbarGroup.addSeparator();
        toolbarGroup.add(DumbAwareAction.create("Expand All", AllIcons.Actions.Expandall,
                (e) -> TreeUtil.expandAll(this.tree)));
        toolbarGroup.add(DumbAwareAction.create("Collapse All", AllIcons.Actions.Collapseall,
                (e) -> TreeUtil.collapseAll(this.tree, 1)));
        final ActionToolbar toolbar = ActionManager.getInstance().createActionToolbar(
                "CoRT.Remarks", toolbarGroup, true);
        toolbar.setTargetComponent(this);
        final JPanel north = new JPanel(new BorderLayout());
        north.add(toolbar.getComponent(), BorderLayout.NORTH);
        this.syntaxErrorBanner.createActionLabel("Show example of the syntax", this::showSyntaxExample);
        this.syntaxErrorBanner.setVisible(false);
        north.add(this.syntaxErrorBanner, BorderLayout.SOUTH);
        this.add(north, BorderLayout.NORTH);

        this.tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        this.tree.setRootVisible(false);
        this.tree.setShowsRootHandles(true);
        this.tree.setCellRenderer(new RemarkRenderer());
        this.tree.getEmptyText().setText("No review remarks");
        this.tree.getEmptyText().appendSecondaryText(
                "Add remarks with \"Add Review Remark\" in the editor's context menu", SimpleTextAttributes.GRAYED_ATTRIBUTES,
                null);
        ToolTipManager.sharedInstance().registerComponent(this.tree);
        this.tree.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    ReviewRemarksPanel.this.jumpToSelected();
                }
            }
        });
        this.tree.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER) {
                    ReviewRemarksPanel.this.jumpToSelected();
                    e.consume();
                }
            }
        });
        PopupHandler.installPopupMenu(this.tree,
                RemarkActions.create(this.project, this.model, this::getSelectedRemark, this::jumpToRemark),
                "CoRT.RemarksPopup");

        this.add(new JBScrollPane(this.tree), BorderLayout.CENTER);
        this.statusLabel.setBorder(JBUI.Borders.empty(2, 4));
        this.add(this.statusLabel, BorderLayout.SOUTH);
        this.updateStatus(0, 0);
    }

    private void showSyntaxExample() {
        final JBTextArea example = new JBTextArea(ReviewRemarksModel.createExampleText(), 18, 70);
        example.setEditable(false);
        final DialogBuilder builder = new DialogBuilder(this.project);
        builder.setTitle("Syntax of the Review Remarks");
        builder.setCenterPanel(new JBScrollPane(example));
        builder.addOkAction();
        builder.show();
    }

    private void updateSyntaxErrorBanner() {
        final String error = this.model.getParseError();
        if (error == null) {
            this.syntaxErrorBanner.setVisible(false);
        } else {
            this.syntaxErrorBanner.setText("Syntax error: " + error + " - correct the remarks text first");
            this.syntaxErrorBanner.setToolTipText("<html>The review remarks text has a syntax error: "
                    + escape(error) + "<br>The remarks cannot be changed (and no remark can be added) until the"
                    + " text is corrected, so that nothing is overwritten.</html>");
            this.syntaxErrorBanner.setVisible(true);
        }
    }

    private void rebuildTree() {
        this.updateSyntaxErrorBanner();
        final ReviewRemark previouslySelected = this.getSelectedRemark();
        this.treeRoot.removeAllChildren();

        final Map<Category, List<RemarkNode>> byCategory = new EnumMap<>(Category.class);
        final List<ReviewRound> rounds = this.model.getReviewData().getReviewRounds();
        int remarkCount = 0;
        int openCount = 0;
        for (int i = 0; i < rounds.size(); i++) {
            final boolean lastRound = i == rounds.size() - 1;
            for (final ReviewRemark remark : rounds.get(i).getRemarks()) {
                byCategory.computeIfAbsent(Category.classify(remark, lastRound), (c) -> new ArrayList<>())
                        .add(new RemarkNode(remark, rounds.get(i).getNumber()));
                remarkCount++;
                if (remark.needsFixing()) {
                    openCount++;
                }
            }
        }

        DefaultMutableTreeNode nodeToSelect = null;
        for (final Map.Entry<Category, List<RemarkNode>> entry : byCategory.entrySet()) {
            int open = 0;
            for (final RemarkNode r : entry.getValue()) {
                if (r.remark.needsFixing()) {
                    open++;
                }
            }
            final DefaultMutableTreeNode categoryNode = new DefaultMutableTreeNode(
                    new CategoryNode(entry.getKey(), entry.getValue().size(), open));
            for (final RemarkNode remarkNode : entry.getValue()) {
                final DefaultMutableTreeNode node = new DefaultMutableTreeNode(remarkNode);
                for (final ReviewRemarkComment comment : remarkNode.remark.getFollowUpComments()) {
                    node.add(new DefaultMutableTreeNode(new CommentNode(remarkNode.remark, comment)));
                }
                categoryNode.add(node);
                if (previouslySelected != null && sameRemark(previouslySelected, remarkNode.remark)) {
                    nodeToSelect = node;
                }
            }
            this.treeRoot.add(categoryNode);
        }
        this.treeModel.reload();
        // expand the categories, but keep the comments collapsed
        for (int i = 0; i < this.treeRoot.getChildCount(); i++) {
            this.tree.expandPath(new TreePath(
                    ((DefaultMutableTreeNode) this.treeRoot.getChildAt(i)).getPath()));
        }
        if (nodeToSelect != null) {
            final TreePath path = new TreePath(nodeToSelect.getPath());
            this.tree.setSelectionPath(path);
            this.tree.scrollPathToVisible(path);
        }
        this.updateStatus(remarkCount, openCount);
    }

    private static boolean sameRemark(ReviewRemark r1, ReviewRemark r2) {
        try {
            return r1 == r2 || r1.hasSameTextAndPositionAs(r2);
        } catch (final RuntimeException e) {
            return false;
        }
    }

    private void updateStatus(int remarkCount, int openCount) {
        if (this.model.getParseError() != null) {
            this.statusLabel.setText("Syntax error in the review remarks");
        } else if (remarkCount == 0) {
            this.statusLabel.setText("No remarks (round " + this.model.getCurrentRound() + ")");
        } else {
            this.statusLabel.setText(remarkCount + (remarkCount == 1 ? " remark, " : " remarks, ") + openCount
                    + " still to fix (round "
                    + this.model.getCurrentRound() + ")");
        }
    }

    private ReviewRemark getSelectedRemark() {
        final Object node = this.tree.getLastSelectedPathComponent();
        if (!(node instanceof DefaultMutableTreeNode)) {
            return null;
        }
        final Object userObject = ((DefaultMutableTreeNode) node).getUserObject();
        if (userObject instanceof RemarkNode) {
            return ((RemarkNode) userObject).remark;
        } else if (userObject instanceof CommentNode) {
            return ((CommentNode) userObject).remark;
        }
        return null;
    }

    private void jumpToSelected() {
        final ReviewRemark remark = this.getSelectedRemark();
        if (remark != null) {
            this.jumpToRemark(remark);
        }
    }

    /**
     * Selects the first remark that still needs fixing and jumps to its code. Returns false if
     * there is no such remark.
     */
    public boolean jumpToNextOpenRemark() {
        final ReviewRemark current = this.getSelectedRemark();
        final List<ReviewRemark> all = this.model.getAllRemarks();
        int start = 0;
        if (current != null) {
            for (int i = 0; i < all.size(); i++) {
                if (sameRemark(all.get(i), current)) {
                    start = i + 1;
                    break;
                }
            }
        }
        for (int i = 0; i < all.size(); i++) {
            final ReviewRemark candidate = all.get((start + i) % all.size());
            if (candidate.needsFixing()) {
                this.select(candidate);
                this.jumpToRemark(candidate);
                return true;
            }
        }
        IntellijNotifications.info(this.project, "There are no remarks left that need fixing.");
        return false;
    }

    private void select(ReviewRemark remark) {
        final DefaultMutableTreeNode node = TreeUtil.findNode(this.treeRoot, (n) ->
                n.getUserObject() instanceof RemarkNode && sameRemark(((RemarkNode) n.getUserObject()).remark, remark));
        if (node != null) {
            final TreePath path = new TreePath(node.getPath());
            this.tree.setSelectionPath(path);
            this.tree.scrollPathToVisible(path);
        }
    }

    /**
     * Opens the file of the given remark at its line (or at the start of the file for remarks that
     * refer to the whole file). Global remarks only get selected.
     */
    void jumpToRemark(ReviewRemark remark) {
        final Position position = Position.parse(remark.getPositionString());
        final String shortName = position.getShortFileName();
        if (shortName == null) {
            return;
        }
        final int line = position.getLine();
        IntellijMarkerFactory.runOnEdt(() -> {
            final VirtualFile vf = IntellijFileResolver.findByShortName(this.project, shortName);
            if (vf == null) {
                IntellijNotifications.warn(this.project,
                        "The file " + shortName + " of the remark could not be found in the project.");
                return;
            }
            new OpenFileDescriptor(this.project, vf, Math.max(0, line - 1), 0).navigate(true);
        });
    }

    static String describePosition(ReviewRemark remark) {
        try {
            final Position p = Position.parse(remark.getPositionString());
            if (p.getShortFileName() == null) {
                return "(global)";
            }
            return p.getShortFileName() + (p.getLine() > 0 ? ":" + p.getLine() : "");
        } catch (final RuntimeException e) {
            return "";
        }
    }

    static Icon iconFor(ReviewRemark remark) {
        switch (remark.getResolution()) {
        case FIXED:
            return AllIcons.Actions.Checked;
        case WONT_FIX:
            return AllIcons.Actions.Cancel;
        case QUESTION:
            return AllIcons.Actions.Help;
        case OPEN:
        default:
            break;
        }
        switch (remark.getRemarkType()) {
        case MUST_FIX:
            return AllIcons.General.Error;
        case CAN_FIX:
            return AllIcons.General.Warning;
        case ALREADY_FIXED:
            return AllIcons.Actions.Checked;
        case POSITIVE:
            return AllIcons.General.InspectionsOK;
        case TEMPORARY:
            return AllIcons.General.TodoDefault;
        case OTHER:
        default:
            return AllIcons.General.Information;
        }
    }

    static String typeLabel(ReviewRemark remark) {
        switch (remark.getRemarkType()) {
        case MUST_FIX:
            return "must fix";
        case CAN_FIX:
            return "can fix";
        case ALREADY_FIXED:
            return "already fixed";
        case POSITIVE:
            return "positive";
        case TEMPORARY:
            return "temporary";
        case OTHER:
        default:
            return "other";
        }
    }

    private static String firstLine(String s) {
        if (s == null) {
            return "";
        }
        final int nl = s.indexOf('\n');
        return nl < 0 ? s : s.substring(0, nl);
    }

    private static String toHtmlTooltip(ReviewRemark remark) {
        final StringBuilder sb = new StringBuilder("<html>");
        for (final ReviewRemarkComment c : remark.getComments()) {
            if (sb.length() > "<html>".length()) {
                sb.append("<br><br>");
            }
            if (!c.getUser().isEmpty()) {
                sb.append("<b>").append(escape(c.getUser())).append(":</b> ");
            }
            sb.append(escape(c.getText()).replace("\n", "<br>"));
        }
        return sb.append("</html>").toString();
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /**
     * Renderer for categories, remarks and comments.
     */
    private static final class RemarkRenderer extends ColoredTreeCellRenderer {
        private static final long serialVersionUID = 1L;

        @Override
        public void customizeCellRenderer(JTree tree, Object value, boolean selected, boolean expanded,
                boolean leaf, int row, boolean hasFocus) {
            this.setToolTipText(null);
            if (!(value instanceof DefaultMutableTreeNode)) {
                return;
            }
            final Object userObject = ((DefaultMutableTreeNode) value).getUserObject();
            if (userObject instanceof CategoryNode) {
                final CategoryNode c = (CategoryNode) userObject;
                this.setIcon(AllIcons.Nodes.Folder);
                this.append(c.category.text, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES);
                final String count;
                if (c.category != Category.WORK) {
                    count = Integer.toString(c.count);
                } else if (c.open == c.count) {
                    count = c.open + " open";
                } else {
                    count = c.open + " of " + c.count + " open";
                }
                this.append("  " + count, SimpleTextAttributes.GRAYED_ATTRIBUTES);
            } else if (userObject instanceof RemarkNode) {
                final RemarkNode r = (RemarkNode) userObject;
                try {
                    this.renderRemark(r);
                } catch (final RuntimeException e) {
                    this.append(r.remark.getText());
                }
            } else if (userObject instanceof CommentNode) {
                final CommentNode c = (CommentNode) userObject;
                this.setIcon(AllIcons.General.Balloon);
                if (!c.comment.getUser().isEmpty()) {
                    this.append(c.comment.getUser() + ": ", SimpleTextAttributes.GRAYED_BOLD_ATTRIBUTES);
                }
                this.append(firstLine(c.comment.getText()));
                this.setToolTipText("<html>" + escape(c.comment.getText()).replace("\n", "<br>") + "</html>");
            } else if (userObject != null) {
                this.append(userObject.toString());
            }
        }

        private void renderRemark(RemarkNode r) {
            final ReviewRemark remark = r.remark;
            this.setIcon(iconFor(remark));
            final boolean resolved = remark.getResolution() == ResolutionType.FIXED
                    || remark.getResolution() == ResolutionType.WONT_FIX;
            this.append(describePosition(remark), resolved
                    ? SimpleTextAttributes.GRAYED_ATTRIBUTES : SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES);
            this.append("  " + firstLine(remark.getText()), resolved
                    ? SimpleTextAttributes.GRAYED_ATTRIBUTES : SimpleTextAttributes.REGULAR_ATTRIBUTES);
            this.append("   " + typeLabel(remark), SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES);
            switch (remark.getRemarkType() == RemarkType.ALREADY_FIXED ? ResolutionType.OPEN : remark.getResolution()) {
            case FIXED:
                this.append("  fixed", FIXED_ATTRIBUTES);
                break;
            case WONT_FIX:
                this.append("  won't fix", WONT_FIX_ATTRIBUTES);
                break;
            case QUESTION:
                this.append("  unclear", QUESTION_ATTRIBUTES);
                break;
            default:
                break;
            }
            this.append("  round " + r.round, SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES);
            this.setToolTipText(toHtmlTooltip(remark));
        }
    }

}
