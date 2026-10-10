package de.setsoftware.reviewtool.intellij;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.datatransfer.StringSelection;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.JComboBox;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.SwingConstants;
import javax.swing.JTree;
import javax.swing.ListSelectionModel;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.tree.DefaultTreeModel;

import com.intellij.icons.AllIcons;
import com.intellij.ide.BrowserUtil;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.vcs.FileStatus;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.ColoredTreeCellRenderer;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.JBSplitter;
import com.intellij.ui.JBColor;
import com.intellij.ui.SimpleColoredComponent;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.table.JBTable;
import com.intellij.ui.treeStructure.Tree;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.tree.TreeUtil;

import de.setsoftware.reviewtool.base.Logger;
import de.setsoftware.reviewtool.changesources.git.GitCommitInfo;
import de.setsoftware.reviewtool.model.EndTransition;
import de.setsoftware.reviewtool.model.ITicketData;
import de.setsoftware.reviewtool.model.TicketInfo;
import de.setsoftware.reviewtool.model.api.IChange;
import de.setsoftware.reviewtool.model.api.FileChangeType;
import de.setsoftware.reviewtool.model.api.IChangeData;
import de.setsoftware.reviewtool.model.api.ICommit;
import de.setsoftware.reviewtool.model.api.IRevisionedFile;
import de.setsoftware.reviewtool.model.api.PositionReference;
import de.setsoftware.reviewtool.model.changestructure.Stop;
import de.setsoftware.reviewtool.model.changestructure.ToursInReview;
import de.setsoftware.reviewtool.model.remarks.DummyMarker;
import de.setsoftware.reviewtool.model.remarks.FileLinePosition;
import de.setsoftware.reviewtool.model.remarks.FilePosition;
import de.setsoftware.reviewtool.model.remarks.GlobalPosition;
import de.setsoftware.reviewtool.model.remarks.Position;
import de.setsoftware.reviewtool.model.remarks.ReviewData;
import de.setsoftware.reviewtool.model.remarks.ReviewRemark;
import de.setsoftware.reviewtool.ticketconnectors.youtrack.YouTrackConnector;

/**
 * The content of the CoRT tool window: a list of tickets, the commits/files, review tours, review
 * remarks and the change summary of the selected ticket.
 *
 * <p>The mode combo box chooses between reviewing and fixing (like the Eclipse "Start review" and
 * "Start fixing" commands); the toolbar actions (start, end, save) work on the ticket whose details
 * are currently shown. Changes to the review remarks that have not been saved to the ticket system
 * yet are marked in the "Remarks" tab and the user is asked before they would be lost.
 */
public class ReviewToolPanel extends JPanel implements Disposable {

    private static final long serialVersionUID = 7882988395724508758L;

    private static final String MODE_KEY = "de.setsoftware.reviewtool.mode";
    /** The ticket whose review/fixing was started and not finished yet (e.g. paused), per project. */
    private static final String UNFINISHED_TICKET_KEY = "de.setsoftware.reviewtool.unfinishedTicket";
    /** Prefix for the backup of remarks that were changed but not saved to the ticket yet. */
    private static final String UNSAVED_REMARKS_PREFIX = "de.setsoftware.reviewtool.unsavedRemarks.";
    /** Suffix of the property with the remarks of the ticket the unsaved changes were based on. */
    private static final String UNSAVED_REMARKS_BASE_SUFFIX = ".base";
    private static final int TAB_CHANGES = 0;
    private static final int TAB_TOURS = 1;
    private static final int TAB_REMARKS = 2;
    private static final String NO_TICKET_KEY = "Selected commits";

    /**
     * User object for file nodes in the commit tree.
     */
    private static final class FileNode {
        private final String label;
        private final File localFile;
        private final FileChangeType type;

        FileNode(String label, File localFile, FileChangeType type) {
            this.label = label;
            this.localFile = localFile;
            this.type = type;
        }

        @Override
        public String toString() {
            return this.label;
        }
    }

    private final Project project;
    private final JComboBox<String> modeBox =
            new JComboBox<>(new String[] {ReviewToolService.FILTER_REVIEW, ReviewToolService.FILTER_FIXING});
    private final TicketTableModel ticketModel = new TicketTableModel();
    private final JBTable ticketTable = new JBTable(this.ticketModel);
    private final DefaultMutableTreeNode treeRoot = new DefaultMutableTreeNode("No ticket selected");
    private final DefaultTreeModel treeModel = new DefaultTreeModel(this.treeRoot);
    private final Tree commitTree = new Tree(this.treeModel);
    private final JBTextArea remarksArea = new JBTextArea();
    private final JBLabel unsavedLabel = new JBLabel();
    private final JTabbedPane rightTabs = new JTabbedPane();
    private final IntellijMarkerFactory markerFactory;
    private final ReviewToursPanel toursPanel;
    private final ReviewSummaryPanel summaryPanel;
    private final ReviewRemarksModel remarksModel;
    private final ReviewRemarksPanel remarksPanel;
    private final AtomicInteger detailsRequest = new AtomicInteger();
    private final AtomicInteger ticketListGeneration = new AtomicInteger();
    private final AtomicInteger remarkMarkerGeneration = new AtomicInteger();
    private final Timer reparseTimer;
    private final SimpleColoredComponent currentTicketLabel = new SimpleColoredComponent();
    private final JBScrollPane ticketListPane = new JBScrollPane(this.ticketTable);
    private final JBSplitter mainSplit = new JBSplitter(false, "de.setsoftware.reviewtool.mainSplitter", 0.45f);

    private volatile IChangeData lastLoadedChanges;
    private volatile String lastLoadedKey;
    /** The ticket whose remarks are shown in the remarks editor, or null. */
    private String currentTicketKey;
    /** Information about the ticket whose details are shown (null while loading or without ticket). */
    private TicketInfo currentTicketInfo;
    /** The ticket whose review or fixing has been started and not ended yet, or null. */
    private String workingOnKey;
    private int currentRound;
    private int lastOpenRemarkCount;
    /** False while the remarks of the shown ticket are loading or could not be loaded. */
    private boolean remarksLoaded = true;
    /** True if the remarks/information of the shown ticket could not be loaded. */
    private boolean ticketLoadFailed;
    /** The ticket (or {@link #NO_TICKET_KEY}) the tours in the "Tours" tab belong to, or null. */
    private String toursKey;
    /** The tours of the working ticket, put aside while another ticket is shown (or null). */
    private ToursInReview parkedWorkingTours;
    /** The choices made when the shown tours were created (tour structure, irrelevant classifications). */
    private IntellijCreateToursUi toursChoices;
    /** The choices made when the parked tours were created. */
    private IntellijCreateToursUi parkedToursChoices;
    /**
     * The ticket the view statistics (viewed lines, checked stops) belong to. The saved progress is
     * only restored if they do not belong to the working ticket already.
     */
    private String statisticsKey;
    /** Saves the review progress shortly after it changed (not on every scrolled line). */
    private final Timer progressSaveTimer;
    private boolean continueReviewOffered;
    /** The tickets for which restoring unsaved remarks has already been offered in this session. */
    private final Set<String> unsavedRemarksOffered = new LinkedHashSet<>();
    private String savedRemarks = "";
    private boolean remarksDirty;
    private boolean updatingRemarksText;
    private boolean changingSelection;
    private boolean remarkMarkersShown;

    public ReviewToolPanel(Project project) {
        super(new BorderLayout());
        this.project = project;
        this.markerFactory = new IntellijMarkerFactory(project);
        this.toursPanel = new ReviewToursPanel(project, this.markerFactory);
        this.summaryPanel = new ReviewSummaryPanel(project);
        this.remarksModel = new ReviewRemarksModel(
                () -> this.remarksArea.getText(),
                (text) -> this.setRemarksTextFromModel(text));
        this.remarksPanel = new ReviewRemarksPanel(project, this.remarksModel);
        this.toursPanel.setAllStopsVisitedListener(this::allStopsVisited);
        this.toursPanel.setAddRemarkListener(this::addRemarkForStop);
        this.toursPanel.setProgressListener(this::progressChanged);
        this.progressSaveTimer = new Timer(3000, (e) -> this.saveProgress(false));
        this.progressSaveTimer.setRepeats(false);
        this.remarksModel.addListener(() -> {
            if (this.remarkMarkersShown) {
                this.renderRemarkMarkers();
            }
            IntellijMarkerFactory.runOnEdt(() -> {
                this.updateRemarksTabTitle();
                this.checkAllRemarksProcessed();
            });
        });
        // re-parse the remarks shortly after the user stopped typing, so the tree and markers follow
        this.reparseTimer = new Timer(700, (e) -> this.remarksModel.reload());
        this.reparseTimer.setRepeats(false);
        final String savedMode = PropertiesComponent.getInstance(project).getValue(MODE_KEY, ReviewToolService.FILTER_REVIEW);
        this.modeBox.setSelectedItem(savedMode);
        this.buildUi();
        if (!ReviewToolSettings.getInstance(project).getState().youtrackUrl.isEmpty()) {
            // show the tickets right away when the tool window is opened
            ApplicationManager.getApplication().invokeLater(this::refreshTickets);
        }
        ApplicationManager.getApplication().invokeLater(this::offerToContinueUnfinishedReview);
    }

