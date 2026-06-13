package de.setsoftware.reviewtool.intellij;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTree;
import javax.swing.ListSelectionModel;
import javax.swing.table.AbstractTableModel;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;

import com.intellij.ide.BrowserUtil;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.table.JBTable;

import de.setsoftware.reviewtool.changesources.git.GitCommitInfo;
import de.setsoftware.reviewtool.model.EndTransition;
import de.setsoftware.reviewtool.model.ITicketData;
import de.setsoftware.reviewtool.model.TicketInfo;
import de.setsoftware.reviewtool.model.api.IChange;
import de.setsoftware.reviewtool.model.api.IChangeData;
import de.setsoftware.reviewtool.model.api.ICommit;
import de.setsoftware.reviewtool.model.changestructure.ToursInReview;
import de.setsoftware.reviewtool.model.remarks.DummyMarker;
import de.setsoftware.reviewtool.model.remarks.FileLinePosition;
import de.setsoftware.reviewtool.model.remarks.IReviewMarker;
import de.setsoftware.reviewtool.model.remarks.Position;
import de.setsoftware.reviewtool.model.remarks.RemarkType;
import de.setsoftware.reviewtool.model.remarks.ReviewData;
import de.setsoftware.reviewtool.model.remarks.ReviewRemark;
import de.setsoftware.reviewtool.ticketconnectors.youtrack.YouTrackConnector;

/**
 * The content of the CoRT tool window: a list of tickets, the commits/files belonging
 * to the selected ticket and an editor for the review remarks.
 */
public class ReviewToolPanel extends JPanel {

    private static final long serialVersionUID = 7882988395724508758L;

    /**
     * Table model for the loaded tickets.
     */
    private static final class TicketTableModel extends AbstractTableModel {

        private static final long serialVersionUID = 1378391971397208329L;

        private static final String[] COLUMNS = {"Key", "Summary", "State", "Component"};

        private List<TicketInfo> tickets = new ArrayList<>();

        public void setTickets(List<TicketInfo> tickets) {
            this.tickets = tickets;
            this.fireTableDataChanged();
        }

        public TicketInfo getTicket(int row) {
            return this.tickets.get(row);
        }

        @Override
        public int getRowCount() {
            return this.tickets.size();
        }

        @Override
        public int getColumnCount() {
            return COLUMNS.length;
        }

        @Override
        public String getColumnName(int column) {
            return COLUMNS[column];
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            final TicketInfo t = this.tickets.get(rowIndex);
            switch (columnIndex) {
            case 0:
                return t.getId();
            case 1:
                return t.getSummaryIncludingParent();
            case 2:
                return t.getState();
            default:
                return t.getComponent();
            }
        }

    }

    /**
     * User object for file nodes in the commit tree.
     */
    private static final class FileNode {
        private final String label;
        private final File localFile;

        FileNode(String label, File localFile) {
            this.label = label;
            this.localFile = localFile;
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
    private final JTree commitTree = new JTree(this.treeModel);
    private final JTextArea remarksArea = new JTextArea();
    private final IntellijMarkerFactory markerFactory;
    private final ReviewToursPanel toursPanel;
    private final ReviewSummaryPanel summaryPanel;

    private volatile IChangeData lastLoadedChanges;
    private volatile String lastLoadedKey;

    public ReviewToolPanel(Project project) {
        super(new BorderLayout());
        this.project = project;
        this.markerFactory = new IntellijMarkerFactory(project);
        this.toursPanel = new ReviewToursPanel(project, this.markerFactory);
        this.summaryPanel = new ReviewSummaryPanel(project);
        this.buildUi();
    }

    private void buildUi() {
        final JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT));
        toolbar.add(this.modeBox);
        final JButton refreshButton = new JButton("Refresh Tickets");
        refreshButton.addActionListener((e) -> this.refreshTickets());
        toolbar.add(refreshButton);
        final JButton startButton = new JButton("Start Review/Fixing");
        startButton.addActionListener((e) -> this.startWorkOnSelectedTicket());
        toolbar.add(startButton);
        final JButton endButton = new JButton("End Review...");
        endButton.addActionListener((e) -> this.endReviewForSelectedTicket());
        toolbar.add(endButton);
        final JButton openButton = new JButton("Open in YouTrack");
        openButton.addActionListener((e) -> this.openSelectedTicketInBrowser());
        toolbar.add(openButton);
        final JButton reviewCommitsButton = new JButton("Review Commits (no ticket)");
        reviewCommitsButton.addActionListener((e) -> this.reviewSelectedCommits());
        toolbar.add(reviewCommitsButton);
        final JButton buildToursButton = new JButton("Create Tours");
        buildToursButton.addActionListener((e) -> this.createToursForSelectedTicket());
        toolbar.add(buildToursButton);
        final JButton showMarkersButton = new JButton("Show Remark Markers");
        showMarkersButton.addActionListener((e) -> this.showRemarkMarkers());
        toolbar.add(showMarkersButton);
        final JButton addRemarkButton = new JButton("Add Remark at Cursor");
        addRemarkButton.addActionListener((e) -> this.addRemarkAtCursor());
        toolbar.add(addRemarkButton);
        final JButton clearMarkersButton = new JButton("Clear Markers");
        clearMarkersButton.addActionListener((e) -> this.clearAllMarkers());
        toolbar.add(clearMarkersButton);
        this.add(toolbar, BorderLayout.NORTH);

