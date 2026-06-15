package de.setsoftware.reviewtool.intellij;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JTree;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;

import com.intellij.ide.actions.RevealFileAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.components.JBScrollPane;

import de.setsoftware.reviewtool.model.api.IFragment;
import de.setsoftware.reviewtool.model.api.IRevisionedFile;
import de.setsoftware.reviewtool.model.changestructure.IStopMarker;
import de.setsoftware.reviewtool.model.changestructure.IStopMarkerFactory;
import de.setsoftware.reviewtool.model.changestructure.Stop;
import de.setsoftware.reviewtool.model.changestructure.Tour;
import de.setsoftware.reviewtool.model.changestructure.TourElement;
import de.setsoftware.reviewtool.model.changestructure.ToursInReview;

/**
 * Shows the review tours of the currently reviewed ticket as a tree of tours and their stops.
 * The top-level tours can be reordered manually ("tour ordering"), a tour can be activated and
 * double clicking a stop opens the file at the stop's position. Activating/reordering keeps the
 * stop markers in the editor gutters in sync.
 */
public final class ReviewToursPanel extends JPanel {

    private static final long serialVersionUID = 1L;

    /**
     * Tree node payload for a tour.
     */
    private static final class TourNode {
        private final Tour tour;

        TourNode(Tour tour) {
            this.tour = tour;
        }
    }

    /**
     * Tree node payload for a stop (knowing its containing top-level tour).
     */
    private static final class StopNode {
        private final Stop stop;

        StopNode(Stop stop) {
            this.stop = stop;
        }
    }

    private final Project project;
    private final IntellijMarkerFactory markerFactory;
    private final DefaultMutableTreeNode treeRoot = new DefaultMutableTreeNode("No tours");
    private final DefaultTreeModel treeModel = new DefaultTreeModel(this.treeRoot);
    private final JTree tree = new JTree(this.treeModel);
    private final JLabel statusLabel = new JLabel("No tours created yet.");

    private final Set<Stop> checkedStops = new HashSet<>();

    private ToursInReview tours;
    private boolean hideIrrelevant;
    private boolean hideChecked;
    private Stop currentStop;

    public ReviewToursPanel(Project project, IntellijMarkerFactory markerFactory) {
        super(new BorderLayout());
        this.project = project;
        this.markerFactory = markerFactory;
        this.buildUi();
    }

