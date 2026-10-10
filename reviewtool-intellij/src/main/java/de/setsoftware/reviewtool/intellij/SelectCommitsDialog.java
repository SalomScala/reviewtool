package de.setsoftware.reviewtool.intellij;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.text.SimpleDateFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.ListSelectionModel;
import javax.swing.RowFilter;
import javax.swing.event.DocumentEvent;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableRowSorter;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.SearchTextField;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;

import de.setsoftware.reviewtool.changesources.git.GitCommitInfo;

/**
 * Dialog that lets the user select individual Git commits for a review that does not use a ticket
 * system. The selected commits are reviewed together. The commits can be filtered by message,
 * author or hash (e.g. by a ticket key) and all shown commits can be selected at once.
 */
public final class SelectCommitsDialog extends DialogWrapper {

    /**
     * Table model with a checkbox column.
     */
    private static final class Model extends AbstractTableModel {
        private static final long serialVersionUID = 1L;
        private static final String[] COLUMNS = {"", "Commit", "Date", "Message", "Author"};

        private final List<GitCommitInfo> commits;
        private final boolean[] selected;
        private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm");

        Model(List<GitCommitInfo> commits) {
            this.commits = commits;
            this.selected = new boolean[commits.size()];
        }

        @Override
        public int getRowCount() {
            return this.commits.size();
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
        public Class<?> getColumnClass(int columnIndex) {
            return columnIndex == 0 ? Boolean.class : String.class;
        }

        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            return columnIndex == 0;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            final GitCommitInfo c = this.commits.get(rowIndex);
            switch (columnIndex) {
            case 0:
                return this.selected[rowIndex];
            case 1:
                return c.getId().length() >= 8 ? c.getId().substring(0, 8) : c.getId();
            case 2:
                return this.dateFormat.format(c.getDate());
            case 3:
                return c.getSummary();
            default:
                return c.getAuthor();
            }
        }

        @Override
        public void setValueAt(Object value, int rowIndex, int columnIndex) {
            if (columnIndex == 0) {
                this.selected[rowIndex] = Boolean.TRUE.equals(value);
                this.fireTableRowsUpdated(rowIndex, rowIndex);
            }
        }

        boolean matches(int row, String filter) {
            if (filter.isEmpty()) {
                return true;
            }
            final GitCommitInfo c = this.commits.get(row);
            final String f = filter.toLowerCase(Locale.ROOT);
            return c.getSummary().toLowerCase(Locale.ROOT).contains(f)
                    || c.getAuthor().toLowerCase(Locale.ROOT).contains(f)
                    || c.getId().toLowerCase(Locale.ROOT).startsWith(f);
        }

        int countSelected() {
            int count = 0;
            for (final boolean b : this.selected) {
                if (b) {
                    count++;
                }
            }
            return count;
        }
    }

    private final List<GitCommitInfo> commits;
    private final Model model;
    private final JBTable table;
    private final TableRowSorter<Model> sorter;
    private final SearchTextField filterField = new SearchTextField(false);
    private final JBLabel countLabel = new JBLabel();

    public SelectCommitsDialog(Project project, List<GitCommitInfo> commits) {
        super(project);
        this.commits = commits;
        this.model = new Model(commits);
        this.table = new JBTable(this.model);
        this.sorter = new TableRowSorter<>(this.model);
        this.setTitle("Select Commits to Review");
        this.setOKButtonText("Review Selected Commits");
        this.init();
    }

    @Override
    protected JComponent createCenterPanel() {
        this.table.setRowSorter(this.sorter);
        this.table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        this.table.getColumnModel().getColumn(0).setMaxWidth(JBUI.scale(30));
        this.table.getColumnModel().getColumn(1).setPreferredWidth(JBUI.scale(80));
        this.table.getColumnModel().getColumn(2).setPreferredWidth(JBUI.scale(120));
        this.table.getColumnModel().getColumn(3).setPreferredWidth(JBUI.scale(420));
        this.table.getColumnModel().getColumn(4).setPreferredWidth(JBUI.scale(120));
        this.table.getEmptyText().setText(this.commits.isEmpty()
                ? "No commits found in the working copy" : "No commits match the filter");
        this.model.addTableModelListener((e) -> this.updateCountLabel());

        this.filterField.addDocumentListener(new DocumentAdapter() {
            @Override
            protected void textChanged(DocumentEvent e) {
                SelectCommitsDialog.this.applyFilter();
            }
        });

        final JPanel north = new JPanel(new BorderLayout(JBUI.scale(8), 0));
        north.add(new JBLabel("Filter (message, author or hash, e.g. a ticket key):"), BorderLayout.WEST);
        north.add(this.filterField, BorderLayout.CENTER);

        final JPanel south = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0));
        final JButton selectShown = new JButton("Select All Shown");
        selectShown.addActionListener((e) -> this.setShownSelected(true));
        final JButton clear = new JButton("Clear Selection");
        clear.addActionListener((e) -> this.setShownSelected(false));
        south.add(selectShown);
        south.add(clear);
        south.add(this.countLabel);
        this.updateCountLabel();

        final JPanel panel = new JPanel(new BorderLayout(0, JBUI.scale(6)));
        panel.add(north, BorderLayout.NORTH);
        final JBScrollPane scrollPane = new JBScrollPane(this.table);
        scrollPane.setPreferredSize(new Dimension(JBUI.scale(800), JBUI.scale(400)));
        panel.add(scrollPane, BorderLayout.CENTER);
        panel.add(south, BorderLayout.SOUTH);
        return panel;
    }

    @Override
    public JComponent getPreferredFocusedComponent() {
        return this.filterField;
    }

    @Override
    protected String getDimensionServiceKey() {
        return "de.setsoftware.reviewtool.SelectCommitsDialog";
    }

    private void applyFilter() {
        final String filter = this.filterField.getText().trim();
        this.sorter.setRowFilter(new RowFilter<Model, Integer>() {
            @Override
            public boolean include(Entry<? extends Model, ? extends Integer> entry) {
                return SelectCommitsDialog.this.model.matches(entry.getIdentifier(), filter);
            }
        });
    }

    /**
     * Selects or deselects all commits that are currently shown (i.e. match the filter).
     */
    private void setShownSelected(boolean selected) {
        for (int viewRow = 0; viewRow < this.table.getRowCount(); viewRow++) {
            this.model.selected[this.table.convertRowIndexToModel(viewRow)] = selected;
        }
        this.model.fireTableDataChanged();
    }

    private void updateCountLabel() {
        final int count = this.model.countSelected();
        this.countLabel.setText(count == 1 ? "1 commit selected" : count + " commits selected");
    }

    @Override
    protected ValidationInfo doValidate() {
        if (this.model.countSelected() == 0) {
            return new ValidationInfo("Select at least one commit.");
        }
        return null;
    }

    /**
     * Returns the full ids (hashes) of the selected commits (in the order of the list).
     */
    public Set<String> getSelectedCommitIds() {
        final Set<String> ret = new LinkedHashSet<>();
        for (int i = 0; i < this.commits.size(); i++) {
            if (this.model.selected[i]) {
                ret.add(this.commits.get(i).getId());
            }
        }
        return ret;
    }

}
