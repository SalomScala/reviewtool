package de.setsoftware.reviewtool.intellij;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.util.Collections;
import java.util.List;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTree;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreeSelectionModel;

import com.intellij.openapi.project.Project;
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

    private ToursInReview tours;

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
        final JButton refreshMarkersButton = new JButton("Refresh Stop Markers");
        refreshMarkersButton.addActionListener((e) -> this.renderStopMarkers());
        toolbar.add(refreshMarkersButton);
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
                parentNode.add(new DefaultMutableTreeNode(new StopNode((Stop) element)));
            }
        }
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

    private void jumpToStop(Stop stop) {
        final File file = stop.getAbsoluteFile();
        final int line = stop.isDetailedFragmentKnown()
                ? stop.getMostRecentFragment().getFrom().getLine()
                : 1;
        IntellijMarkerFactory.runOnEdt(() -> {
            final com.intellij.openapi.vfs.VirtualFile vf = IntellijFileResolver.findByAbsoluteFile(file);
            if (vf == null) {
                return;
            }
            new com.intellij.openapi.fileEditor.OpenFileDescriptor(
                    this.project, vf, Math.max(0, line - 1), 0).navigate(true);
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
                    this.setText(stopLabel(((StopNode) userObject).stop));
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