    private void buildUi() {
        final JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT));
        final JButton upButton = new JButton("Move Tour Up");
        upButton.addActionListener((e) -> this.moveSelectedTour(-1));
        toolbar.add(upButton);
        final JButton downButton = new JButton("Move Tour Down");
        downButton.addActionListener((e) -> this.moveSelectedTour(1));
        toolbar.add(downButton);
        final JButton activateButton = new JButton("Activate Tour");
        activateButton.addActionListener((e) -> this.activateSelectedTour());
        toolbar.add(activateButton);
        final JButton showCodeButton = new JButton("Show Stop Code");
        showCodeButton.addActionListener((e) -> this.showSelectedStopCode());
        toolbar.add(showCodeButton);
        final JButton prevButton = new JButton("Previous Stop");
        prevButton.addActionListener((e) -> this.navigate(-1));
        toolbar.add(prevButton);
        final JButton nextButton = new JButton("Next Stop");
        nextButton.addActionListener((e) -> this.navigate(1));
        toolbar.add(nextButton);
        final JButton nextUncheckedButton = new JButton("Next Unchecked");
        nextUncheckedButton.addActionListener((e) -> this.jumpToNextUnchecked());
        toolbar.add(nextUncheckedButton);
        final JButton refreshMarkersButton = new JButton("Refresh Stop Markers");
        refreshMarkersButton.addActionListener((e) -> this.renderStopMarkers());
        toolbar.add(refreshMarkersButton);
        final JCheckBox hideIrrelevantBox = new JCheckBox("Hide irrelevant");
        hideIrrelevantBox.addActionListener((e) -> {
            this.hideIrrelevant = hideIrrelevantBox.isSelected();
            this.rebuildTree();
        });
        toolbar.add(hideIrrelevantBox);
        final JCheckBox hideCheckedBox = new JCheckBox("Hide checked");
        hideCheckedBox.addActionListener((e) -> {
            this.hideChecked = hideCheckedBox.isSelected();
            this.rebuildTree();
        });
        toolbar.add(hideCheckedBox);
        this.add(toolbar, BorderLayout.NORTH);

        this.tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        this.tree.setRootVisible(true);
        this.tree.setCellRenderer(new CellRenderer());
        this.tree.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    ReviewToursPanel.this.handleDoubleClick();
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
                    ReviewToursPanel.this.showContextMenu(e);
                }
            }
        });
        this.add(new JBScrollPane(this.tree), BorderLayout.CENTER);
        this.add(this.statusLabel, BorderLayout.SOUTH);
    }

    /**
     * Sets the tours to display and renders the stop markers for the active tour.
     */
    public void setTours(ToursInReview tours) {
        this.tours = tours;
        this.rebuildTree();
        this.renderStopMarkers();
    }

    private void rebuildTree() {
        IntellijMarkerFactory.runOnEdt(() -> {
            this.treeRoot.removeAllChildren();
            if (this.tours == null) {
                this.treeRoot.setUserObject("No tours");
                this.statusLabel.setText("No tours created yet.");
            } else {
                final List<Tour> topmost = this.tours.getTopmostTours();
                this.treeRoot.setUserObject(topmost.size() + " tours");
                final Tour active = this.tours.getActiveTour();
                int stopCount = 0;
                for (final Tour tour : topmost) {
                    final DefaultMutableTreeNode tourNode =
                            new DefaultMutableTreeNode(new TourNode(tour));
                    this.addChildren(tourNode, tour);
                    this.treeRoot.add(tourNode);
                    stopCount += tour.getStops().size();
                }
                this.statusLabel.setText(topmost.size() + " tours, " + stopCount
                        + " stops, active: "
                        + (active == null ? "-" : firstLine(active.getDescription())));
            }
            this.treeModel.reload();
            for (int i = 0; i < this.tree.getRowCount(); i++) {
                this.tree.expandRow(i);
            }
        });
    }

    private void addChildren(DefaultMutableTreeNode parentNode, Tour tour) {
        for (final TourElement element : tour.getChildren()) {
            if (element instanceof Tour) {
                final Tour subTour = (Tour) element;
                final DefaultMutableTreeNode subNode = new DefaultMutableTreeNode(new TourNode(subTour));
                this.addChildren(subNode, subTour);
                parentNode.add(subNode);
            } else if (element instanceof Stop) {
                final Stop stop = (Stop) element;
                if (this.hideIrrelevant && this.isIrrelevant(stop)) {
                    continue;
                }
                if (this.hideChecked && this.checkedStops.contains(stop)) {
                    continue;
                }
                parentNode.add(new DefaultMutableTreeNode(new StopNode(stop)));
            }
        }
    }

    private boolean isIrrelevant(Stop stop) {
        return this.tours != null && stop.isIrrelevantForReview(this.tours.getIrrelevantCategories());
    }

    private DefaultMutableTreeNode getSelectedNode() {
        final Object node = this.tree.getLastSelectedPathComponent();
        return node instanceof DefaultMutableTreeNode ? (DefaultMutableTreeNode) node : null;
    }

    private Tour getSelectedTopmostTour() {
        if (this.tours == null) {
            return null;
        }
        final DefaultMutableTreeNode node = this.getSelectedNode();
        if (node == null || !(node.getUserObject() instanceof TourNode)) {
            return null;
        }
        final Tour tour = ((TourNode) node.getUserObject()).tour;
        return this.tours.getTopmostTours().contains(tour) ? tour : null;
    }

    private void moveSelectedTour(int direction) {
        final Tour tour = this.getSelectedTopmostTour();
        if (tour == null) {
            return;
        }
        final List<Tour> topmost = this.tours.getTopmostTours();
        final int index = topmost.indexOf(tour);
        final int target = index + direction;
        if (target < 0 || target >= topmost.size()) {
            return;
        }
        Collections.swap(topmost, index, target);
        this.rebuildTree();
        this.renderStopMarkers();
    }

    private void activateSelectedTour() {
        final Tour tour = this.getSelectedTopmostTour();
        if (tour == null) {
            return;
        }
        this.tours.ensureTourActive(tour, new NoOpStopMarkerFactory(), false);
        this.rebuildTree();
        this.renderStopMarkers();
    }

    private void handleDoubleClick() {
        final DefaultMutableTreeNode node = this.getSelectedNode();
        if (node == null) {
            return;
        }
        if (node.getUserObject() instanceof StopNode) {
            this.jumpToStop(((StopNode) node.getUserObject()).stop);
        } else if (node.getUserObject() instanceof TourNode) {
            this.activateSelectedTour();
        }
    }

    private void showSelectedStopCode() {
        final Stop stop = this.getSelectedStop();
        if (stop != null) {
            this.jumpToStop(stop);
        }
    }

    private Stop getSelectedStop() {
        final DefaultMutableTreeNode node = this.getSelectedNode();
        if (node != null && node.getUserObject() instanceof StopNode) {
            return ((StopNode) node.getUserObject()).stop;
        }
        return null;
    }

    private void showContextMenu(MouseEvent e) {
        final TreePath path = this.tree.getPathForLocation(e.getX(), e.getY());
        if (path == null) {
            return;
        }
        this.tree.setSelectionPath(path);
        final Object node = path.getLastPathComponent();
        if (!(node instanceof DefaultMutableTreeNode)) {
            return;
        }
        final Object userObject = ((DefaultMutableTreeNode) node).getUserObject();
        final JPopupMenu menu = new JPopupMenu();
        if (userObject instanceof StopNode) {
            final Stop stop = ((StopNode) userObject).stop;
            menu.add(menuItem("Show code", () -> this.jumpToStop(stop)));
            menu.add(menuItem("Open containing folder", () -> this.openContainingFolder(stop)));
            final boolean checked = this.checkedStops.contains(stop);
            menu.add(menuItem(checked ? "Unmark as checked" : "Mark as checked",
                    () -> this.toggleChecked(stop)));
        } else if (userObject instanceof TourNode) {
            final Tour tour = ((TourNode) userObject).tour;
            if (this.tours != null && this.tours.getTopmostTours().contains(tour)) {
                menu.add(menuItem("Activate tour", this::activateSelectedTour));
                menu.add(menuItem("Move tour up", () -> this.moveSelectedTour(-1)));
                menu.add(menuItem("Move tour down", () -> this.moveSelectedTour(1)));
            }
        }
        if (menu.getComponentCount() > 0) {
            menu.show(this.tree, e.getX(), e.getY());
        }
    }

    private static JMenuItem menuItem(String text, Runnable action) {
        final JMenuItem item = new JMenuItem(text);
        item.addActionListener((e) -> action.run());
        return item;
    }

    private void toggleChecked(Stop stop) {
        if (!this.checkedStops.remove(stop)) {
            this.checkedStops.add(stop);
        }
        this.rebuildTree();
    }

    private void openContainingFolder(Stop stop) {
        final File file = stop.getAbsoluteFile();
        IntellijMarkerFactory.runOnEdt(() -> RevealFileAction.openFile(file));
    }

    /**
     * Collects all relevant stops of all tours in display order.
     */
    private List<Stop> flattenRelevantStops() {
        final List<Stop> ret = new ArrayList<>();
        if (this.tours != null) {
            for (final Tour tour : this.tours.getTopmostTours()) {
                for (final Stop stop : tour.getStops()) {
                    if (!this.isIrrelevant(stop)) {
                        ret.add(stop);
                    }
                }
            }
        }
        return ret;
    }

    private void navigate(int direction) {
        final List<Stop> stops = this.flattenRelevantStops();
        if (stops.isEmpty()) {
            return;
        }
        final int index = this.currentStop == null ? -1 : stops.indexOf(this.currentStop);
        final int target = index + direction;
        if (target < 0 || target >= stops.size()) {
            return;
        }
        this.jumpToStop(stops.get(target));
    }

    private void jumpToNextUnchecked() {
        final List<Stop> stops = this.flattenRelevantStops();
        if (stops.isEmpty()) {
            return;
        }
        final int start = this.currentStop == null ? 0 : stops.indexOf(this.currentStop) + 1;
        for (int i = 0; i < stops.size(); i++) {
            final Stop candidate = stops.get((start + i) % stops.size());
            if (!this.checkedStops.contains(candidate)) {
                this.jumpToStop(candidate);
                return;
            }
        }
    }

    /**
     * Opens the file of the given stop and selects the changed line range, so the corresponding
     * code location is shown.
     */
    private void jumpToStop(Stop stop) {
        this.currentStop = stop;
        final File file = stop.getAbsoluteFile();
        final boolean detailed = stop.isDetailedFragmentKnown();
        final int fromLine = detailed ? stop.getMostRecentFragment().getFrom().getLine() : 1;
        final int toLine = detailed ? stop.getMostRecentFragment().getTo().getLine() : fromLine;
        IntellijMarkerFactory.runOnEdt(() -> {
            final VirtualFile vf = IntellijFileResolver.findByAbsoluteFile(file);
            if (vf == null) {
                return;
            }
            final OpenFileDescriptor descriptor =
                    new OpenFileDescriptor(this.project, vf, Math.max(0, fromLine - 1), 0);
            final Editor editor = FileEditorManager.getInstance(this.project).openTextEditor(descriptor, true);
            if (editor != null && detailed) {
                final Document doc = editor.getDocument();
                final int lastLine = doc.getLineCount() - 1;
                final int from = Math.max(0, Math.min(fromLine - 1, lastLine));
                final int to = Math.max(from, Math.min(toLine - 1, lastLine));
                editor.getSelectionModel().setSelection(doc.getLineStartOffset(from), doc.getLineEndOffset(to));
            }
        });
    }

    /**
     * Removes the existing stop markers and renders fresh ones for all tours, highlighting the
     * stops of the currently active tour.
     */
    private void renderStopMarkers() {
        if (this.tours == null) {
            return;
        }
        IntellijMarkerFactory.runOnEdt(this::doRenderStopMarkers);
    }

    private void doRenderStopMarkers() {
        this.markerFactory.clearStopMarkers();
        final Tour active = this.tours.getActiveTour();
        for (final Tour tour : this.tours.getTopmostTours()) {
            final boolean isActive = tour.equals(active);
            final String description = firstLine(tour.getDescription());
            for (final Stop stop : tour.getStops()) {
                if (this.isIrrelevant(stop)) {
                    continue;
                }
                final com.intellij.openapi.vfs.VirtualFile vf =
                        IntellijFileResolver.findByAbsoluteFile(stop.getAbsoluteFile());
                if (vf == null) {
                    continue;
                }
                final int fromLine;
                final int toLine;
                if (stop.isDetailedFragmentKnown()) {
                    final IFragment fragment = stop.getMostRecentFragment();
                    fromLine = fragment.getFrom().getLine();
                    toLine = fragment.getTo().getLine();
                } else {
                    fromLine = 1;
                    toLine = 1;
                }
                final String message = stop.getClassificationFormatted() + description;
                this.markerFactory.createStopMarker(vf, fromLine, toLine, isActive, message);
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

    /**
     * Renderer for the tree node labels.
     */
    private final class CellRenderer extends javax.swing.tree.DefaultTreeCellRenderer {
        private static final long serialVersionUID = 1L;

        @Override
        public java.awt.Component getTreeCellRendererComponent(
                JTree tree, Object value, boolean sel, boolean expanded,
                boolean leaf, int row, boolean hasFocus) {
            super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus);
            if (value instanceof DefaultMutableTreeNode) {
                final Object userObject = ((DefaultMutableTreeNode) value).getUserObject();
                if (userObject instanceof TourNode) {
                    final Tour tour = ((TourNode) userObject).tour;
                    final ToursInReview t = ReviewToursPanel.this.tours;
                    final boolean active = t != null && tour.equals(t.getActiveTour());
                    this.setText((active ? "* " : "") + firstLine(tour.getDescription())
                            + "  (" + tour.getStops().size() + " stops)");
                } else if (userObject instanceof StopNode) {
                    final Stop stop = ((StopNode) userObject).stop;
                    final boolean checked = ReviewToursPanel.this.checkedStops.contains(stop);
                    final StringBuilder text = new StringBuilder(checked ? "✓ " : "");
                    text.append(stopLabel(stop));
                    final String classification = stop.getClassificationFormatted().trim();
                    if (!classification.isEmpty()) {
                        text.append("  [").append(classification).append(']');
                    }
                    if (ReviewToursPanel.this.isIrrelevant(stop)) {
                        text.append("  (irrelevant)");
                    }
                    this.setText(text.toString());
                }
            }
            return this;
        }
    }

    private static String stopLabel(Stop stop) {
        final String fileName = stop.getAbsoluteFile().getName();
        if (stop.isDetailedFragmentKnown()) {
            final IFragment fragment = stop.getMostRecentFragment();
            final int from = fragment.getFrom().getLine();
            final int to = fragment.getTo().getLine();
            return fileName + " : " + (from == to ? Integer.toString(from) : from + "-" + to);
        }
        return fileName;
    }

    /**
     * No-op stop marker factory used to switch the active tour in the core model without letting
     * the core create its own markers (the panel renders the IntelliJ markers itself).
     */
    private static final class NoOpStopMarkerFactory implements IStopMarkerFactory {

        @Override
        public IStopMarker createStopMarker(IRevisionedFile file, boolean tourActive, String message) {
            return NoOpStopMarker.INSTANCE;
        }

        @Override
        public IStopMarker createStopMarker(
                IRevisionedFile file, boolean tourActive, String message, IFragment pos) {
            return NoOpStopMarker.INSTANCE;
        }

        @Override
        public void clearStopMarkers() {
            //nothing to do
        }
    }

    /**
     * No-op stop marker.
     */
    private static final class NoOpStopMarker implements IStopMarker {
        private static final NoOpStopMarker INSTANCE = new NoOpStopMarker();

        @Override
        public void openEditor(boolean forceTextEditor) {
            //nothing to do
        }

        @Override
        public void delete() {
            //nothing to do
        }
    }

}
