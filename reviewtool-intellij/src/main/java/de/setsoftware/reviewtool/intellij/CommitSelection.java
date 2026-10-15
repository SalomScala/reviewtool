package de.setsoftware.reviewtool.intellij;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import de.setsoftware.reviewtool.model.api.ICommit;
import de.setsoftware.reviewtool.model.changestructure.ToursInReview.ReviewRoundInfo;

/**
 * Determines the commits of a ticket that have been made since the last review round, so that a
 * further review round can be limited to them (typically the fixes for the remarks of the last round).
 */
final class CommitSelection {

    private CommitSelection() {
    }

    /**
     * Returns the commits sorted by their time (the oldest first).
     */
    static List<ICommit> sortedByTime(Collection<? extends ICommit> commits) {
        final List<ICommit> sorted = new ArrayList<>(commits);
        sorted.sort(Comparator.comparing(ICommit::getTime));
        return sorted;
    }

    /**
     * Returns the last review round that started after the oldest and before the newest commit, i.e.
     * the round after which there are new commits that have not been reviewed yet (null if there is
     * none, e.g. in the first review round or if nothing has been committed since).
     */
    static ReviewRoundInfo lastReviewedRound(List<? extends ICommit> commits, List<ReviewRoundInfo> rounds) {
        if (commits.isEmpty() || rounds == null) {
            return null;
        }
        final List<ICommit> sorted = sortedByTime(commits);
        final long oldest = sorted.get(0).getTime().getTime();
        final long newest = sorted.get(sorted.size() - 1).getTime().getTime();
        ReviewRoundInfo last = null;
        for (final ReviewRoundInfo round : rounds) {
            if (round.getTime() == null) {
                continue;
            }
            final long time = round.getTime().getTime();
            if (time > oldest && time < newest && (last == null || time > last.getTime().getTime())) {
                last = round;
            }
        }
        return last;
    }

    /**
     * Returns the commits made after the given review round started.
     */
    static List<ICommit> since(List<? extends ICommit> commits, ReviewRoundInfo round) {
        final List<ICommit> ret = new ArrayList<>();
        for (final ICommit commit : sortedByTime(commits)) {
            if (commit.getTime().after(round.getTime())) {
                ret.add(commit);
            }
        }
        return ret;
    }

    /**
     * Returns the commits with the given revisions (see {@link #revision(ICommit)}), or all commits
     * if none of them matches (e.g. because the history has been rewritten in the meantime).
     */
    static List<ICommit> withRevisions(List<? extends ICommit> commits, Set<String> revisions) {
        final List<ICommit> ret = new ArrayList<>();
        for (final ICommit commit : commits) {
            if (revisions.contains(revision(commit))) {
                ret.add(commit);
            }
        }
        return ret.isEmpty() ? new ArrayList<>(commits) : ret;
    }

    /**
     * The revision of the commit as it is saved with the review progress.
     */
    static String revision(ICommit commit) {
        return commit.getRevision().toString();
    }

}
