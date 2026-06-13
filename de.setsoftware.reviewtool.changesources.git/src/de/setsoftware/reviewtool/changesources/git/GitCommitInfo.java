package de.setsoftware.reviewtool.changesources.git;

import java.util.Date;

/**
 * Lightweight, platform-independent description of a Git commit, used to let the user select
 * individual commits for a ticket-less review.
 */
public final class GitCommitInfo {

    private final String id;
    private final String author;
    private final Date date;
    private final String message;

    public GitCommitInfo(String id, String author, Date date, String message) {
        this.id = id;
        this.author = author;
        this.date = date;
        this.message = message;
    }

    /**
     * Returns the full commit hash (used to identify the commit when loading its changes).
     */
    public String getId() {
        return this.id;
    }

    public String getAuthor() {
        return this.author;
    }

    public Date getDate() {
        return this.date;
    }

    public String getMessage() {
        return this.message;
    }

    /**
     * Returns the first line of the commit message.
     */
    public String getSummary() {
        if (this.message == null) {
            return "";
        }
        final int nl = this.message.indexOf('\n');
        return nl < 0 ? this.message : this.message.substring(0, nl);
    }

}