        this.ticketTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        this.ticketTable.getSelectionModel().addListSelectionListener((e) -> {
            if (!e.getValueIsAdjusting()) {
                this.loadDetailsForSelectedTicket();
            }
        });

        this.commitTree.setRootVisible(true);
        this.commitTree.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    ReviewToolPanel.this.openSelectedFile();
                }
            }
        });

        this.remarksArea.setLineWrap(true);
        this.remarksArea.setWrapStyleWord(true);
        final JPanel remarksPanel = new JPanel(new BorderLayout());
        remarksPanel.add(new JBScrollPane(this.remarksArea), BorderLayout.CENTER);
        final JButton saveRemarksButton = new JButton("Save Remarks to Ticket");
        saveRemarksButton.addActionListener((e) -> this.saveRemarksForSelectedTicket());
        remarksPanel.add(saveRemarksButton, BorderLayout.SOUTH);

        final JSplitPane rightSplit = new JSplitPane(
                JSplitPane.VERTICAL_SPLIT,
                new JBScrollPane(this.commitTree),
                remarksPanel);
        rightSplit.setResizeWeight(0.6);

        final JTabbedPane rightTabs = new JTabbedPane();
        rightTabs.addTab("Changes", rightSplit);
        rightTabs.addTab("Tours", this.toursPanel);
        rightTabs.addTab("Summary", this.summaryPanel);

        final JSplitPane mainSplit = new JSplitPane(
                JSplitPane.HORIZONTAL_SPLIT,
                new JBScrollPane(this.ticketTable),
                rightTabs);
        mainSplit.setResizeWeight(0.4);
        this.add(mainSplit, BorderLayout.CENTER);
    }

    private ReviewToolService getService() {
        return ReviewToolService.getInstance(this.project);
    }

    private String getSelectedTicketKey() {
        final int viewRow = this.ticketTable.getSelectedRow();
        if (viewRow < 0) {
            return null;
        }
        return this.ticketModel.getTicket(this.ticketTable.convertRowIndexToModel(viewRow)).getId();
    }

    private void showError(String message, Throwable exception) {
        de.setsoftware.reviewtool.base.Logger.warn(message, exception);
        ApplicationManager.getApplication().invokeLater(() ->
                NotificationGroupManager.getInstance().getNotificationGroup("CoRT")
                        .createNotification("Code Review Tool", message + ": " + exception, NotificationType.ERROR)
                        .notify(this.project));
    }

    /**
     * Loads the tickets for the currently selected filter in the background.
     */
    public void refreshTickets() {
        final String filterName = (String) this.modeBox.getSelectedItem();
        new Task.Backgroundable(this.project, "Loading tickets from YouTrack", true) {
            @Override
            public void run(ProgressIndicator indicator) {
                try {
                    final YouTrackConnector connector =
                            ReviewToolPanel.this.getService().createTicketConnector();
                    final List<TicketInfo> tickets = connector.getTicketsForFilter(filterName);
                    ApplicationManager.getApplication().invokeLater(() ->
                            ReviewToolPanel.this.ticketModel.setTickets(tickets));
                } catch (final RuntimeException e) {
                    ReviewToolPanel.this.showError("Could not load tickets from YouTrack", e);
                }
            }
        }.queue();
    }

    private void loadDetailsForSelectedTicket() {
        final String key = this.getSelectedTicketKey();
        if (key == null) {
            return;
        }
        new Task.Backgroundable(this.project, "Loading details for " + key, true) {
            @Override
            public void run(ProgressIndicator indicator) {
                ReviewToolPanel.this.loadRemarks(key);
                ReviewToolPanel.this.loadChanges(key, indicator);
            }
        }.queue();
    }

    private void loadRemarks(String key) {
        try {
            final ITicketData ticket = this.getService().createTicketConnector().loadTicket(key);
            final String remarks = ticket == null ? "" : ticket.getReviewData();
            ApplicationManager.getApplication().invokeLater(() -> this.remarksArea.setText(remarks));
        } catch (final RuntimeException e) {
            this.showError("Could not load review remarks for " + key, e);
        }
    }

    private void loadChanges(String key, ProgressIndicator indicator) {
        try {
            final IChangeData changes = this.getService().getRepositoryChanges(
                    key, new ChangeSourceUiAdapter(this.project, indicator));
            this.lastLoadedChanges = changes;
            this.lastLoadedKey = key;
            final DefaultMutableTreeNode newRoot = this.buildCommitTree(key, changes);
            ApplicationManager.getApplication().invokeLater(() -> {
                this.treeModel.setRoot(newRoot);
                for (int i = 0; i < this.commitTree.getRowCount(); i++) {
                    this.commitTree.expandRow(i);
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
                final String path = change.getTo().getPath();
                if (seenPaths.add(path)) {
                    commitNode.add(new DefaultMutableTreeNode(new FileNode(
                            path,
                            change.getTo().toLocalPath(change.getWorkingCopy()))));
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
            Messages.showInfoMessage(this.project,
                    "The file " + file + " does not exist in the working copy (anymore).",
                    "Code Review Tool");
            return;
        }
        FileEditorManager.getInstance(this.project).openFile(virtualFile, true);
    }

    private void startWorkOnSelectedTicket() {
        final String key = this.getSelectedTicketKey();
        if (key == null) {
            return;
        }
        final boolean review = ReviewToolService.FILTER_REVIEW.equals(this.modeBox.getSelectedItem());
        new Task.Backgroundable(this.project, "Changing ticket state of " + key, true) {
            @Override
            public void run(ProgressIndicator indicator) {
                try {
                    final YouTrackConnector connector =
                            ReviewToolPanel.this.getService().createTicketConnector();
                    if (review) {
                        connector.startReviewing(key);
                    } else {
                        connector.startFixing(key);
                    }
                } catch (final RuntimeException e) {
                    ReviewToolPanel.this.showError("Could not change state of " + key, e);
                }
            }
        }.queue();
    }

    private void endReviewForSelectedTicket() {
        final String key = this.getSelectedTicketKey();
        if (key == null) {
            return;
        }
        final String remarks = this.remarksArea.getText();
        new Task.Backgroundable(this.project, "Ending review for " + key, true) {
            @Override
            public void run(ProgressIndicator indicator) {
                try {
                    final YouTrackConnector connector =
                            ReviewToolPanel.this.getService().createTicketConnector();
                    final List<EndTransition> transitions = connector.getPossibleTransitionsForReviewEnd(key);
                    final String[] options = new String[transitions.size()];
                    for (int i = 0; i < transitions.size(); i++) {
                        options[i] = transitions.get(i).getNameForUser();
                    }
                    final int[] choice = new int[] {-1};
                    ApplicationManager.getApplication().invokeAndWait(() ->
                            choice[0] = Messages.showChooseDialog(
                                    ReviewToolPanel.this.project,
                                    "Choose the new state for " + key + ":",
                                    "End Review",
                                    null,
                                    options,
                                    options.length > 0 ? options[0] : null));
                    if (choice[0] < 0) {
                        return;
                    }
                    connector.saveReviewData(key, remarks);
                    connector.changeStateAtReviewEnd(key, transitions.get(choice[0]));
                    ReviewToolPanel.this.refreshTickets();
                } catch (final RuntimeException e) {
                    ReviewToolPanel.this.showError("Could not end review for " + key, e);
                }
            }
        }.queue();
    }

    private void saveRemarksForSelectedTicket() {
        final String key = this.getSelectedTicketKey();
        if (key == null) {
            return;
        }
        final String remarks = this.remarksArea.getText();
        new Task.Backgroundable(this.project, "Saving review remarks for " + key, true) {
            @Override
            public void run(ProgressIndicator indicator) {
                try {
                    ReviewToolPanel.this.getService().createTicketConnector().saveReviewData(key, remarks);
                } catch (final RuntimeException e) {
                    ReviewToolPanel.this.showError("Could not save review remarks for " + key, e);
                }
            }
        }.queue();
    }

    private void openSelectedTicketInBrowser() {
        final String key = this.getSelectedTicketKey();
        if (key == null) {
            return;
        }
        BrowserUtil.browse(
                this.getService().createTicketConnector().getLinkSettings().createLinkFor(key));
    }

    /**
     * Builds the review tours for the changes of the currently selected ticket and shows them in
     * the "Tours" tab.
     */
    private void createToursForSelectedTicket() {
        final IChangeData changes = this.lastLoadedChanges;
        if (changes == null) {
            Messages.showInfoMessage(this.project,
                    "Please select a ticket first so that its changes can be loaded.",
                    "Code Review Tool");
            return;
        }
        final String key = this.lastLoadedKey;
        new Task.Backgroundable(this.project, "Creating review tours for " + key, true) {
            @Override
            public void run(ProgressIndicator indicator) {
                try {
                    final ToursInReview tours = ReviewToolPanel.this.getService().createTours(
                            changes, new ChangeSourceUiAdapter(ReviewToolPanel.this.project, indicator));
                    if (tours == null) {
                        return;
                    }
                    ApplicationManager.getApplication().invokeLater(() -> {
                        ReviewToolPanel.this.toursPanel.setTours(tours);
                        ReviewToolPanel.this.summaryPanel.setTours(tours);
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
     * Lets the user select individual Git commits and loads their changes for review, without
     * involving the ticket system.
     */
    private void reviewSelectedCommits() {
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
                } catch (final RuntimeException e) {
                    ReviewToolPanel.this.showError("Could not load commits", e);
                }
            }
        }.queue();
    }

    private void loadChangesForCommits(Set<String> revisionIds) {
        new Task.Backgroundable(this.project, "Loading changes for selected commits", true) {
            @Override
            public void run(ProgressIndicator indicator) {
                try {
                    final IChangeData changes = ReviewToolPanel.this.getService().getChangesForCommits(
                            revisionIds, new ChangeSourceUiAdapter(ReviewToolPanel.this.project, indicator));
                    ReviewToolPanel.this.lastLoadedChanges = changes;
                    ReviewToolPanel.this.lastLoadedKey = "Selected commits";
                    final DefaultMutableTreeNode newRoot =
                            ReviewToolPanel.this.buildCommitTree("Selected commits", changes);
                    ApplicationManager.getApplication().invokeLater(() -> {
                        ReviewToolPanel.this.treeModel.setRoot(newRoot);
                        for (int i = 0; i < ReviewToolPanel.this.commitTree.getRowCount(); i++) {
                            ReviewToolPanel.this.commitTree.expandRow(i);
                        }
                        ReviewToolPanel.this.remarksArea.setText(
                                "Reviewing selected commits (no ticket). Use \"Create Tours\" to build the tours"
                                + " and the \"Summary\" tab for an overview.");
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
     * Parses the review remarks currently shown in the editor and renders them as markers in the
     * editor gutters.
     */
    private void showRemarkMarkers() {
        final String remarks = this.remarksArea.getText();
        new Task.Backgroundable(this.project, "Loading review remark markers", false) {
            @Override
            public void run(ProgressIndicator indicator) {
                try {
                    ReviewToolPanel.this.markerFactory.clearReviewMarkers();
                    ReviewData.parse(
                            Collections.<Integer, String>emptyMap(),
                            ReviewToolPanel.this.markerFactory,
                            remarks);
                    ReviewToolPanel.this.markerFactory.renderReviewMarkers();
                } catch (final RuntimeException e) {
                    ReviewToolPanel.this.showError("Could not parse review remarks", e);
                }
            }
        }.queue();
    }

    private void clearAllMarkers() {
        this.markerFactory.clearReviewMarkers();
        this.markerFactory.clearStopMarkers();
    }

    /**
     * Adds a new review remark at the caret position of the currently active editor and merges it
     * into the review remarks text.
     */
    private void addRemarkAtCursor() {
        final Editor editor = FileEditorManager.getInstance(this.project).getSelectedTextEditor();
        if (editor == null) {
            Messages.showInfoMessage(this.project,
                    "Please open a file and place the caret where the remark should be added.",
                    "Code Review Tool");
            return;
        }
        final VirtualFile file = FileDocumentManager.getInstance().getFile(editor.getDocument());
        if (file == null) {
            return;
        }
        final int line = editor.getCaretModel().getLogicalPosition().line + 1;

        final String text = Messages.showInputDialog(this.project,
                "Remark for " + file.getName() + ":" + line, "Add Review Remark", null);
        if (text == null || text.trim().isEmpty()) {
            return;
        }
        final RemarkType[] types = RemarkType.values();
        final String[] typeNames = new String[types.length];
        for (int i = 0; i < types.length; i++) {
            typeNames[i] = types[i].name();
        }
        final int typeChoice = Messages.showChooseDialog(this.project,
                "Type of the remark:", "Add Review Remark", null, typeNames, typeNames[0]);
        if (typeChoice < 0) {
            return;
        }

        try {
            final Position pos = new FileLinePosition(file.getName(), line);
            final IReviewMarker marker = this.markerFactory.createMarker(pos);
            final ReviewRemark remark = ReviewRemark.create(
                    marker, System.getProperty("user.name", "reviewer"), pos, text.trim(), types[typeChoice]);

            final ReviewData reviewData = ReviewData.parse(
                    Collections.<Integer, String>emptyMap(),
                    DummyMarker.FACTORY,
                    this.remarksArea.getText());
            reviewData.merge(remark, 1);
            this.remarksArea.setText(reviewData.serialize());
            this.markerFactory.renderReviewMarkers();
        } catch (final RuntimeException e) {
            this.showError("Could not add review remark", e);
        }
    }

}
