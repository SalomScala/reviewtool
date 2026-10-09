package de.setsoftware.reviewtool.intellij;

import java.awt.BorderLayout;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;

import javax.swing.Icon;
import javax.swing.JPanel;
import javax.swing.JTree;
import javax.swing.ToolTipManager;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;

import com.intellij.icons.AllIcons;
import com.intellij.ide.actions.RevealFileAction;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.ScrollType;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.DumbAwareToggleAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.ColoredTreeCellRenderer;
import com.intellij.ui.PopupHandler;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.treeStructure.Tree;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.tree.TreeUtil;

import de.setsoftware.reviewtool.base.Pair;
import de.setsoftware.reviewtool.model.api.IFragment;
import de.setsoftware.reviewtool.model.api.IRevisionedFile;
import de.setsoftware.reviewtool.model.changestructure.IStopMarker;
import de.setsoftware.reviewtool.model.changestructure.IStopMarkerFactory;
import de.setsoftware.reviewtool.model.changestructure.Stop;
import de.setsoftware.reviewtool.model.changestructure.Tour;
import de.setsoftware.reviewtool.model.changestructure.TourElement;
import de.setsoftware.reviewtool.model.changestructure.ToursInReview;
import de.setsoftware.reviewtool.model.viewtracking.IViewStatisticsListener;
import de.setsoftware.reviewtool.model.viewtracking.ViewStatDataForStop;
import de.setsoftware.reviewtool.model.viewtracking.ViewStatistics;
import de.setsoftware.reviewtool.model.viewtracking.ViewStatistics.INextStopCallback;

/**
 * Shows the review tours of the currently reviewed ticket as a tree of tours and their stops.
 * The top-level tours can be reordered manually ("tour ordering"), a tour can be activated and
 * double clicking a stop opens the file at the stop's position. Navigating to a stop activates the
 * tour containing it, selects the stop in the tree and keeps the stop markers in the editor gutters
 * in sync. A progress bar shows how many relevant stops have not been visited yet.
 */
public final class ReviewToursPanel extends JPanel {

    private static final long serialVersionUID = 1L;

    private static final String HIDE_IRRELEVANT_KEY = "de.setsoftware.reviewtool.hideIrrelevant";
    private static final String HIDE_CHECKED_KEY = "de.setsoftware.reviewtool.hideChecked";
    private static final String HIDE_VISITED_KEY = "de.setsoftware.reviewtool.hideVisited";

    private static final SimpleTextAttributes ACTIVE_TOUR_ATTRIBUTES = SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES;

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
     * Tree node payload for a stop.
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
    private final Tree tree = new Tree(this.treeModel);
    private final JBLabel statusLabel = new JBLabel();
    private final ReviewProgressBar progressBar = new ReviewProgressBar();

    private static final int LONG_ENOUGH_VIEW_COUNT = 2;

    private final ViewStatistics statistics = new ViewStatistics();
    private final EditorViewTracker viewTracker;
    // kept in a field because ViewStatistics holds its listeners only weakly
    private final IViewStatisticsListener statisticsListener =
            (file) -> IntellijMarkerFactory.runOnEdt(this::statisticsChanged);

    private ToursInReview tours;
    private boolean hideIrrelevant;
    private boolean hideChecked;
    private boolean hideVisited;
    private Stop currentStop;
    private int lastVisitedCount = -1;

    public ReviewToursPanel(Project project, IntellijMarkerFactory markerFactory) {
        super(new BorderLayout());
        this.project = project;
        this.markerFactory = markerFactory;
        this.viewTracker = new EditorViewTracker(project, this.statistics);
        final PropertiesComponent properties = PropertiesComponent.getInstance();
        this.hideIrrelevant = properties.getBoolean(HIDE_IRRELEVANT_KEY, false);
        this.hideChecked = properties.getBoolean(HIDE_CHECKED_KEY, false);
        this.hideVisited = properties.getBoolean(HIDE_VISITED_KEY, false);
        this.buildUi();
        this.statistics.addListener(this.statisticsListener);
        this.viewTracker.start();
        this.rebuildTree();
    }

