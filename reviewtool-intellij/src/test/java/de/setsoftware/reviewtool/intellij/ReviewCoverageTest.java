package de.setsoftware.reviewtool.intellij;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import de.setsoftware.reviewtool.intellij.ReviewCoverage.State;
import de.setsoftware.reviewtool.intellij.ReviewCoverage.StopCoverage;

/**
 * Tests for {@link ReviewCoverage}.
 */
public class ReviewCoverageTest {

    private static StopCoverage stop(String description, State state) {
        return new StopCoverage(description, state);
    }

    @Test
    public void testNoWarningWhenEverythingHasBeenViewed() {
        assertNull(ReviewCoverage.warning(new ArrayList<>()));
        assertNull(ReviewCoverage.warning(Arrays.asList(stop("A.java:1", State.OK), stop("B.java:2-3", State.OK))));
    }

    @Test
    public void testWarningNamesTheStops() {
        final String warning = ReviewCoverage.warning(Arrays.asList(
                stop("A.java:1", State.NOT_VIEWED),
                stop("A.java:9", State.OK),
                stop("B.java:2-3", State.PARTLY_VIEWED),
                stop("C.java:5", State.OK),
                stop("C.java:7", State.OK),
                stop("D.java:4-8", State.BRIEFLY_VIEWED)));
        assertEquals("2 relevant stops have not been viewed completely (or marked as checked): A.java:1,"
                + " B.java:2-3 (partly). 1 stop was only shown briefly (no line for 5 seconds): D.java:4-8."
                + " 1 of them is in the last third of the review, where reviewers find markedly fewer problems.",
                warning);
    }

    @Test
    public void testLongListsAreShortened() {
        final List<StopCoverage> stops = new ArrayList<>();
        for (int i = 1; i <= 8; i++) {
            stops.add(stop("F" + i + ".java", State.NOT_VIEWED));
        }
        final String warning = ReviewCoverage.warning(stops);
        assertTrue(warning, warning.startsWith("8 relevant stops have not been viewed completely (or marked as"
                + " checked): F1.java, F2.java, F3.java, F4.java, F5.java and 3 more."));
        // the last third of 8 stops: the last 2 (rounded down)
        assertTrue(warning, warning.endsWith("2 of them are in the last third of the review, where reviewers find"
                + " markedly fewer problems."));
        // with less than 3 stops, there is no "last third"
        assertEquals("1 relevant stop has not been viewed completely (or marked as checked): X.java.",
                ReviewCoverage.warning(Arrays.asList(stop("Y.java", State.OK), stop("X.java", State.NOT_VIEWED))));
    }

    @Test
    public void testSizeHint() {
        assertNull(ReviewCoverage.sizeHint("DEMO-1", 399, 10));
        final String hint = ReviewCoverage.sizeHint("DEMO-1", 400, 12);
        assertTrue(hint, hint.startsWith("DEMO-1 is large: 400 changed lines in 12 relevant stops."));
        assertTrue(hint, hint.contains("several sessions"));
    }

}
