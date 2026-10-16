package de.setsoftware.reviewtool.intellij;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns the view statistics of the relevant stops into warnings for the reviewer. Studies on code review
 * show that review coverage matters (components with low coverage have more defects after the release),
 * that files shown late in a review get markedly less attention (a defect in the last file was found
 * with 64% lower odds), and that large reviews find markedly fewer defects (59% vs. 35% for small vs. large
 * changes in Baum et al., EMSE 2019).
 */
final class ReviewCoverage {

    /** A stop counts as only briefly viewed if none of its lines was shown this many seconds. */
    static final int BRIEF_VIEW_SECONDS = 5;

    /** From this number of changed lines (in the relevant stops) on, a review counts as large. */
    static final int LARGE_REVIEW_LINES = 400;

    private static final int MAX_LISTED = 5;

    /**
     * How much of a relevant stop the reviewer has seen.
     */
    enum State {
        NOT_VIEWED,
        PARTLY_VIEWED,
        BRIEFLY_VIEWED,
        OK
    }

    /**
     * A relevant stop with its view state, in the order of the review.
     */
    static final class StopCoverage {
        private final String description;
        private final State state;

        /**
         * @param description A short description, e.g. "Statistics.java:24-28".
         */
        StopCoverage(String description, State state) {
            this.description = description;
            this.state = state;
        }

        String getDescription() {
            return this.description;
        }

        State getState() {
            return this.state;
        }
    }

    private ReviewCoverage() {
    }

    /**
     * Returns a warning about the relevant stops (in the order of the review) that have not been viewed
     * completely or only briefly, or null if there are none.
     */
    static String warning(List<StopCoverage> relevantStops) {
        final List<String> notViewed = new ArrayList<>();
        final List<String> brief = new ArrayList<>();
        int late = 0;
        final int lateFrom = relevantStops.size() - relevantStops.size() / 3;
        for (int i = 0; i < relevantStops.size(); i++) {
            final StopCoverage stop = relevantStops.get(i);
            if (stop.state == State.OK) {
                continue;
            }
            if (stop.state == State.BRIEFLY_VIEWED) {
                brief.add(stop.description);
            } else {
                notViewed.add(stop.description + (stop.state == State.PARTLY_VIEWED ? " (partly)" : ""));
            }
            if (i >= lateFrom && relevantStops.size() >= 3) {
                late++;
            }
        }
        if (notViewed.isEmpty() && brief.isEmpty()) {
            return null;
        }
        final StringBuilder text = new StringBuilder();
        if (!notViewed.isEmpty()) {
            text.append(notViewed.size()).append(notViewed.size() == 1 ? " relevant stop has" : " relevant stops have")
                    .append(" not been viewed completely (or marked as checked): ").append(list(notViewed))
                    .append(". ");
        }
        if (!brief.isEmpty()) {
            text.append(brief.size()).append(brief.size() == 1 ? " stop was" : " stops were")
                    .append(" only shown briefly (no line for ").append(BRIEF_VIEW_SECONDS).append(" seconds): ")
                    .append(list(brief)).append(". ");
        }
        if (late > 0) {
            text.append(late).append(late == 1 ? " of them is" : " of them are")
                    .append(" in the last third of the review, where reviewers find markedly fewer problems. ");
        }
        return text.toString().trim();
    }

    private static String list(List<String> descriptions) {
        if (descriptions.size() <= MAX_LISTED) {
            return String.join(", ", descriptions);
        }
        return String.join(", ", descriptions.subList(0, MAX_LISTED)) + " and " + (descriptions.size() - MAX_LISTED)
                + " more";
    }

    /**
     * Returns a hint for a large review (null if it is not large).
     *
     * @param changedLines The number of changed lines in the relevant stops.
     */
    static String sizeHint(String reviewName, int changedLines, int relevantStops) {
        if (changedLines < LARGE_REVIEW_LINES) {
            return null;
        }
        return reviewName + " is large: " + changedLines + " changed lines in " + relevantStops
                + " relevant stops. In studies, reviews of large changes found markedly fewer defects (59% vs. 35%"
                + " for small changes). Consider reviewing it in several sessions (e.g. one tour per session) or"
                + " asking the author to split it.";
    }

}
