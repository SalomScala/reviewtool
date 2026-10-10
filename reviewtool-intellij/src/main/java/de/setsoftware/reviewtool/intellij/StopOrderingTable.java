package de.setsoftware.reviewtool.intellij;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import javax.swing.DefaultCellEditor;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.ListSelectionModel;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;

import com.intellij.ui.ToolbarDecorator;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.EditableModel;
import com.intellij.util.ui.JBUI;

import de.setsoftware.reviewtool.intellij.StopOrderingSettings.Entry;
import de.setsoftware.reviewtool.intellij.StopOrderingSettings.RelationType;
import de.setsoftware.reviewtool.ordering.HierarchyExplicitness;

/**
 * Editor for the {@link StopOrderingSettings} on the settings page: a table of all relation types
 * with a checkbox to activate them and the hierarchy explicitness; the rows can be moved up and
 * down to change the priority.
 */
final class StopOrderingTable {

    /**
     * A row of the table.
     */
    private static final class Row {
        private final RelationType type;
        private boolean active;
        private HierarchyExplicitness explicitness;

        Row(RelationType type, boolean active, HierarchyExplicitness explicitness) {
            this.type = type;
            this.active = active;
            this.explicitness = explicitness;
        }
    }

    /**
     * Table model with movable rows.
     */
    private static final class Model extends AbstractTableModel implements EditableModel {
        private static final long serialVersionUID = 1L;
        private static final String[] COLUMNS = {"Active", "Relation", "Nesting in the tour tree"};

        private final List<Row> rows = new ArrayList<>();

        @Override
        public int getRowCount() {
            return this.rows.size();
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
            switch (columnIndex) {
            case 0:
                return Boolean.class;
            case 2:
                return HierarchyExplicitness.class;
            default:
                return String.class;
            }
        }

        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            return columnIndex == 0
                    || (columnIndex == 2 && this.rows.get(rowIndex).type.getPossibleExplicitness().size() > 1);
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            final Row row = this.rows.get(rowIndex);
            switch (columnIndex) {
            case 0:
                return row.active;
            case 1:
                return row.type.getDescription();
            default:
                return row.explicitness;
            }
        }

        @Override
        public void setValueAt(Object value, int rowIndex, int columnIndex) {
            final Row row = this.rows.get(rowIndex);
            if (columnIndex == 0) {
                row.active = (Boolean) value;
            } else if (columnIndex == 2 && value instanceof HierarchyExplicitness) {
                row.explicitness = row.type.limit((HierarchyExplicitness) value);
            }
            this.fireTableRowsUpdated(rowIndex, rowIndex);
        }

        @Override
        public void addRow() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void removeRow(int index) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void exchangeRows(int oldIndex, int newIndex) {
            final Row row = this.rows.remove(oldIndex);
            this.rows.add(newIndex, row);
            this.fireTableDataChanged();
        }

        @Override
        public boolean canExchangeRows(int oldIndex, int newIndex) {
            return true;
        }
    }

    private final Model model = new Model();
    private final JBTable table = new JBTable(this.model);
    private final JComponent component;

    StopOrderingTable() {
        this.table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        this.table.getColumnModel().getColumn(0).setMaxWidth(JBUI.scale(60));
        this.table.getColumnModel().getColumn(2).setCellEditor(
                new DefaultCellEditor(new JComboBox<>(HierarchyExplicitness.values())));
        this.table.getColumnModel().getColumn(2).setCellRenderer(new DefaultTableCellRenderer() {
            private static final long serialVersionUID = 1L;

            @Override
            protected void setValue(Object value) {
                this.setText(value instanceof HierarchyExplicitness
                        ? StopOrderingSettings.describe((HierarchyExplicitness) value) : "");
            }
        });
        this.table.setVisibleRowCount(RelationType.values().length);
        this.component = ToolbarDecorator.createDecorator(this.table)
                .disableAddAction()
                .disableRemoveAction()
                .createPanel();
    }

    JComponent getComponent() {
        return this.component;
    }

    /**
     * Shows the given configuration: first the active types in their order, then the inactive ones.
     */
    void setSettings(String serialized) {
        this.model.rows.clear();
        final Set<RelationType> active = EnumSet.noneOf(RelationType.class);
        for (final Entry e : StopOrderingSettings.parse(serialized)) {
            this.model.rows.add(new Row(e.getType(), true, e.getExplicitness()));
            active.add(e.getType());
        }
        for (final RelationType t : RelationType.values()) {
            if (!active.contains(t)) {
                this.model.rows.add(new Row(t, false, t.getDefaultExplicitness()));
            }
        }
        this.model.fireTableDataChanged();
    }

    /**
     * Returns the edited configuration in serialized form.
     */
    String getSettings() {
        if (this.table.isEditing()) {
            this.table.getCellEditor().stopCellEditing();
        }
        final List<Entry> entries = new ArrayList<>();
        for (final Row row : this.model.rows) {
            if (row.active) {
                entries.add(new Entry(row.type, row.explicitness));
            }
        }
        return StopOrderingSettings.serialize(entries);
    }

}