    private void buildUi() {
        final JPanel north = new JPanel(new BorderLayout());
        final JPanel modePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0));
        modePanel.add(new JBLabel("Mode:"));
        this.modeBox.setToolTipText("Review: tickets ready for review. Fixing: tickets with remarks to fix.");
        this.modeBox.addActionListener((e) -> this.modeChanged());
        modePanel.add(this.modeBox);
        north.add(modePanel, BorderLayout.WEST);
        final ActionToolbar toolbar = ActionManager.getInstance().createActionToolbar(
                "CoRT.Main", this.createToolbarActions(), true);
        toolbar.setTargetComponent(this);
        north.add(toolbar.getComponent(), BorderLayout.CENTER);
        this.add(north, BorderLayout.NORTH);

        this.ticketTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        this.ticketTable.setAutoCreateRowSorter(true);
        final int[] columnWidths = {75, 220, 110, 110, 120, 90, 55};
        for (int i = 0; i < columnWidths.length; i++) {
            this.ticketTable.getColumnModel().getColumn(i).setPreferredWidth(JBUI.scale(columnWidths[i]));
        }
        // the key and the state must stay readable when the list is narrow
        this.ticketTable.getColumnModel().getColumn(TicketTableModel.COLUMN_KEY).setMinWidth(JBUI.scale(65));
        this.ticketTable.getColumnModel().getColumn(TicketTableModel.COLUMN_STATE).setMinWidth(JBUI.scale(80));
        this.ticketTable.setDefaultRenderer(String.class, new CurrentTicketRenderer(SwingConstants.LEADING));
        this.ticketTable.setDefaultRenderer(Integer.class, new CurrentTicketRenderer(SwingConstants.TRAILING));
        this.ticketTable.getSelectionModel().addListSelectionListener((e) -> {
            if (!e.getValueIsAdjusting() && !this.changingSelection) {
                this.ticketSelectionChanged();
            }
        });
        this.updateTicketTableEmptyText();

        this.commitTree.setRootVisible(true);
        this.commitTree.setCellRenderer(new CommitTreeRenderer());
        this.commitTree.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    ReviewToolPanel.this.openSelectedFile();
                }
            }
        });
        this.commitTree.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER) {
                    ReviewToolPanel.this.openSelectedFile();
                }
            }
        });

        this.rightTabs.addTab("Changes", new JBScrollPane(this.commitTree));
        this.rightTabs.addTab("Tours", this.toursPanel);
        this.rightTabs.addTab("Remarks", this.createRemarksTab());
        this.rightTabs.addTab("Summary", this.summaryPanel);

        this.currentTicketLabel.setBorder(JBUI.Borders.empty(4, 8));
        this.currentTicketLabel.setIconTextGap(JBUI.scale(6));
        final JPanel rightPanel = new JPanel(new BorderLayout());
        rightPanel.add(this.currentTicketLabel, BorderLayout.NORTH);
        rightPanel.add(this.rightTabs, BorderLayout.CENTER);
        this.updateCurrentTicketLabel();

        // the ticket list gets less space than the details; it is hidden while working on a ticket
        this.mainSplit.setFirstComponent(this.ticketListPane);
        this.mainSplit.setSecondComponent(rightPanel);
        this.mainSplit.setHonorComponentsMinimumSize(false);
        this.add(this.mainSplit, BorderLayout.CENTER);
    }

    /**
     * Renders the ticket table cells; the row of the ticket whose details are shown is bold.
     */
    private final class CurrentTicketRenderer extends DefaultTableCellRenderer {
        private static final long serialVersionUID = 1L;

        CurrentTicketRenderer(int alignment) {
            this.setHorizontalAlignment(alignment);
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                boolean hasFocus, int row, int column) {
            final Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            final String key = ReviewToolPanel.this.ticketModel.getTicket(table.convertRowIndexToModel(row)).getId();
            c.setFont(table.getFont().deriveFont(
                    key.equals(ReviewToolPanel.this.currentTicketKey) ? Font.BOLD : Font.PLAIN));
            return c;
        }
    }

    /**
     * Shows which ticket the tabs belong to (and whether it is being reviewed or fixed) above the tabs,
     * because the ticket usually leaves the ticket list (filter) when its review or fixing is started.
     */
    private void updateCurrentTicketLabel() {
        final SimpleColoredComponent label = this.currentTicketLabel;
        label.clear();
        if (this.currentTicketKey == null) {
            label.setIcon(AllIcons.General.Information);
            label.append("No ticket selected - select a ticket in the list or review commits without a ticket",
                    SimpleTextAttributes.GRAYED_ATTRIBUTES);
            return;
        }
        if (NO_TICKET_KEY.equals(this.currentTicketKey)) {
            label.setIcon(AllIcons.Vcs.History);
            label.append("Selected commits", SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES);
            label.append("  reviewed without a ticket - the remarks are not stored",
                    SimpleTextAttributes.GRAYED_ATTRIBUTES);
            return;
        }
        final boolean working = this.currentTicketKey.equals(this.workingOnKey);
        label.setIcon(working
                ? (this.isFixingMode() ? AllIcons.Actions.Edit : AllIcons.Actions.Preview)
                : AllIcons.Nodes.Tag);
        label.append(this.currentTicketKey, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES);
        final TicketInfo info = this.currentTicketInfo;
        if (info == null || !info.getId().equals(this.currentTicketKey)) {
            if (this.ticketLoadFailed) {
                label.append("  could not be loaded from YouTrack - see the notification (Retry)",
                        SimpleTextAttributes.ERROR_ATTRIBUTES);
            } else {
                label.append("  loading...", SimpleTextAttributes.GRAYED_ATTRIBUTES);
            }
            return;
        }
        label.append("  " + info.getSummaryIncludingParent());
        final StringBuilder details = new StringBuilder();
        details.append("  \u00B7  ").append(info.getState());
        if (working) {
            details.append("  \u00B7  ").append(this.isFixingMode() ? "fixing" : "reviewing")
                    .append(" (review round ").append(this.currentRound).append(')');
        }
        label.append(details.toString(), working
                ? SimpleTextAttributes.REGULAR_ITALIC_ATTRIBUTES : SimpleTextAttributes.GRAYED_ATTRIBUTES);
    }

    /**
     * The mode cannot be changed while a review or fixing is running (e.g. by an accidental arrow key in
     * the combo box), because the actions (end review / end fixing) depend on it.
     */
    private void updateModeBoxEnabled() {
        final boolean working = this.workingOnKey != null;
        this.modeBox.setEnabled(!working);
        this.modeBox.setToolTipText(working
                ? "The mode cannot be changed while the " + (this.isFixingMode() ? "fixing" : "review") + " of "
                    + this.workingOnKey + " is running. End or pause it first."
                : "Review: tickets ready for review. Fixing: tickets with remarks to fix.");
    }

    boolean isTicketListVisible() {
        return this.ticketListPane.isVisible();
    }

    /**
     * Shows or hides the ticket list (it is hidden while a review or fixing is running, so that the
     * tours and remarks get the space).
     */
    void setTicketListVisible(boolean visible) {
        this.ticketListPane.setVisible(visible);
        this.mainSplit.revalidate();
        this.mainSplit.repaint();
    }

    /**
     * Adds the ticket whose details are shown to the ticket list if it is not contained (e.g. because
     * starting the review changed its state, so it does not match the filter anymore).
     */
    private void ensureCurrentTicketListed() {
        final TicketInfo info = this.currentTicketInfo;
        if (!this.hasTicket() || info == null || !info.getId().equals(this.currentTicketKey)
                || this.ticketModel.indexOf(info.getId()) >= 0) {
            return;
        }
        this.ticketModel.addTicket(info);
        this.selectTicketSilently(info.getId());
        this.loadTicketHistories(null, Collections.singletonList(info), this.ticketListGeneration.get());
    }

    /**
     * Adds the ticket whose review/fixing has been paused to the ticket list (if it is not listed
     * anyway), so that it can be continued.
     */
    private void addUnfinishedTicket(YouTrackConnector connector, int generation) {
        // stored as "mode:key", the ticket is only shown in the list of its mode
        final String stored = PropertiesComponent.getInstance(this.project).getValue(UNFINISHED_TICKET_KEY, "");
        final String prefix = this.modeBox.getSelectedItem() + ":";
        if (!stored.startsWith(prefix)) {
            return;
        }
        final String key = stored.substring(prefix.length());
        if (key.isEmpty() || this.ticketModel.indexOf(key) >= 0) {
            return;
        }
        new Task.Backgroundable(this.project, "Loading the unfinished ticket " + key, true) {
            @Override
            public void run(ProgressIndicator indicator) {
                try {
                    final ITicketData ticket = connector.loadTicket(key);
                    if (ticket == null) {
                        return;
                    }
                    final TicketInfo info = connector.addHistory(ticket.getTicketInfo());
                    ApplicationManager.getApplication().invokeLater(() -> {
                        if (generation == ReviewToolPanel.this.ticketListGeneration.get()
                                && ReviewToolPanel.this.ticketModel.indexOf(key) < 0) {
                            ReviewToolPanel.this.ticketModel.addTicket(info);
                        }
                    });
                } catch (final RuntimeException e) {
                    Logger.debug("could not load the unfinished ticket " + key + ": " + e);
                }
            }
        }.queue();
    }

    /**
     * Reloads the information about the ticket whose details are shown (e.g. its state after the end
     * of the review) and updates the label and its row in the ticket list.
     */
    private void refreshCurrentTicketInfo() {
        if (!this.hasTicket()) {
            return;
        }
        final String key = this.currentTicketKey;
        new Task.Backgroundable(this.project, "Updating " + key, true) {
            @Override
            public void run(ProgressIndicator indicator) {
                try {
                    final YouTrackConnector connector = ReviewToolPanel.this.getService().createTicketConnector();
                    final ITicketData ticket = connector.loadTicket(key);
                    if (ticket == null) {
                        return;
                    }
                    final TicketInfo info = connector.addHistory(ticket.getTicketInfo());
                    ApplicationManager.getApplication().invokeLater(() -> {
                        if (key.equals(ReviewToolPanel.this.currentTicketKey)) {
                            ReviewToolPanel.this.currentTicketInfo = info;
                            ReviewToolPanel.this.updateCurrentTicketLabel();
                        }
                        ReviewToolPanel.this.ticketModel.updateTicket(info);
                    });
                } catch (final RuntimeException e) {
                    Logger.debug("could not update the information about " + key + ": " + e);
                }
            }
        }.queue();
    }

    /**
     * Updates the row of the given ticket in the list with the freshly loaded information (e.g. its
     * new state), keeping the information from its history that has already been loaded.
     */
    private void updateRowKeepingHistory(TicketInfo info) {
        if (info == null) {
            return;
        }
        final int index = this.ticketModel.indexOf(info.getId());
        if (index < 0) {
            return;
        }
        final TicketInfo old = this.ticketModel.getTicket(index);
        this.ticketModel.updateTicket(
                info.withHistory(old.getPreviousState(), old.getReviewers(), old.getWaitingSince()));
    }

    /**
     * Called when the settings have been changed: reloads the ticket list.
     */
    void settingsChanged() {
        this.updateTicketTableEmptyText();
        if (!ReviewToolSettings.getInstance(this.project).getState().youtrackUrl.isEmpty()) {
            this.refreshTickets();
        }
    }

    private DefaultActionGroup createToolbarActions() {
        final DefaultActionGroup group = new DefaultActionGroup();
        group.add(PanelActions.action("Refresh Tickets", AllIcons.Actions.Refresh, () -> true, this::refreshTickets));
        group.add(PanelActions.toggle("Show Ticket List (Hidden While Reviewing/Fixing)", AllIcons.Actions.ListFiles,
                this::isTicketListVisible, this::setTicketListVisible));
        group.addSeparator();
        group.add(PanelActions.withTextInToolbar(PanelActions.action(
                () -> this.isFixingMode() ? "Start Fixing" : "Start Review",
                AllIcons.Actions.Execute,
                () -> this.getActiveTicketKey() != null && !this.getActiveTicketKey().equals(this.workingOnKey),
                this::startWorkOnSelectedTicket)));
        group.add(PanelActions.withTextInToolbar(PanelActions.action(
                () -> this.isFixingMode() ? "End Fixing..." : "End Review...",
                AllIcons.Actions.Commit,
                this::hasTicket,
                this::endReviewOrFixing)));
        group.add(PanelActions.action("Save Remarks to Ticket", AllIcons.Actions.MenuSaveall,
                () -> this.hasTicket() && this.remarksDirty, this::saveRemarks));
        group.add(PanelActions.action("Reload Remarks and Changes of the Ticket", AllIcons.Actions.Rerun,
                this::hasTicket, this::reloadCurrentTicket));
        group.addSeparator();
        group.add(PanelActions.action("Open Ticket in YouTrack", AllIcons.General.Web,
                () -> this.getActiveTicketKey() != null, this::openSelectedTicketInBrowser));
        group.add(PanelActions.action("Copy Ticket ID", AllIcons.Actions.Copy,
                () -> this.getActiveTicketKey() != null, this::copySelectedTicketId));
        group.add(PanelActions.action("Open Ticket by ID...", AllIcons.Actions.Find, () -> true, this::openTicketById));
        group.addSeparator();
        group.add(PanelActions.action("Review Commits without Ticket...", AllIcons.Vcs.History, () -> true,
                this::reviewSelectedCommits));
        group.add(PanelActions.action("Create Tours", AllIcons.Actions.ShowAsTree,
                () -> this.lastLoadedChanges != null, () -> this.createToursForLoadedChanges(false)));
        group.addSeparator();
        group.add(PanelActions.action("Add Remark at Cursor...", AllIcons.General.Add, () -> true,
                this::addRemarkAtCursor));
        group.add(PanelActions.action("Show Remark Markers", AllIcons.General.InspectionsEye, () -> true,
                this::showRemarkMarkers));
        group.add(PanelActions.action("Clear Markers", AllIcons.Actions.GC, () -> true, this::clearAllMarkers));
        group.addSeparator();
        final DefaultActionGroup more = new DefaultActionGroup("More", true);
        more.getTemplatePresentation().setIcon(AllIcons.Actions.More);
        more.add(PanelActions.action("Clear Commit Cache", AllIcons.Actions.GC, () -> true, this::clearCommitCache));
        more.add(PanelActions.action("Enable Verbose Logging", AllIcons.Actions.StartDebugger, () -> true, () -> {
            IntellijLogger.enableVerboseLogging();
            IntellijNotifications.info(this.project,
                    "Verbose logging of CoRT is enabled until the IDE is restarted (see Help | Show Log).");
        }));
        group.add(more);
        group.add(PanelActions.action("Settings", AllIcons.General.Settings, () -> true,
                () -> ShowSettingsUtil.getInstance().showSettingsDialog(this.project, ReviewToolConfigurable.class)));
        return group;
    }

    private JPanel createRemarksTab() {
        this.remarksArea.setLineWrap(true);
        this.remarksArea.setWrapStyleWord(true);
        this.remarksArea.getEmptyText().setText("The review remarks of the selected ticket are shown here");
        this.remarksArea.getDocument().addDocumentListener(new DocumentAdapter() {
            @Override
            protected void textChanged(DocumentEvent e) {
                if (!ReviewToolPanel.this.updatingRemarksText) {
                    ReviewToolPanel.this.setRemarksDirty(true);
                    ReviewToolPanel.this.reparseTimer.restart();
                }
            }
        });
        this.unsavedLabel.setForeground(JBColor.ORANGE);
        this.unsavedLabel.setBorder(JBUI.Borders.empty(2, 4));

        final JPanel textPanel = new JPanel(new BorderLayout());
        final JBLabel textTitle = new JBLabel("Review remarks text (as stored in the ticket):");
        textTitle.setBorder(JBUI.Borders.empty(2, 4));
        textPanel.add(textTitle, BorderLayout.NORTH);
        textPanel.add(new JBScrollPane(this.remarksArea), BorderLayout.CENTER);
        textPanel.add(this.unsavedLabel, BorderLayout.SOUTH);

        // side by side, because the tool window is usually wide and flat at the bottom of the IDE
        final JBSplitter split = new JBSplitter(false, "de.setsoftware.reviewtool.remarksSplitter", 0.6f);
        split.setFirstComponent(this.remarksPanel);
        split.setSecondComponent(textPanel);
        split.setHonorComponentsMinimumSize(false);
        final JPanel panel = new JPanel(new BorderLayout());
        panel.add(split, BorderLayout.CENTER);
        return panel;
    }

    /**
     * Stops the timers and removes the markers when the tool window content is disposed.
     */
    @Override
    public void dispose() {
        this.reparseTimer.stop();
        this.progressSaveTimer.stop();
        this.saveProgress(true);
        this.toursPanel.dispose();
        this.markerFactory.clearReviewMarkers();
        this.markerFactory.clearStopMarkers();
        final ReviewToolService service = this.getService();
        if (service.getReviewPanel() == this) {
            service.setReviewPanel(null);
        }
    }

    private ReviewToolService getService() {
        return ReviewToolService.getInstance(this.project);
    }

    /**
     * Returns true iff the panel is in fixing mode (otherwise it is in review mode).
     */
    public boolean isFixingMode() {
        return ReviewToolService.FILTER_FIXING.equals(this.modeBox.getSelectedItem());
    }

    /**
     * Returns true iff the details of a ticket (not of a ticket-less commit selection) are shown.
     */
    private boolean hasTicket() {
        return this.currentTicketKey != null && !NO_TICKET_KEY.equals(this.currentTicketKey);
    }

    private void reloadCurrentTicket() {
        if (!this.hasTicket() || !this.confirmDiscardUnsavedRemarks()) {
            return;
        }
        this.loadDetails(this.currentTicketKey, null);
    }

    private String getSelectedTicketKey() {
        final int viewRow = this.ticketTable.getSelectedRow();
        if (viewRow < 0) {
            return null;
        }
        return this.ticketModel.getTicket(this.ticketTable.convertRowIndexToModel(viewRow)).getId();
    }

    /**
     * The ticket the ticket actions (start, open, copy) refer to: the selected ticket, or the ticket
     * whose details are shown (e.g. one opened by its ID that is not in the list).
     */
    private String getActiveTicketKey() {
        final String selected = this.getSelectedTicketKey();
        if (selected != null) {
            return selected;
        }
        return this.hasTicket() ? this.currentTicketKey : null;
    }

    /**
     * Asks for a ticket ID and shows the ticket, also if it is not contained in the list of the
     * current filter (the counterpart of entering an ID in the Eclipse ticket selection dialog).
     */
    private void openTicketById() {
        final String input = Messages.showInputDialog(this.project,
                "Ticket ID (e.g. PROJ-123):", "Open Ticket by ID", null, null,
                new com.intellij.openapi.ui.InputValidator() {
                    @Override
                    public boolean checkInput(String inputString) {
                        return !inputString.trim().isEmpty() && !inputString.trim().contains(" ");
                    }

                    @Override
                    public boolean canClose(String inputString) {
                        return this.checkInput(inputString);
                    }
                });
        if (input == null) {
            return;
        }
        final String key = input.trim();
        if (key.equals(this.currentTicketKey)) {
            this.reloadCurrentTicket();
            return;
        }
        if (!this.confirmDiscardUnsavedRemarks()) {
            return;
        }
        this.selectTicketSilently(key);
        this.loadDetails(key, null);
    }

    private void clearCommitCache() {
        new Task.Backgroundable(this.project, "Clearing the CoRT commit cache", false) {
            @Override
            public void run(ProgressIndicator indicator) {
                try {
                    ReviewToolPanel.this.getService().getChangeSource().clearCaches();
                    IntellijNotifications.info(ReviewToolPanel.this.project,
                            "The commit cache has been cleared, the commits are determined anew on the next load.");
                } catch (final RuntimeException e) {
                    ReviewToolPanel.this.showError("Could not clear the commit cache", e);
                }
            }
        }.queue();
    }

    private void selectTicketSilently(String key) {
        this.changingSelection = true;
        try {
            final int modelRow = key == null ? -1 : this.ticketModel.indexOf(key);
            if (modelRow < 0) {
                this.ticketTable.clearSelection();
            } else {
                final int viewRow = this.ticketTable.convertRowIndexToView(modelRow);
                this.ticketTable.getSelectionModel().setSelectionInterval(viewRow, viewRow);
            }
        } finally {
            this.changingSelection = false;
        }
    }

    private void showError(String message, Throwable exception) {
        IntellijNotifications.error(this.project, message, exception);
    }

    private void showError(String message, Throwable exception, Runnable retry) {
        IntellijNotifications.error(this.project, message, exception, retry);
    }

    /**
     * Returns true iff the remarks of the shown ticket could be loaded. If not (e.g. YouTrack was not
     * reachable), the remarks must not be changed, because saving them would overwrite the remarks in
     * the ticket. In this case, a warning is shown.
     */
    private boolean checkRemarksLoaded() {
        if (this.remarksLoaded) {
            return true;
        }
        IntellijNotifications.warn(this.project, "The review remarks of " + this.currentTicketKey
                + " have not been loaded (yet), so they cannot be changed - saving would overwrite the remarks in"
                + " the ticket. Reload the ticket when YouTrack is reachable again.");
        return false;
    }

    private void updateTicketTableEmptyText() {
        final boolean configured = !ReviewToolSettings.getInstance(this.project).getState().youtrackUrl.isEmpty();
        this.ticketTable.getEmptyText().clear();
        if (configured) {
            this.ticketTable.getEmptyText().setText("No tickets loaded");
            this.ticketTable.getEmptyText().appendSecondaryText("Refresh tickets",
                    SimpleTextAttributes.LINK_ATTRIBUTES, (e) -> this.refreshTickets());
        } else {
            this.ticketTable.getEmptyText().setText("YouTrack is not configured yet");
            this.ticketTable.getEmptyText().appendSecondaryText("Open settings",
                    SimpleTextAttributes.LINK_ATTRIBUTES, (e) -> {
                        ShowSettingsUtil.getInstance().showSettingsDialog(this.project, ReviewToolConfigurable.class);
                        this.updateTicketTableEmptyText();
                    });
        }
        this.ticketTable.getEmptyText().appendLine("Or review commits without a ticket",
                SimpleTextAttributes.LINK_ATTRIBUTES, (e) -> this.reviewSelectedCommits());
    }

    private void modeChanged() {
        PropertiesComponent.getInstance(this.project).setValue(MODE_KEY, (String) this.modeBox.getSelectedItem());
        if (!ReviewToolSettings.getInstance(this.project).getState().youtrackUrl.isEmpty()) {
            this.refreshTickets();
        }
    }

    /**
     * Loads the tickets for the currently selected filter in the background.
     */
    public void refreshTickets() {
        final String filterName = (String) this.modeBox.getSelectedItem();
        this.updateTicketTableEmptyText();
        new Task.Backgroundable(this.project, "Loading tickets from YouTrack", true) {
            @Override
            public void run(ProgressIndicator indicator) {
                try {
                    final YouTrackConnector connector =
                            ReviewToolPanel.this.getService().createTicketConnector();
                    final List<TicketInfo> tickets = connector.getTicketsForFilter(filterName);
                    final int generation = ReviewToolPanel.this.ticketListGeneration.incrementAndGet();
                    ApplicationManager.getApplication().invokeLater(() -> {
                        final String selected = ReviewToolPanel.this.currentTicketKey;
                        ReviewToolPanel.this.ticketModel.setTickets(tickets);
                        // keep the ticket whose details are shown selected (and listed)
                        ReviewToolPanel.this.selectTicketSilently(selected);
                        ReviewToolPanel.this.loadTicketHistories(connector, tickets, generation);
                        ReviewToolPanel.this.ensureCurrentTicketListed();
                        ReviewToolPanel.this.addUnfinishedTicket(connector, generation);
                    });
                } catch (final RuntimeException e) {
                    ReviewToolPanel.this.showError("Could not load tickets from YouTrack", e,
                            ReviewToolPanel.this::refreshTickets);
                }
            }
        }.queue();
    }

    /**
     * Determines the previous reviewers, the previous state and the time since the last state change
     * of the listed tickets (like the Eclipse ticket selection dialog). This needs a request per
     * ticket, so it is done in the background after the list is shown, and the rows are updated as
     * the results arrive. The results are discarded when the list has been reloaded in the meantime.
     *
     * @param connectorOrNull The connector to use; if null, one is created in the background.
     */
    private void loadTicketHistories(YouTrackConnector connectorOrNull, List<TicketInfo> tickets, int generation) {
        if (tickets.isEmpty()) {
            return;
        }
        new Task.Backgroundable(this.project, "Loading the review history of the tickets", true) {
            @Override
            public void run(ProgressIndicator indicator) {
                final YouTrackConnector connector;
                try {
                    connector = connectorOrNull != null
                            ? connectorOrNull : ReviewToolPanel.this.getService().createTicketConnector();
                } catch (final RuntimeException e) {
                    Logger.debug("could not load the ticket histories: " + e);
                    return;
                }
                indicator.setIndeterminate(false);
                for (int i = 0; i < tickets.size(); i++) {
                    if (indicator.isCanceled()
                            || generation != ReviewToolPanel.this.ticketListGeneration.get()) {
                        return;
                    }
                    indicator.setFraction((double) i / tickets.size());
                    indicator.setText2(tickets.get(i).getId());
                    final TicketInfo withHistory;
                    try {
                        withHistory = connector.addHistory(tickets.get(i));
                    } catch (final RuntimeException e) {
                        // the history is additional information, the list is usable without it
                        Logger.warn("could not load the history of " + tickets.get(i).getId(), e);
                        continue;
                    }
                    ApplicationManager.getApplication().invokeLater(() -> {
                        if (generation == ReviewToolPanel.this.ticketListGeneration.get()) {
                            ReviewToolPanel.this.ticketModel.updateTicket(withHistory);
                        }
                    });
                }
            }
        }.queue();
    }

    private void ticketSelectionChanged() {
        final String key = this.getSelectedTicketKey();
        if (key == null || key.equals(this.currentTicketKey)) {
            return;
        }
        if (!this.confirmDiscardUnsavedRemarks()) {
            // stay on the ticket with the unsaved remarks
            this.selectTicketSilently(this.currentTicketKey);
            return;
        }
        this.loadDetails(key, null);
    }

    /**
     * Asks the user what to do with unsaved remark changes before they would be replaced. Returns
     * false if the current action shall be cancelled.
     */
    private boolean confirmDiscardUnsavedRemarks() {
        if (!this.remarksDirty || !this.hasTicket()) {
            return true;
        }
        final int answer = Messages.showYesNoCancelDialog(this.project,
                "The review remarks of " + this.currentTicketKey + " have changes that have not been saved to"
                + " the ticket yet. Save them now?",
                "Unsaved Review Remarks", "Save", "Discard", "Cancel", Messages.getWarningIcon());
        if (answer == Messages.CANCEL) {
            return false;
        }
        if (answer == Messages.YES) {
            this.saveRemarks();
        } else {
            this.deleteUnsavedRemarksBackup(this.currentTicketKey);
        }
        return true;
    }

    /**
     * Offers to restore remarks that were changed in an earlier session but not saved to the ticket.
     */
    private void offerUnsavedRemarks(String key, String loadedRemarks) {
        final PropertiesComponent properties = PropertiesComponent.getInstance(this.project);
        final String backup = properties.getValue(UNSAVED_REMARKS_PREFIX + key);
        if (backup == null) {
            return;
        }
        if (backup.equals(loadedRemarks)) {
            this.deleteUnsavedRemarksBackup(key);
            return;
        }
        if (!this.unsavedRemarksOffered.add(key)) {
            // already offered (e.g. when the ticket was selected before the review was started)
            return;
        }
        final String base = properties.getValue(UNSAVED_REMARKS_PREFIX + key + UNSAVED_REMARKS_BASE_SUFFIX);
        final boolean ticketChanged = base != null && !base.equals(loadedRemarks);
        IntellijNotifications.info(this.project, "There are changes to the review remarks of " + key
                + " that have not been saved to the ticket (e.g. because YouTrack was not reachable)."
                + (ticketChanged
                    ? " Caution: the remarks in the ticket have been changed since then. Restoring replaces them"
                        + " (until they are saved, reloading the ticket brings its remarks back)."
                    : ""),
                "Restore them", () -> {
                    if (key.equals(this.currentTicketKey) && this.remarksLoaded) {
                        this.setRemarksTextFromModel(backup);
                        this.remarksModel.reload();
                        this.rightTabs.setSelectedIndex(TAB_REMARKS);
                    }
                },
                "Discard them", () -> this.deleteUnsavedRemarksBackup(key));
    }

    private void deleteUnsavedRemarksBackup(String key) {
        final PropertiesComponent properties = PropertiesComponent.getInstance(this.project);
        properties.unsetValue(UNSAVED_REMARKS_PREFIX + key);
        properties.unsetValue(UNSAVED_REMARKS_PREFIX + key + UNSAVED_REMARKS_BASE_SUFFIX);
    }

    /**
     * Loads the remarks and changes of the given ticket in the background. If the user selects
     * another ticket in the meantime, the outdated results are discarded.
     *
     * @param afterChangesLoaded Called on the EDT after the changes have been loaded (may be null).
     */
    private void loadDetails(String key, Runnable afterChangesLoaded) {
        final int request = this.detailsRequest.incrementAndGet();
        this.currentTicketKey = key;
        this.currentTicketInfo = null;
        this.discardToursOfOtherTicket(key);
        this.updateCurrentTicketLabel();
        this.ticketTable.repaint();
        this.setRemarksText("");
        this.remarksArea.getEmptyText().setText("Loading review remarks of " + key + "...");
        this.remarksLoaded = false;
        this.ticketLoadFailed = false;
        this.remarksArea.setEditable(false);
        // the remarks tree and markers of the previous ticket must not be shown for the new one
        this.remarksModel.reload();
        this.treeModel.setRoot(new DefaultMutableTreeNode("Loading changes of " + key + "..."));
        new Task.Backgroundable(this.project, "Loading details for " + key, true) {
            @Override
            public void run(ProgressIndicator indicator) {
                ReviewToolPanel.this.loadRemarks(key, request);
                ReviewToolPanel.this.loadChanges(key, indicator, request, afterChangesLoaded);
            }
        }.queue();
    }

    private boolean isCurrentRequest(int request) {
        return request == this.detailsRequest.get();
    }

    private void loadRemarks(String key, int request) {
        try {
            final ITicketData ticket = this.getService().createTicketConnector().loadTicket(key);
            final String remarks = ticket == null ? "" : ticket.getReviewData();
            final TicketInfo info = ticket == null ? null : ticket.getTicketInfo();
            int round = 1;
            String reviewer = null;
            try {
                if (ticket != null) {
                    round = Math.max(1, ticket.getCurrentRound());
                    reviewer = ticket.getReviewerForRound(round);
                }
            } catch (final RuntimeException e) {
                // the remarks are still usable, they are just attributed to the local user / round 1
                Logger.warn("could not determine the review round of " + key, e);
            }
            final int finalRound = round;
            final String finalReviewer = reviewer;
            ApplicationManager.getApplication().invokeLater(() -> {
                if (!this.isCurrentRequest(request)) {
                    return;
                }
                this.remarksArea.getEmptyText().setText("No review remarks yet");
                this.remarksLoaded = true;
                this.remarksArea.setEditable(true);
                this.currentTicketInfo = info;
                this.currentRound = finalRound;
                this.updateCurrentTicketLabel();
                this.ensureCurrentTicketListed();
                this.updateRowKeepingHistory(info);
                this.remarksModel.setRoundInfo(finalRound, finalReviewer);
                this.setRemarksText(remarks);
                this.offerUnsavedRemarks(key, remarks);
                // show the remarks of the ticket in the editors right away
                this.remarkMarkersShown = true;
                this.remarksModel.reload();
            });
        } catch (final RuntimeException e) {
            ApplicationManager.getApplication().invokeLater(() -> {
                if (this.isCurrentRequest(request)) {
                    this.remarksArea.getEmptyText().setText("The review remarks of " + key
                            + " could not be loaded - use \"Reload Remarks and Changes of the Ticket\"");
                    this.ticketLoadFailed = true;
                    this.updateCurrentTicketLabel();
                }
            });
            this.showError("Could not load review remarks for " + key, e, () -> this.retryLoad(key));
        }
    }

    private void loadChanges(String key, ProgressIndicator indicator, int request, Runnable afterChangesLoaded) {
        try {
            final IChangeData changes = this.getService().getRepositoryChanges(
                    key, new ChangeSourceUiAdapter(this.project, indicator));
            final DefaultMutableTreeNode newRoot = this.buildCommitTree(key, changes);
            ApplicationManager.getApplication().invokeLater(() -> {
                if (!this.isCurrentRequest(request)) {
                    return;
                }
                this.lastLoadedChanges = changes;
                this.lastLoadedKey = key;
                this.treeModel.setRoot(newRoot);
                TreeUtil.expandAll(this.commitTree);
                if (changes.getMatchedCommits().isEmpty()) {
                    IntellijNotifications.warn(this.project, "No commits have been found for " + key
                            + ". Check the commit message pattern in the settings if this is unexpected.");
                }
                if (afterChangesLoaded != null) {
                    afterChangesLoaded.run();
                }
            });
        } catch (final ProcessCanceledException e) {
            throw e;
        } catch (final Exception e) {
            this.showError("Could not determine Git changes for " + key, e);
        }
    }

    private DefaultMutableTreeNode buildCommitTree(String key, IChangeData changes) {
        final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm");
        final DefaultMutableTreeNode root = new DefaultMutableTreeNode(
                key + " (" + changes.getMatchedCommits().size() + " commits)");
        for (final ICommit commit : changes.getMatchedCommits()) {
            final String firstLine = commit.getMessage().split("\n")[0];
            final DefaultMutableTreeNode commitNode = new DefaultMutableTreeNode(
                    dateFormat.format(commit.getTime()) + "  " + firstLine);
            final Set<String> seenPaths = new LinkedHashSet<>();
            for (final IChange change : commit.getChanges()) {
                final IRevisionedFile file =
                        change.getType() == FileChangeType.DELETED ? change.getFrom() : change.getTo();
                final String path = file.getPath();
                if (seenPaths.add(path)) {
                    commitNode.add(new DefaultMutableTreeNode(new FileNode(
                            path,
                            file.toLocalPath(change.getWorkingCopy()),
                            change.getType())));
                }
            }
            root.add(commitNode);
        }
        return root;
    }

    private void openSelectedFile() {
        final Object node = this.commitTree.getLastSelectedPathComponent();
        if (!(node instanceof DefaultMutableTreeNode)) {
            return;
        }
        final Object userObject = ((DefaultMutableTreeNode) node).getUserObject();
        if (!(userObject instanceof FileNode)) {
            return;
        }
        final File file = ((FileNode) userObject).localFile;
        final VirtualFile virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file);
        if (virtualFile == null) {
            IntellijNotifications.warn(this.project, "The file " + file + " does not exist in the working copy (anymore).");
            return;
        }
        FileEditorManager.getInstance(this.project).openFile(virtualFile, true);
    }

    private void startWorkOnSelectedTicket() {
        final String key = this.getActiveTicketKey();
        if (key == null) {
            return;
        }
        if (this.workingOnKey != null && !this.workingOnKey.equals(key)) {
            // only one review/fixing at a time: the tours, the progress and the markers belong to it
            this.offerToShowWorkingTicket("End (or pause) it before starting to work on " + key + ".");
            return;
        }
        final boolean review = !this.isFixingMode();
        // the ticket is reloaded after the start, so unsaved remark changes are saved first
        final String remarksToSave = key.equals(this.currentTicketKey) && this.remarksDirty
                ? this.remarksArea.getText() : null;
        new Task.Backgroundable(this.project, (review ? "Starting review of " : "Starting fixing of ") + key, true) {
            @Override
            public void run(ProgressIndicator indicator) {
                try {
                    final YouTrackConnector connector =
                            ReviewToolPanel.this.getService().createTicketConnector();
                    if (remarksToSave != null) {
                        connector.saveReviewData(key, remarksToSave);
                    }
                    if (review) {
                        connector.startReviewing(key);
                    } else {
                        connector.startFixing(key);
                    }
                    ApplicationManager.getApplication().invokeLater(() -> ReviewToolPanel.this.workStarted(key, review));
                } catch (final RuntimeException e) {
                    ReviewToolPanel.this.showError("Could not start working on " + key, e,
                            ReviewToolPanel.this::startWorkOnSelectedTicket);
                }
            }
        }.queue();
    }

    /**
     * Called after the ticket state was changed at the start of a review / fixing. Reloads the
     * ticket (the review round changed) and continues like the Eclipse plugin: for a review the
     * tours are created, for fixing the remarks are shown.
     */
    private void workStarted(String key, boolean review) {
        IntellijNotifications.info(this.project, (review ? "Review of " : "Fixing of ") + key + " started."
                + " The ticket list is hidden while you work on the ticket.");
        this.workingOnKey = key;
        this.parkedWorkingTours = null;
        PropertiesComponent.getInstance(this.project).setValue(
                UNFINISHED_TICKET_KEY, this.modeBox.getSelectedItem() + ":" + key);
        this.setTicketListVisible(false);
        this.updateModeBoxEnabled();
        if (review) {
            this.loadDetails(key, () -> this.createToursForLoadedChanges(true));
        } else {
            this.loadDetails(key, () -> {
                this.rightTabs.setSelectedIndex(TAB_REMARKS);
                if (this.remarksModel.findFirstOpenRemark() != null) {
                    this.remarksPanel.jumpToNextOpenRemark();
                }
            });
        }
        this.refreshTickets();
    }

    /**
     * While reviewing: when the last relevant stop has been viewed, offers to end the review.
     */
    private void allStopsVisited() {
        if (!this.isFixingMode() && this.hasTicket() && this.currentTicketKey.equals(this.workingOnKey)
                && this.currentTicketKey.equals(this.toursKey)) {
            IntellijNotifications.info(this.project, "All relevant stops of " + this.currentTicketKey
                    + " have been viewed.", "End review...", this::endReviewOrFixing);
        }
    }

    /**
     * While fixing: when the last open remark has been processed, offers to end the fixing.
     */
    private void checkAllRemarksProcessed() {
        final int open = this.remarksModel.countOpenRemarks();
        final boolean fixing = this.isFixingMode() && this.hasTicket()
                && this.currentTicketKey.equals(this.workingOnKey);
        if (fixing && this.lastOpenRemarkCount > 0 && open == 0) {
            IntellijNotifications.info(this.project, "All review remarks of " + this.currentTicketKey
                    + " have been processed.", "End fixing...", this::endReviewOrFixing);
        }
        this.lastOpenRemarkCount = open;
    }

    /**
     * Called after a review or fixing has been ended (or paused): shows the ticket list again.
     *
     * @param finished False if the work has only been paused; the ticket is then kept in the ticket
     *      list (also after a restart), although it usually does not match the filter.
     */
    private void workEnded(boolean finished) {
        if (finished) {
            if (this.workingOnKey != null) {
                this.deleteProgress(this.workingOnKey);
            }
            PropertiesComponent.getInstance(this.project).unsetValue(UNFINISHED_TICKET_KEY);
            // the state of the ticket changed
            this.refreshCurrentTicketInfo();
        } else {
            this.saveProgress(true);
        }
        this.progressSaveTimer.stop();
        this.workingOnKey = null;
        this.parkedWorkingTours = null;
        this.parkedToursChoices = null;
        this.statisticsKey = null;
        this.setTicketListVisible(true);
        this.updateModeBoxEnabled();
        this.updateCurrentTicketLabel();
    }

    /**
     * Removes the tours (and the summary and the stop markers) if they belong to another ticket than
     * the given one, so that the tabs and the editor always show the data of the shown ticket.
     */
    private void discardToursOfOtherTicket(String key) {
        if (this.toursPanel.hasTours() && !key.equals(this.toursKey)) {
            if (this.toursKey != null && this.toursKey.equals(this.workingOnKey)) {
                // only a look at another ticket: the tours (and the progress) of the review must not get lost
                this.parkedWorkingTours = this.toursPanel.getTours();
                this.parkedToursChoices = this.toursChoices;
            }
            this.toursKey = null;
            this.toursPanel.setTours(null);
            this.summaryPanel.setTours(null);
        }
        if (this.parkedWorkingTours != null && key.equals(this.workingOnKey)) {
            this.toursKey = key;
            this.toursChoices = this.parkedToursChoices;
            this.toursPanel.setTours(this.parkedWorkingTours);
            this.summaryPanel.setTours(this.parkedWorkingTours);
            this.parkedWorkingTours = null;
            this.parkedToursChoices = null;
        }
    }

    /**
     * Loads the details of the given ticket again (after an error), if it is still the shown ticket.
     */
    private void retryLoad(String key) {
        if (key.equals(this.currentTicketKey)) {
            this.loadDetails(key, null);
        }
    }

    private void endReviewOrFixing() {
        if (!this.hasTicket()) {
            return;
        }
        if (this.workingOnKey != null && !this.workingOnKey.equals(this.currentTicketKey)) {
            // another ticket is only shown: ending "its" review would end a review that has not been started
            this.offerToShowWorkingTicket();
            return;
        }
        if (!this.checkRemarksLoaded()) {
            return;
        }
        this.remarksModel.reload();
        if (this.remarksModel.getParseError() != null) {
            // like Eclipse, the syntax has to be corrected before the review/fixing can be ended
            this.rightTabs.setSelectedIndex(TAB_REMARKS);
            IntellijNotifications.warn(this.project, "The review remarks have a syntax error. Please correct"
                    + " the remarks text in the \"Remarks\" tab first: " + this.remarksModel.getParseError());
            return;
        }
        if (this.isFixingMode()) {
            this.endFixing(this.currentTicketKey);
        } else {
            this.endReview(this.currentTicketKey);
        }
    }

    /**
     * Explains that the review/fixing of another ticket than the shown one is running and offers to
     * show that ticket (so that its review/fixing can be ended).
     */
    private void offerToShowWorkingTicket() {
        final String what = this.isFixingMode() ? "fixing" : "review";
        this.offerToShowWorkingTicket(this.currentTicketKey + " is only shown. Show " + this.workingOnKey
                + " to end its " + what + "?");
    }

    /**
     * Explains that the review/fixing of another ticket is running (with the given explanation) and
     * offers to show that ticket.
     */
    private void offerToShowWorkingTicket(String explanation) {
        final String working = this.workingOnKey;
        final String what = this.isFixingMode() ? "fixing" : "review";
        final int answer = Messages.showYesNoDialog(this.project,
                "The " + what + " of " + working + " is in progress. " + explanation,
                "Code Review Tool",
                "Show " + working, "Cancel", Messages.getQuestionIcon());
        if (answer == Messages.YES && this.confirmDiscardUnsavedRemarks()) {
            this.selectTicketSilently(working);
            this.loadDetails(working, null);
        }
    }

    private void endReview(String key) {
        final ReviewData reviewData = this.remarksModel.getReviewData();
        final boolean temporary = hasTemporaryMarkers(reviewData);
        final int open = this.remarksModel.countOpenRemarks();
        final EndTransition.Type preferredType;
        if (temporary) {
            preferredType = EndTransition.Type.PAUSE;
        } else if (open > 0) {
            preferredType = EndTransition.Type.REJECTION;
        } else {
            preferredType = EndTransition.Type.OK;
        }
        final int notVisited = this.toursPanel.countRelevantStopsNotFullyVisited();
        final String summary = open + " remark(s) need fixing" + (temporary ? ", there are temporary markers" : "");
        final String warning = notVisited <= 0 ? null
                : notVisited + (notVisited == 1 ? " relevant stop has" : " relevant stops have")
                        + " not been viewed completely (or marked as checked) yet.";
        final String remarksBefore = this.remarksArea.getText();

        new Task.Backgroundable(this.project, "Ending review of " + key, true) {
            @Override
            public void run(ProgressIndicator indicator) {
                try {
                    final YouTrackConnector connector = ReviewToolPanel.this.getService().createTicketConnector();
                    final List<EndTransition> transitions = new ArrayList<>();
                    transitions.add(new EndTransition("Pause", null, EndTransition.Type.PAUSE));
                    transitions.addAll(connector.getPossibleTransitionsForReviewEnd(key));

                    final AtomicReference<EndTransition> chosen = new AtomicReference<>();
                    final AtomicReference<String> remarks = new AtomicReference<>();
                    ApplicationManager.getApplication().invokeAndWait(() -> {
                        final EndReviewDialog dialog = new EndReviewDialog(ReviewToolPanel.this.project, key,
                                transitions, remarksBefore, preferredType, summary, warning);
                        if (!dialog.showAndGet() || dialog.getSelectedTransition() == null) {
                            return;
                        }
                        if (dialog.getSelectedTransition().getType() != EndTransition.Type.PAUSE
                                && hasTemporaryMarkers(parse(dialog.getRemarks()))) {
                            final int answer = Messages.showYesNoDialog(ReviewToolPanel.this.project,
                                    "There are still temporary markers. Finish the review anyway?",
                                    "Temporary Markers", Messages.getWarningIcon());
                            if (answer != Messages.YES) {
                                return;
                            }
                        }
                        chosen.set(dialog.getSelectedTransition());
                        remarks.set(dialog.getRemarks());
                    });
                    if (chosen.get() == null) {
                        return;
                    }
                    connector.saveReviewData(key, remarks.get());
                    final boolean pause = chosen.get().getType() == EndTransition.Type.PAUSE;
                    if (!pause) {
                        connector.changeStateAtReviewEnd(key, chosen.get());
                    }
                    ApplicationManager.getApplication().invokeLater(() -> {
                        ReviewToolPanel.this.remarksSaved(key, remarks.get());
                        if (!pause) {
                            ReviewToolPanel.this.clearAllMarkers();
                        }
                        ReviewToolPanel.this.workEnded(!pause);
                        IntellijNotifications.info(ReviewToolPanel.this.project, pause
                                ? "Review of " + key + " paused, the remarks have been saved."
                                : "Review of " + key + " ended: " + chosen.get().getNameForUser());
                    });
                    ReviewToolPanel.this.refreshTickets();
                } catch (final RuntimeException e) {
                    ReviewToolPanel.this.showError("Could not end review for " + key + " (the remarks are kept)", e,
                            ReviewToolPanel.this::endReviewOrFixing);
                }
            }
        }.queue();
    }

    private void endFixing(String key) {
        final int open = this.remarksModel.countOpenRemarks();
        if (open > 0) {
            final int answer = Messages.showYesNoDialog(this.project,
                    open + " remark(s) have not been marked as processed yet. You can mark them in the \"Remarks\""
                    + " tab or with the gutter icon of the remark marker. Finish fixing anyway?",
                    "Open Remarks", "Finish Fixing", "Continue Fixing", Messages.getWarningIcon());
            if (answer != Messages.YES) {
                return;
            }
        } else {
            final int answer = Messages.showYesNoDialog(this.project,
                    "Save the remarks and set " + key + " to 'ready for review'?",
                    "End Fixing", "End Fixing", "Cancel", Messages.getQuestionIcon());
            if (answer != Messages.YES) {
                return;
            }
        }
        final String remarks = this.remarksArea.getText();
        new Task.Backgroundable(this.project, "Ending fixing of " + key, true) {
            @Override
            public void run(ProgressIndicator indicator) {
                try {
                    final YouTrackConnector connector = ReviewToolPanel.this.getService().createTicketConnector();
                    connector.saveReviewData(key, remarks);
                    connector.changeStateToReadyForReview(key);
                    ApplicationManager.getApplication().invokeLater(() -> {
                        ReviewToolPanel.this.remarksSaved(key, remarks);
                        ReviewToolPanel.this.clearAllMarkers();
                        ReviewToolPanel.this.workEnded(true);
                        IntellijNotifications.info(ReviewToolPanel.this.project,
                                "Fixing of " + key + " ended, the ticket is ready for review again.");
                    });
                    ReviewToolPanel.this.refreshTickets();
                } catch (final RuntimeException e) {
                    ReviewToolPanel.this.showError("Could not end fixing for " + key + " (the remarks are kept)", e,
                            ReviewToolPanel.this::endReviewOrFixing);
                }
            }
        }.queue();
    }

    private static ReviewData parse(String remarks) {
        try {
            return ReviewData.parse(Collections.<Integer, String>emptyMap(), DummyMarker.FACTORY, remarks);
        } catch (final RuntimeException e) {
            return new ReviewData();
        }
    }

    private static boolean hasTemporaryMarkers(ReviewData data) {
        try {
            return data.hasTemporaryMarkers();
        } catch (final RuntimeException e) {
            return false;
        }
    }

    /**
     * Saves the review remarks of the current ticket to the ticket system.
     */
    private void saveRemarks() {
        if (!this.hasTicket() || !this.checkRemarksLoaded()) {
            return;
        }
        final String key = this.currentTicketKey;
        final String remarks = this.remarksArea.getText();
        new Task.Backgroundable(this.project, "Saving review remarks for " + key, true) {
            @Override
            public void run(ProgressIndicator indicator) {
                try {
                    ReviewToolPanel.this.getService().createTicketConnector().saveReviewData(key, remarks);
                    ApplicationManager.getApplication().invokeLater(() -> {
                        ReviewToolPanel.this.remarksSaved(key, remarks);
                        IntellijNotifications.info(ReviewToolPanel.this.project, "Review remarks saved to " + key + ".");
                    });
                } catch (final RuntimeException e) {
                    ReviewToolPanel.this.showError("Could not save review remarks for " + key, e,
                            ReviewToolPanel.this::saveRemarks);
                }
            }
        }.queue();
    }

    private void remarksSaved(String key, String savedText) {
        // the backup is obsolete; if there are still unsaved changes, setRemarksDirty creates it anew
        this.deleteUnsavedRemarksBackup(key);
        if (!key.equals(this.currentTicketKey)) {
            return;
        }
        this.savedRemarks = savedText;
        this.setRemarksDirty(!savedText.equals(this.remarksArea.getText()));
    }

    /**
     * Replaces the remarks text with freshly loaded remarks (not a user change).
     */
    private void setRemarksText(String text) {
        this.updatingRemarksText = true;
        try {
            this.remarksArea.setText(text);
        } finally {
            this.updatingRemarksText = false;
        }
        this.savedRemarks = text;
        this.setRemarksDirty(false);
    }

    /**
     * Called by the remarks model when it changed the remarks (e.g. a remark was added or resolved).
     */
    private void setRemarksTextFromModel(String text) {
        this.updatingRemarksText = true;
        try {
            this.remarksArea.setText(text);
        } finally {
            this.updatingRemarksText = false;
        }
        this.setRemarksDirty(!text.equals(this.savedRemarks));
    }

    private void setRemarksDirty(boolean dirty) {
        this.remarksDirty = dirty && this.hasTicket();
        if (this.remarksDirty) {
            // a backup, so that the changes are not lost if they cannot be saved (e.g. YouTrack is not
            //  reachable) and the IDE is closed
            final PropertiesComponent properties = PropertiesComponent.getInstance(this.project);
            properties.setValue(UNSAVED_REMARKS_PREFIX + this.currentTicketKey, this.remarksArea.getText());
            properties.setValue(UNSAVED_REMARKS_PREFIX + this.currentTicketKey + UNSAVED_REMARKS_BASE_SUFFIX,
                    this.savedRemarks);
        }
        this.unsavedLabel.setText(this.remarksDirty
                ? "Unsaved changes - not yet saved to " + this.currentTicketKey
                    + " (use \"Save Remarks to Ticket\" or end the review/fixing)"
                : "");
        this.updateRemarksTabTitle();
    }

    private void updateRemarksTabTitle() {
        final int open = this.remarksModel.countOpenRemarks();
        final String title = "Remarks" + (open > 0 ? " (" + open + " open)" : "") + (this.remarksDirty ? " *" : "");
        if (this.rightTabs.getTabCount() > TAB_REMARKS) {
            this.rightTabs.setTitleAt(TAB_REMARKS, title);
        }
    }

    private void openSelectedTicketInBrowser() {
        final String key = this.getActiveTicketKey();
        if (key == null) {
            return;
        }
        try {
            BrowserUtil.browse(this.getService().createTicketConnector().getLinkSettings().createLinkFor(key));
        } catch (final RuntimeException e) {
            this.showError("Could not open " + key, e);
        }
    }

    private void copySelectedTicketId() {
        final String key = this.getActiveTicketKey();
        if (key == null) {
            return;
        }
        CopyPasteManager.getInstance().setContents(new StringSelection(key));
    }

    /**
     * Builds the review tours for the changes loaded last and shows them in the "Tours" tab.
     */
    /**
     * Creates the review tours for the loaded changes in the background.
     *
     * @param continueReview True if the tours are created for the review of the working ticket: if
     *      there is saved progress of the current review round, the choices made back then are used
     *      (instead of asking again) and the progress is restored.
     */
    private void createToursForLoadedChanges(boolean continueReview) {
        final IChangeData changes = this.lastLoadedChanges;
        if (changes == null) {
            IntellijNotifications.info(this.project, "Please select a ticket first so that its changes can be loaded.");
            return;
        }
        final String key = this.lastLoadedKey;
        final ReviewProgress saved = continueReview ? this.loadProgressOfCurrentRound(key) : null;
        final IntellijCreateToursUi choices = saved == null
                ? new IntellijCreateToursUi(this.project)
                : new IntellijCreateToursUi(this.project, saved.getTourStructure(), saved.getIrrelevantClassifications());
        new Task.Backgroundable(this.project, "Creating review tours for " + key, true) {
            @Override
            public void run(ProgressIndicator indicator) {
                try {
                    final ToursInReview tours = ReviewToolPanel.this.getService().createTours(
                            changes, new ChangeSourceUiAdapter(ReviewToolPanel.this.project, indicator), choices);
                    if (tours == null) {
                        return;
                    }
                    ApplicationManager.getApplication().invokeLater(() -> {
                        ReviewToolPanel.this.toursKey = key;
                        ReviewToolPanel.this.toursChoices = choices;
                        ReviewToolPanel.this.toursPanel.setTours(tours);
                        ReviewToolPanel.this.summaryPanel.setTours(tours);
                        ReviewToolPanel.this.rightTabs.setSelectedIndex(TAB_TOURS);
                        ReviewToolPanel.this.toursCreated(key, tours, saved);
                    });
                } catch (final ProcessCanceledException e) {
                    throw e;
                } catch (final RuntimeException e) {
                    ReviewToolPanel.this.showError("Could not create tours for " + key, e);
                }
            }
        }.queue();
    }

    /**
     * Called on the EDT after new tours have been created: restores the saved progress of the review
     * (if the tours belong to the working ticket and there is saved progress that has not been
     * applied yet).
     */
    private void toursCreated(String key, ToursInReview tours, ReviewProgress saved) {
        if (this.isFixingMode() || !key.equals(this.workingOnKey)) {
            return;
        }
        if (saved != null && !key.equals(this.statisticsKey)) {
            final int checked = saved.applyTo(this.toursPanel.getStatistics(), tours);
            if (checked > 0 || saved.hasViews()) {
                IntellijNotifications.info(this.project, "The progress of the review of " + key
                        + " has been restored (viewed lines" + (checked > 0 ? ", " + checked + " checked stop(s)" : "")
                        + ").");
            }
        }
        this.statisticsKey = key;
        this.saveProgress(false);
    }

    /**
     * Returns the saved progress of the review of the given ticket if it belongs to the current review
     * round (saved progress of an older round is deleted).
     */
    private ReviewProgress loadProgressOfCurrentRound(String key) {
        final ReviewProgress saved = ReviewProgress.load(this.progressFile(key));
        // without the remarks, the current round is unknown (and the progress must not be deleted)
        if (saved != null && this.remarksLoaded && saved.getRound() != this.currentRound) {
            this.deleteProgress(key);
            return null;
        }
        return saved;
    }

    /**
     * The file the progress of the review of the given ticket is saved in (in the IDE's system
     * directory, separately for each project).
     */
    private Path progressFile(String key) {
        return PathManager.getSystemDir().resolve("cort-review-progress").resolve(this.project.getLocationHash())
                .resolve(key.replaceAll("[^A-Za-z0-9_.-]", "_") + ".properties");
    }

    private boolean isProgressToBeSaved() {
        return !this.isFixingMode() && this.workingOnKey != null && this.workingOnKey.equals(this.toursKey)
                && this.workingOnKey.equals(this.statisticsKey) && this.toursPanel.hasTours();
    }

    private void progressChanged() {
        if (this.isProgressToBeSaved() && !this.progressSaveTimer.isRunning()) {
            this.progressSaveTimer.start();
        }
    }

    /**
     * Saves the progress of the running review (if there is one).
     *
     * @param synchronous True to write the file right away (e.g. when the IDE is closed), otherwise
     *      it is written in the background.
     */
    private void saveProgress(boolean synchronous) {
        if (!this.isProgressToBeSaved()) {
            return;
        }
        final IntellijCreateToursUi choices = this.toursChoices;
        final ReviewProgress progress = ReviewProgress.capture(this.currentRound,
                choices == null ? null : choices.getChosenTourStructure(),
                choices == null ? null : choices.getChosenIrrelevant(),
                this.toursPanel.getStatistics(), this.toursPanel.getTours());
        final Path file = this.progressFile(this.workingOnKey);
        final Runnable write = () -> {
            try {
                progress.save(file);
            } catch (final IOException e) {
                Logger.warn("could not save the review progress to " + file, e);
            }
        };
        if (synchronous) {
            write.run();
        } else {
            ApplicationManager.getApplication().executeOnPooledThread(write);
        }
    }

    private void deleteProgress(String key) {
        final Path file = this.progressFile(key);
        try {
            Files.deleteIfExists(file);
        } catch (final IOException e) {
            Logger.warn("could not delete the saved review progress " + file, e);
        }
    }

    /**
     * When the IDE was closed (or the review paused) during a review, offers to continue it where
     * the reviewer left off.
     */
    private void offerToContinueUnfinishedReview() {
        if (this.continueReviewOffered || this.workingOnKey != null) {
            return;
        }
        this.continueReviewOffered = true;
        final String stored = PropertiesComponent.getInstance(this.project).getValue(UNFINISHED_TICKET_KEY, "");
        final String prefix = ReviewToolService.FILTER_REVIEW + ":";
        if (!stored.startsWith(prefix)) {
            return;
        }
        final String key = stored.substring(prefix.length());
        if (key.isEmpty() || !Files.isRegularFile(this.progressFile(key))) {
            return;
        }
        IntellijNotifications.info(this.project, "The review of " + key + " has not been finished."
                + " Continue it where you left off?", "Continue review", () -> this.continueReview(key));
    }

    /**
     * Continues the unfinished review of the given ticket (without changing the ticket's state): shows
     * the ticket, creates the same tours as before and restores the progress.
     */
    void continueReview(String key) {
        if (this.workingOnKey != null) {
            if (!this.workingOnKey.equals(key)) {
                this.offerToShowWorkingTicket();
            }
            return;
        }
        if (!this.confirmDiscardUnsavedRemarks()) {
            return;
        }
        if (this.isFixingMode()) {
            this.modeBox.setSelectedItem(ReviewToolService.FILTER_REVIEW);
        }
        this.workingOnKey = key;
        this.parkedWorkingTours = null;
        this.parkedToursChoices = null;
        this.setTicketListVisible(false);
        this.updateModeBoxEnabled();
        this.selectTicketSilently(key);
        this.loadDetails(key, () -> this.createToursForLoadedChanges(true));
    }

    /**
     * Lets the user select individual Git commits and loads their changes for review, without
     * involving the ticket system.
     */
    private void reviewSelectedCommits() {
        if (!this.confirmDiscardUnsavedRemarks()) {
            return;
        }
        new Task.Backgroundable(this.project, "Loading recent commits", true) {
            @Override
            public void run(ProgressIndicator indicator) {
                try {
                    final List<GitCommitInfo> commits = ReviewToolPanel.this.getService()
                            .getRecentCommits(200, new ChangeSourceUiAdapter(ReviewToolPanel.this.project, indicator));
                    final AtomicReference<Set<String>> selected = new AtomicReference<>();
                    ApplicationManager.getApplication().invokeAndWait(() -> {
                        final SelectCommitsDialog dialog =
                                new SelectCommitsDialog(ReviewToolPanel.this.project, commits);
                        if (dialog.showAndGet()) {
                            selected.set(dialog.getSelectedCommitIds());
                        }
                    });
                    if (selected.get() != null && !selected.get().isEmpty()) {
                        ReviewToolPanel.this.loadChangesForCommits(selected.get());
                    }
                } catch (final ProcessCanceledException e) {
                    throw e;
                } catch (final Exception e) {
                    ReviewToolPanel.this.showError("Could not load commits", e);
                }
            }
        }.queue();
    }

    private void loadChangesForCommits(Set<String> revisionIds) {
        final int request = this.detailsRequest.incrementAndGet();
        new Task.Backgroundable(this.project, "Loading changes for selected commits", true) {
            @Override
            public void run(ProgressIndicator indicator) {
                try {
                    final IChangeData changes = ReviewToolPanel.this.getService().getChangesForCommits(
                            revisionIds, new ChangeSourceUiAdapter(ReviewToolPanel.this.project, indicator));
                    final DefaultMutableTreeNode newRoot =
                            ReviewToolPanel.this.buildCommitTree(NO_TICKET_KEY, changes);
                    ApplicationManager.getApplication().invokeLater(() -> {
                        if (!ReviewToolPanel.this.isCurrentRequest(request)) {
                            return;
                        }
                        ReviewToolPanel.this.lastLoadedChanges = changes;
                        ReviewToolPanel.this.lastLoadedKey = NO_TICKET_KEY;
                        ReviewToolPanel.this.currentTicketKey = NO_TICKET_KEY;
                        ReviewToolPanel.this.currentTicketInfo = null;
                        // the tours of earlier selected commits do not fit the new selection
                        ReviewToolPanel.this.toursKey = null;
                        ReviewToolPanel.this.discardToursOfOtherTicket(NO_TICKET_KEY);
                        ReviewToolPanel.this.updateCurrentTicketLabel();
                        ReviewToolPanel.this.selectTicketSilently(null);
                        ReviewToolPanel.this.treeModel.setRoot(newRoot);
                        TreeUtil.expandAll(ReviewToolPanel.this.commitTree);
                        ReviewToolPanel.this.remarksModel.setRoundInfo(1, null);
                        ReviewToolPanel.this.setRemarksText("");
                        ReviewToolPanel.this.remarksLoaded = true;
                        ReviewToolPanel.this.remarksArea.setEditable(true);
                        ReviewToolPanel.this.remarksArea.getEmptyText().setText(
                                "Reviewing selected commits without a ticket - remarks are not stored in a ticket");
                        ReviewToolPanel.this.remarksModel.reload();
                        ReviewToolPanel.this.rightTabs.setSelectedIndex(TAB_CHANGES);
                        IntellijNotifications.info(ReviewToolPanel.this.project, "Loaded the changes of "
                                + revisionIds.size() + " commit(s).", "Create review tours",
                                () -> ReviewToolPanel.this.createToursForLoadedChanges(false));
                    });
                } catch (final ProcessCanceledException e) {
                    throw e;
                } catch (final Exception e) {
                    ReviewToolPanel.this.showError("Could not load changes for selected commits", e);
                }
            }
        }.queue();
    }

    /**
     * Shows the current review remarks as markers (with quick-fix popups) in the editor gutters.
     */
    private void showRemarkMarkers() {
        this.remarkMarkersShown = true;
        // reload() notifies the model listener, which renders the markers because they are now shown
        this.remarksModel.reload();
    }

    /**
     * Renders a gutter marker (with a resolution popup) for every remark that refers to a file.
     * Remarks for a whole file are shown at its first line.
     */
    private void renderRemarkMarkers() {
        IntellijMarkerFactory.runOnEdt(() -> {
            final int generation = this.remarkMarkerGeneration.incrementAndGet();
            final List<ReviewRemark> remarks = new ArrayList<>();
            final Set<String> fileNames = new LinkedHashSet<>();
            for (final ReviewRemark remark : this.remarksModel.getAllRemarks()) {
                final String fileName = Position.parse(remark.getPositionString()).getShortFileName();
                if (fileName != null) {
                    remarks.add(remark);
                    fileNames.add(fileName);
                }
            }
            // the files are resolved with the file index in the background, then the markers are created
            IntellijFileResolver.findByShortNamesAsync(this.project, fileNames, (files) -> {
                if (generation != this.remarkMarkerGeneration.get() || !this.remarkMarkersShown) {
                    return;
                }
                this.markerFactory.clearReviewMarkers();
                for (final ReviewRemark remark : remarks) {
                    final Position pos = Position.parse(remark.getPositionString());
                    final VirtualFile file = files.get(pos.getShortFileName());
                    if (file == null) {
                        continue;
                    }
                    final boolean warning = remark.needsFixing();
                    final String tooltip = "[" + ReviewRemarksPanel.typeLabel(remark) + " / "
                            + remark.getResolution().name().toLowerCase().replace('_', ' ') + "] "
                            + remark.getText() + "\n(click for actions)";
                    this.markerFactory.addRemarkMarker(file, Math.max(1, pos.getLine()), warning, tooltip,
                            RemarkActions.create(this.project, this.remarksModel, () -> remark, null));
                }
            });
        });
    }

    private void clearAllMarkers() {
        this.remarkMarkersShown = false;
        this.remarkMarkerGeneration.incrementAndGet();
        this.markerFactory.clearReviewMarkers();
        this.markerFactory.clearStopMarkers();
    }

    /**
     * Adds a new review remark at the caret position of the currently active editor. When a stop is
     * selected in the "Tours" tab and the editor does not show the stop's file, the remark refers
     * to the stop. Without an editor and a selected stop, a global remark can be added.
     */
    public void addRemarkAtCursor() {
        final Editor editor = FileEditorManager.getInstance(this.project).getSelectedTextEditor();
        final VirtualFile file = editor == null ? null : FileDocumentManager.getInstance().getFile(editor.getDocument());
        final Stop stop = this.rightTabs.getSelectedIndex() == TAB_TOURS ? this.toursPanel.getSelectedStop() : null;
        if (stop != null) {
            final VirtualFile stopFile = IntellijFileResolver.findByAbsoluteFile(stop.getAbsoluteFile());
            if (stopFile != null && !stopFile.equals(file)) {
                this.addRemarkForStop(stop);
                return;
            }
        }
        if (editor == null) {
            this.addRemarkAt(null, 0, "");
            return;
        }
        final int line = editor.getCaretModel().getLogicalPosition().line + 1;
        final String selected = editor.getSelectionModel().getSelectedText();
        this.addRemarkAt(file, line, selected == null ? "" : selected);
    }

    /**
     * Adds a new review remark for the given stop (at the first line of its change, or for its file
     * if the exact position is unknown).
     */
    void addRemarkForStop(Stop stop) {
        final VirtualFile file = IntellijFileResolver.findByAbsoluteFile(stop.getAbsoluteFile());
        if (file == null) {
            IntellijNotifications.warn(this.project,
                    "The file " + stop.getAbsoluteFile() + " does not exist in the working copy (anymore).");
            return;
        }
        final int line = stop.isDetailedFragmentKnown() ? stop.getMostRecentFragment().getFrom().getLine() : 0;
        this.addRemarkAt(file, line, "");
    }

    /**
     * Adds a new review remark for the given file and (1-based) line, asking the user for the kind,
     * the text and the reference (line, file or global) in one dialog, and merges it into the review
     * remarks of the current review round via the shared model. Used by the toolbar button and the
     * editor action {@link CortActions.AddRemark}.
     *
     * @param file The file, or null for a global remark.
     * @param prefillText The text the remark is prefilled with (e.g. the selected text).
     */
    public void addRemarkAt(VirtualFile file, int line, String prefillText) {
        if (this.hasTicket() && !this.checkRemarksLoaded()) {
            return;
        }
        this.remarksModel.reload();
        if (this.remarksModel.getParseError() != null) {
            this.rightTabs.setSelectedIndex(TAB_REMARKS);
            IntellijNotifications.warn(this.project, "The review remarks have a syntax error, so no remark can be"
                    + " added. Please correct the remarks text in the \"Remarks\" tab first: "
                    + this.remarksModel.getParseError());
            return;
        }
        final Set<PositionReference> allowed;
        final String location;
        if (file == null) {
            allowed = EnumSet.of(PositionReference.GLOBAL);
            location = null;
        } else if (line <= 0) {
            allowed = EnumSet.of(PositionReference.FILE, PositionReference.GLOBAL);
            location = file.getName();
        } else {
            allowed = EnumSet.allOf(PositionReference.class);
            location = file.getName() + ":" + line;
        }
        final CreateRemarkDialog dialog = new CreateRemarkDialog(this.project, location, prefillText, allowed);
        if (!dialog.showAndGet()) {
            return;
        }

        try {
            final Position pos;
            switch (dialog.getReference()) {
            case LINE:
                pos = new FileLinePosition(file.getName(), line);
                break;
            case FILE:
                pos = new FilePosition(file.getName());
                break;
            case GLOBAL:
            default:
                pos = new GlobalPosition();
                break;
            }
            final ReviewRemark remark = ReviewRemark.create(
                    new DummyMarker(), this.remarksModel.getReviewer(), pos, dialog.getRemarkText(),
                    dialog.getRemarkType());
            // make sure the freshly added remark becomes visible as a gutter marker
            this.remarkMarkersShown = true;
            this.remarksModel.mergeNewRemark(remark);
            if (this.currentTicketKey == null) {
                IntellijNotifications.warn(this.project,
                        "No ticket is selected, so the remark can not be saved to a ticket.");
            }
        } catch (final RuntimeException e) {
            this.showError("Could not add review remark", e);
        }
    }

    /**
     * Jumps to the next open remark in fixing mode and to the next unvisited stop in review mode
     * (the counterpart of the Eclipse "Jump to next review stop or open review remark" command).
     */
    public void jumpToNext() {
        if (this.isFixingMode()) {
            this.jumpToNextOpenRemark();
        } else {
            this.jumpToNextUnvisitedStop();
        }
    }

    public void jumpToNextOpenRemark() {
        this.rightTabs.setSelectedIndex(TAB_REMARKS);
        this.remarksPanel.jumpToNextOpenRemark();
    }

    public void jumpToNextUnvisitedStop() {
        this.rightTabs.setSelectedIndex(TAB_TOURS);
        this.toursPanel.jumpToNextUnvisitedStop();
    }

    /**
     * Jumps to the previous (-1) or next (1) relevant stop.
     */
    public void navigateStops(int direction) {
        this.rightTabs.setSelectedIndex(TAB_TOURS);
        this.toursPanel.navigate(direction);
    }

    /**
     * Selects the stop nearest to the given position in the "Tours" tab.
     */
    public void showInTours(VirtualFile file, int line) {
        this.rightTabs.setSelectedIndex(TAB_TOURS);
        this.toursPanel.showNearestStop(new File(file.getPath()), line);
    }

    /**
     * Renderer for the commit tree with file type icons.
     */
    private static final class CommitTreeRenderer extends ColoredTreeCellRenderer {
        private static final long serialVersionUID = 1L;

        @Override
        public void customizeCellRenderer(JTree tree, Object value, boolean selected, boolean expanded,
                boolean leaf, int row, boolean hasFocus) {
            if (!(value instanceof DefaultMutableTreeNode)) {
                return;
            }
            final DefaultMutableTreeNode node = (DefaultMutableTreeNode) value;
            final Object userObject = node.getUserObject();
            if (userObject instanceof FileNode) {
                final FileNode fileNode = (FileNode) userObject;
                final String name = fileNode.localFile.getName();
                this.setIcon(FileTypeManager.getInstance().getFileTypeByFileName(name).getIcon());
                // colored like in IntelliJ's VCS views, and named for those who cannot tell the colors apart
                final FileStatus status = fileNode.type == FileChangeType.ADDED ? FileStatus.ADDED
                        : fileNode.type == FileChangeType.DELETED ? FileStatus.DELETED : FileStatus.MODIFIED;
                this.append(name, new SimpleTextAttributes(fileNode.type == FileChangeType.DELETED
                        ? SimpleTextAttributes.STYLE_STRIKEOUT : SimpleTextAttributes.STYLE_PLAIN, status.getColor()));
                if (fileNode.type == FileChangeType.ADDED) {
                    this.append("  new", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES);
                } else if (fileNode.type == FileChangeType.DELETED) {
                    this.append("  deleted", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES);
                }
                final String parent = fileNode.label.length() > name.length()
                        ? fileNode.label.substring(0, fileNode.label.length() - name.length()) : "";
                if (!parent.isEmpty()) {
                    this.append("  " + parent, SimpleTextAttributes.GRAYED_ATTRIBUTES);
                }
                this.setToolTipText(fileNode.localFile.getPath());
            } else if (node.isRoot()) {
                this.setIcon(AllIcons.Nodes.Folder);
                this.append(String.valueOf(userObject), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES);
            } else {
                this.setIcon(AllIcons.Vcs.CommitNode);
                this.append(String.valueOf(userObject));
            }
        }
    }

}