    private void buildUi() {
        final DefaultActionGroup group = new DefaultActionGroup();
        group.add(PanelActions.action("Previous Stop", AllIcons.Actions.PreviousOccurence, this::hasTours,
                () -> this.navigate(-1)));
        group.add(PanelActions.action("Next Stop", AllIcons.Actions.NextOccurence, this::hasTours,
                () -> this.navigate(1)));
        group.add(PanelActions.action("Jump to Next Unvisited Stop", AllIcons.Actions.Forward, this::hasTours,
                this::jumpToNextUnvisitedStop));
        group.addSeparator();
        group.add(PanelActions.action("Show Stop Code", AllIcons.Actions.EditSource, () -> this.getSelectedStop() != null,
                this::showSelectedStopCode));
        group.add(PanelActions.action("Show Stop Diff", AllIcons.Actions.Diff, () -> this.getSelectedStop() != null,
                this::showSelectedStopDiff));
        group.add(PanelActions.action("Mark/Unmark Stop as Checked", AllIcons.Actions.Checked,
                () -> this.getSelectedStop() != null,
                () -> this.toggleChecked(this.getSelectedStop())));
        group.addSeparator();
        group.add(PanelActions.action("Activate Tour", AllIcons.Actions.Execute, () -> this.getSelectedTopmostTour() != null,
                this::activateSelectedTour));
        group.add(PanelActions.action("Move Tour Up", AllIcons.Actions.MoveUp, () -> this.getSelectedTopmostTour() != null,
                () -> this.moveSelectedTour(-1)));
        group.add(PanelActions.action("Move Tour Down", AllIcons.Actions.MoveDown, () -> this.getSelectedTopmostTour() != null,
                () -> this.moveSelectedTour(1)));
        group.addSeparator();
        group.add(this.toggle("Hide Irrelevant Stops", AllIcons.General.Filter, HIDE_IRRELEVANT_KEY,
                () -> this.hideIrrelevant, (v) -> this.hideIrrelevant = v));
        group.add(this.toggle("Hide Stops Marked as Checked", AllIcons.Actions.ToggleVisibility, HIDE_CHECKED_KEY,
                () -> this.hideChecked, (v) -> this.hideChecked = v));
        group.add(this.toggle("Hide Visited Stops", AllIcons.Actions.Show, HIDE_VISITED_KEY,
                () -> this.hideVisited, (v) -> this.hideVisited = v));
        group.addSeparator();
        group.add(PanelActions.action("Refresh Stop Markers", AllIcons.Actions.Refresh, this::hasTours,
                this::renderStopMarkers));
        group.add(PanelActions.action("Expand All", AllIcons.Actions.Expandall, () -> true,
                () -> TreeUtil.expandAll(this.tree)));
        group.add(PanelActions.action("Collapse All", AllIcons.Actions.Collapseall, () -> true,
                () -> TreeUtil.collapseAll(this.tree, 1)));
        final ActionToolbar toolbar = ActionManager.getInstance().createActionToolbar("CoRT.Tours", group, true);
        toolbar.setTargetComponent(this);
        this.add(toolbar.getComponent(), BorderLayout.NORTH);

        this.tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        this.tree.setRootVisible(false);
        this.tree.setShowsRootHandles(true);
        this.tree.setCellRenderer(new CellRenderer());
        this.tree.getEmptyText().setText("No tours created yet");
        this.tree.getEmptyText().appendSecondaryText(
                "Select a ticket and use \"Create Tours\" (or \"Start Review\")",
                SimpleTextAttributes.GRAYED_ATTRIBUTES, null);
        ToolTipManager.sharedInstance().registerComponent(this.tree);
        this.tree.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    ReviewToursPanel.this.handleDoubleClick();
                }
            }
        });
        this.tree.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER) {
                    ReviewToursPanel.this.handleDoubleClick();
                    e.consume();
                }
            }
        });
        PopupHandler.installPopupMenu(this.tree, this.createContextMenu(), "CoRT.ToursPopup");
        this.add(new JBScrollPane(this.tree), BorderLayout.CENTER);

        final JPanel bottom = new JPanel(new BorderLayout());
        bottom.setBorder(JBUI.Borders.empty(2, 4));
        bottom.add(this.progressBar, BorderLayout.CENTER);
        bottom.add(this.statusLabel, BorderLayout.SOUTH);
        this.add(bottom, BorderLayout.SOUTH);
    }

    private DefaultActionGroup createContextMenu() {
        final DefaultActionGroup menu = new DefaultActionGroup();
        menu.add(PanelActions.action("Show Code", AllIcons.Actions.EditSource, () -> this.getSelectedStop() != null,
                this::showSelectedStopCode));
        menu.add(PanelActions.action("Show Diff", AllIcons.Actions.Diff, () -> this.getSelectedStop() != null,
                this::showSelectedStopDiff));
        menu.add(PanelActions.action("Open Containing Folder", AllIcons.Actions.MenuOpen, () -> this.getSelectedStop() != null,
                () -> this.openContainingFolder(this.getSelectedStop())));
        menu.add(PanelActions.action(
                () -> this.getSelectedStop() != null && this.isChecked(this.getSelectedStop())
                        ? "Unmark as Checked" : "Mark as Checked",
                AllIcons.Actions.Checked,
                () -> this.getSelectedStop() != null,
                () -> this.toggleChecked(this.getSelectedStop())));
        menu.add(PanelActions.action("Activate Tour", AllIcons.Actions.Execute, () -> this.getSelectedTopmostTour() != null,
                this::activateSelectedTour));
        menu.add(PanelActions.action("Move Tour Up", AllIcons.Actions.MoveUp, () -> this.getSelectedTopmostTour() != null,
                () -> this.moveSelectedTour(-1)));
        menu.add(PanelActions.action("Move Tour Down", AllIcons.Actions.MoveDown, () -> this.getSelectedTopmostTour() != null,
                () -> this.moveSelectedTour(1)));
        return menu;
    }

    private DumbAwareToggleAction toggle(String text, Icon icon, String key,
            BooleanSupplier getter, Consumer<Boolean> setter) {
        return PanelActions.toggle(text, icon, getter, (state) -> {
            setter.accept(state);
            PropertiesComponent.getInstance().setValue(key, state, false);
            this.rebuildTree();
        });
    }

    /**
     * Stops the view tracking.
     */
    void dispose() {
        this.viewTracker.stop();
    }

    /**
     * Sets the tours to display and renders the stop markers for the active tour.
     */
    public void setTours(ToursInReview tours) {
        this.tours = tours;
        this.currentStop = null;
        this.rebuildTree();
        this.renderStopMarkers();
    }

    /**
     * Returns true iff tours have been created.
     */
    public boolean hasTours() {
        return this.tours != null;
    }

    private void rebuildTree() {
        IntellijMarkerFactory.runOnEdt(() -> {
            this.treeRoot.removeAllChildren();
            DefaultMutableTreeNode currentStopNode = null;
            if (this.tours != null) {
                for (final Tour tour : this.tours.getTopmostTours()) {
                    final DefaultMutableTreeNode tourNode = new DefaultMutableTreeNode(new TourNode(tour));
                    final DefaultMutableTreeNode found = this.addChildren(tourNode, tour);
                    if (found != null) {
                        currentStopNode = found;
                    }
                    this.treeRoot.add(tourNode);
                }
            }
            this.treeModel.reload();
            TreeUtil.expandAll(this.tree);
            if (currentStopNode != null) {
                final TreePath path = new TreePath(currentStopNode.getPath());
                this.tree.setSelectionPath(path);
                this.tree.scrollPathToVisible(path);
            }
            this.updateStatus();
        });
    }

    /**
     * Adds the child nodes for the given tour and returns the node of the current stop, if it is
     * contained.
     */
    private DefaultMutableTreeNode addChildren(DefaultMutableTreeNode parentNode, Tour tour) {
        DefaultMutableTreeNode currentStopNode = null;
        for (final TourElement element : tour.getChildren()) {
            if (element instanceof Tour) {
                final Tour subTour = (Tour) element;
                final DefaultMutableTreeNode subNode = new DefaultMutableTreeNode(new TourNode(subTour));
                final DefaultMutableTreeNode found = this.addChildren(subNode, subTour);
                if (found != null) {
                    currentStopNode = found;
                }
                parentNode.add(subNode);
            } else if (element instanceof Stop) {
                final Stop stop = (Stop) element;
                if (this.hideIrrelevant && this.isIrrelevant(stop)) {
                    continue;
                }
                if (this.hideChecked && this.isChecked(stop)) {
                    continue;
                }
                if (this.hideVisited && this.isFullyVisited(stop)) {
                    continue;
                }
                final DefaultMutableTreeNode stopNode = new DefaultMutableTreeNode(new StopNode(stop));
                if (stop == this.currentStop) {
                    currentStopNode = stopNode;
                }
                parentNode.add(stopNode);
            }
        }
        return currentStopNode;
    }

    private void statisticsChanged() {
        final int visitedCount = this.countStops(this::isFullyVisited);
        if (this.hideVisited && visitedCount != this.lastVisitedCount) {
            // the set of shown stops changed
            this.rebuildTree();
        } else {
            this.tree.repaint();
            this.updateStatus();
        }
        this.lastVisitedCount = visitedCount;
    }

    private int countStops(Predicate<Stop> predicate) {
        int count = 0;
        if (this.tours != null) {
            for (final Tour tour : this.tours.getTopmostTours()) {
                for (final Stop stop : tour.getStops()) {
                    if (predicate.test(stop)) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    private void updateStatus() {
        if (this.tours == null) {
            this.progressBar.clear();
            this.statusLabel.setText("No tours created yet.");
            return;
        }
        int irrelevant = 0;
        int visited = 0;
        int partlyVisited = 0;
        int unvisited = 0;
        int stopCount = 0;
        for (final Tour t : this.tours.getTopmostTours()) {
            for (final Stop s : t.getStops()) {
                stopCount++;
                if (this.isChecked(s)) {
                    visited++;
                    continue;
                }
                final ViewStatDataForStop ratio = this.statistics.determineViewRatio(s, LONG_ENOUGH_VIEW_COUNT);
                if (ratio.isNotViewedAtAll()) {
                    if (this.isIrrelevant(s)) {
                        irrelevant++;
                    } else {
                        unvisited++;
                    }
                } else if (ratio.isPartlyUnvisited()) {
                    partlyVisited++;
                } else {
                    visited++;
                }
            }
        }
        this.progressBar.setCounts(irrelevant, visited, partlyVisited, unvisited);
        final Tour active = this.tours.getActiveTour();
        final int tourCount = this.tours.getTopmostTours().size();
        this.statusLabel.setText(tourCount + (tourCount == 1 ? " tour, " : " tours, ") + stopCount
                + (stopCount == 1 ? " stop" : " stops") + ", active tour: " + (active == null ? "-" : firstLine(active.getDescription())));
    }

    private boolean isIrrelevant(Stop stop) {
        return this.tours != null && stop.isIrrelevantForReview(this.tours.getIrrelevantCategories());
    }

    private boolean isChecked(Stop stop) {
        return this.statistics.isMarkedAsChecked(stop);
    }

    private boolean isFullyVisited(Stop stop) {
        final ViewStatDataForStop ratio = this.statistics.determineViewRatio(stop, LONG_ENOUGH_VIEW_COUNT);
        return !ratio.isNotViewedAtAll() && !ratio.isPartlyUnvisited();
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
        this.selectTour(tour);
    }

    private void selectTour(Tour tour) {
        IntellijMarkerFactory.runOnEdt(() -> {
            final DefaultMutableTreeNode node = TreeUtil.findNode(this.treeRoot,
                    (n) -> n.getUserObject() instanceof TourNode && ((TourNode) n.getUserObject()).tour == tour);
            if (node != null) {
                final TreePath path = new TreePath(node.getPath());
                this.tree.setSelectionPath(path);
                this.tree.scrollPathToVisible(path);
            }
        });
    }

    private void activateSelectedTour() {
        final Tour tour = this.getSelectedTopmostTour();
        if (tour == null) {
            return;
        }
        this.activateTour(tour);
        this.selectTour(tour);
    }

    private void activateTour(Tour tour) {
        if (tour == null || tour.equals(this.tours.getActiveTour())) {
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

    private void showSelectedStopDiff() {
        final Stop stop = this.getSelectedStop();
        if (stop != null) {
            StopDiffViewer.show(this.project, stop);
        }
    }

    private Stop getSelectedStop() {
        final DefaultMutableTreeNode node = this.getSelectedNode();
        if (node != null && node.getUserObject() instanceof StopNode) {
            return ((StopNode) node.getUserObject()).stop;
        }
        return null;
    }

    private void toggleChecked(Stop stop) {
        if (stop == null) {
            return;
        }
        this.statistics.toggleExplicitlyCheckedMark(Collections.singletonList(stop));
        this.rebuildTree();
    }

    private void openContainingFolder(Stop stop) {
        if (stop == null) {
            return;
        }
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

    /**
     * Jumps to the previous (direction -1) or next (direction 1) relevant stop.
     */
    public void navigate(int direction) {
        if (!this.checkToursAvailable()) {
            return;
        }
        final List<Stop> stops = this.flattenRelevantStops();
        if (stops.isEmpty()) {
            IntellijNotifications.info(this.project, "There are no relevant stops in the tours.");
            return;
        }
        final int index = this.currentStop == null ? -1 : stops.indexOf(this.currentStop);
        final int target = index < 0 && direction < 0 ? stops.size() - 1 : index + direction;
        if (target < 0 || target >= stops.size()) {
            IntellijNotifications.info(this.project, direction > 0
                    ? "This is the last relevant stop." : "This is the first relevant stop.");
            return;
        }
        this.jumpToStop(stops.get(target));
    }

    /**
     * Jumps to the next relevant stop that has not been visited yet (wrapping around at the end), like
     * the Eclipse "Jump to next unvisited review stop" command. Changing to a new tour or wrapping
     * around is reported with a notification.
     */
    public void jumpToNextUnvisitedStop() {
        if (!this.checkToursAvailable()) {
            return;
        }
        final Stop next = this.statistics.getNextUnvisitedStop(this.tours, this.currentStop, new INextStopCallback() {
            @Override
            public void newTourStarted(Tour tour) {
                IntellijNotifications.info(ReviewToursPanel.this.project,
                        "Start of a new review tour: " + firstLine(tour.getDescription()));
            }

            @Override
            public void wrappedAround() {
                IntellijNotifications.info(ReviewToursPanel.this.project,
                        "The end of the last tour has been reached, continuing at the beginning.");
            }
        });
        if (next == null) {
            IntellijNotifications.info(this.project, "There are no relevant stops left that have not been visited.");
            return;
        }
        this.jumpToStop(next);
    }

    /**
     * Selects the stop that is nearest to the given file position in the tree and activates its tour
     * (the counterpart of the Eclipse "Show in Review content" command).
     */
    public void showNearestStop(File file, int line) {
        if (!this.checkToursAvailable()) {
            return;
        }
        final Pair<Tour, Stop> nearest = this.tours.findNearestStop(file, line);
        if (nearest == null || nearest.getSecond() == null) {
            IntellijNotifications.info(this.project, "The file " + file.getName() + " is not part of the review tours.");
            return;
        }
        this.currentStop = nearest.getSecond();
        if (!nearest.getFirst().equals(this.tours.getActiveTour())) {
            this.activateTour(nearest.getFirst());
        } else {
            this.rebuildTree();
        }
    }

    private boolean checkToursAvailable() {
        if (this.tours == null) {
            IntellijNotifications.info(this.project,
                    "No review tours yet. Select a ticket and use \"Create Tours\" or \"Start Review\".");
            return false;
        }
        return true;
    }

    /**
     * Opens the file of the given stop at the start of the changed line range. Activates the tour
     * containing the stop and selects the stop in the tree. Like in Eclipse, the changed code is not
     * selected (it is highlighted by the stop marker), so that typing does not replace it and new
     * remarks are not prefilled with it.
     */
    private void jumpToStop(Stop stop) {
        this.currentStop = stop;
        final Tour tour = this.tours.getTopmostTourWith(stop);
        if (tour != null && !tour.equals(this.tours.getActiveTour())) {
            this.activateTour(tour);
        } else {
            this.rebuildTree();
        }
        final File file = stop.getAbsoluteFile();
        final boolean detailed = stop.isDetailedFragmentKnown();
        final int fromLine = detailed ? stop.getMostRecentFragment().getFrom().getLine() : 1;
        IntellijMarkerFactory.runOnEdt(() -> {
            final VirtualFile vf = IntellijFileResolver.findByAbsoluteFile(file);
            if (vf == null) {
                IntellijNotifications.warn(this.project,
                        "The file " + file + " does not exist in the working copy (anymore).");
                return;
            }
            final OpenFileDescriptor descriptor =
                    new OpenFileDescriptor(this.project, vf, Math.max(0, fromLine - 1), 0);
            final Editor editor = FileEditorManager.getInstance(this.project).openTextEditor(descriptor, true);
            if (editor != null) {
                editor.getScrollingModel().scrollToCaret(ScrollType.CENTER_UP);
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
        IntellijMarkerFactory.runOnEdt(() -> ApplicationManager.getApplication().runReadAction(this::doRenderStopMarkers));
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
                final VirtualFile vf = IntellijFileResolver.findByAbsoluteFile(stop.getAbsoluteFile());
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

    private String visitedLabel(Stop stop) {
        final ViewStatDataForStop ratio = this.statistics.determineViewRatio(stop, LONG_ENOUGH_VIEW_COUNT);
        if (ratio.isNotViewedAtAll()) {
            return "unvisited";
        } else if (ratio.isPartlyUnvisited()) {
            return "partly visited";
        } else {
            return "visited";
        }
    }

    private Icon stopIcon(Stop stop) {
        if (this.isChecked(stop)) {
            return AllIcons.Actions.Checked;
        }
        final ViewStatDataForStop ratio = this.statistics.determineViewRatio(stop, LONG_ENOUGH_VIEW_COUNT);
        if (ratio.isNotViewedAtAll()) {
            return this.isIrrelevant(stop) ? AllIcons.General.InspectionsTrafficOff : AllIcons.Nodes.EmptyNode;
        } else if (ratio.isPartlyUnvisited()) {
            return AllIcons.General.InspectionsPause;
        } else {
            return AllIcons.General.InspectionsOK;
        }
    }

    /**
     * Renderer for the tree node labels.
     */
    private final class CellRenderer extends ColoredTreeCellRenderer {
        private static final long serialVersionUID = 1L;

        @Override
        public void customizeCellRenderer(JTree tree, Object value, boolean selected, boolean expanded,
                boolean leaf, int row, boolean hasFocus) {
            this.setToolTipText(null);
            if (!(value instanceof DefaultMutableTreeNode)) {
                return;
            }
            final Object userObject = ((DefaultMutableTreeNode) value).getUserObject();
            final ToursInReview t = ReviewToursPanel.this.tours;
            if (userObject instanceof TourNode) {
                final Tour tour = ((TourNode) userObject).tour;
                final boolean active = t != null && tour.equals(t.getActiveTour());
                this.setIcon(active ? AllIcons.Actions.Execute : AllIcons.Nodes.Folder);
                this.append(firstLine(tour.getDescription()),
                        active ? ACTIVE_TOUR_ATTRIBUTES : SimpleTextAttributes.REGULAR_ATTRIBUTES);
                this.append("  " + tour.getStops().size() + " stops"
                        + (active ? ", active" : ""), SimpleTextAttributes.GRAYED_ATTRIBUTES);
                this.setToolTipText(tour.getClassificationFormatted() + tour.getDescription());
            } else if (userObject instanceof StopNode) {
                final Stop stop = ((StopNode) userObject).stop;
                final boolean irrelevant = ReviewToursPanel.this.isIrrelevant(stop);
                final boolean current = stop == ReviewToursPanel.this.currentStop;
                this.setIcon(ReviewToursPanel.this.stopIcon(stop));
                SimpleTextAttributes main = irrelevant
                        ? SimpleTextAttributes.GRAYED_ATTRIBUTES : SimpleTextAttributes.REGULAR_ATTRIBUTES;
                if (current) {
                    main = main.derive(SimpleTextAttributes.STYLE_BOLD, null, null, null);
                }
                this.append(stopLabel(stop), main);
                final String classification = stop.getClassificationFormatted().trim();
                if (!classification.isEmpty()) {
                    this.append("  [" + classification + "]", SimpleTextAttributes.GRAYED_ATTRIBUTES);
                }
                final StringBuilder state = new StringBuilder("  ");
                state.append(ReviewToursPanel.this.isChecked(stop)
                        ? "checked" : ReviewToursPanel.this.visitedLabel(stop));
                if (irrelevant) {
                    state.append(", irrelevant");
                }
                this.append(state.toString(), SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES);
                this.setToolTipText(stop.getAbsoluteFile().getPath());
            }
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
