package de.setsoftware.reviewtool.intellij;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import javax.swing.table.AbstractTableModel;

import de.setsoftware.reviewtool.model.TicketInfo;

/**
 * Table model for the tickets in the tool window, with the same columns as the Eclipse ticket
 * selection dialog. The previous state and reviewers are filled in later (see
 * {@link #updateTicket(TicketInfo)}), because they have to be determined from each ticket's history.
 */
final class TicketTableModel extends AbstractTableModel {

    private static final long serialVersionUID = 1378391971397208329L;

    static final int COLUMN_KEY = 0;
    static final int COLUMN_SUMMARY = 1;
    static final int COLUMN_STATE = 2;
    static final int COLUMN_PREVIOUS_STATE = 3;
    static final int COLUMN_PREVIOUS_REVIEWERS = 4;
    static final int COLUMN_COMPONENT = 5;
    static final int COLUMN_OPEN_DAYS = 6;

    private static final String[] COLUMNS =
        {"Key", "Summary", "State", "Prev. state", "Prev. reviewers", "Component", "Open (days)"};

    private List<TicketInfo> tickets = new ArrayList<>();

    void setTickets(List<TicketInfo> tickets) {
        this.tickets = new ArrayList<>(tickets);
        this.fireTableDataChanged();
    }

    TicketInfo getTicket(int row) {
        return this.tickets.get(row);
    }

    int indexOf(String key) {
        for (int i = 0; i < this.tickets.size(); i++) {
            if (this.tickets.get(i).getId().equals(key)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Replaces the ticket with the same key (e.g. by one with the information from its history).
     */
    void updateTicket(TicketInfo ticket) {
        final int index = this.indexOf(ticket.getId());
        if (index >= 0) {
            this.tickets.set(index, ticket);
            this.fireTableRowsUpdated(index, index);
        }
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
    public Class<?> getColumnClass(int columnIndex) {
        return columnIndex == COLUMN_OPEN_DAYS ? Integer.class : String.class;
    }

    @Override
    public Object getValueAt(int rowIndex, int columnIndex) {
        final TicketInfo t = this.tickets.get(rowIndex);
        switch (columnIndex) {
        case COLUMN_KEY:
            return t.getId();
        case COLUMN_SUMMARY:
            return t.getSummaryIncludingParent();
        case COLUMN_STATE:
            return t.getState();
        case COLUMN_PREVIOUS_STATE:
            return t.getPreviousState();
        case COLUMN_PREVIOUS_REVIEWERS:
            return String.join(", ", t.getReviewers());
        case COLUMN_COMPONENT:
            return t.getComponent();
        case COLUMN_OPEN_DAYS:
        default:
            return t.getWaitingForDays(new Date());
        }
    }

}
