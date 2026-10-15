package de.setsoftware.reviewtool.intellij;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;

import org.junit.Test;

import de.setsoftware.reviewtool.model.api.ICommit;
import de.setsoftware.reviewtool.model.api.IRevision;
import de.setsoftware.reviewtool.model.changestructure.ToursInReview.ReviewRoundInfo;

/**
 * Tests for {@link CommitSelection}.
 */
public class CommitSelectionTest {

    private static final long DAY = 24L * 60 * 60 * 1000;

    private static ICommit commit(String revision, int day) {
        final IRevision rev = (IRevision) Proxy.newProxyInstance(IRevision.class.getClassLoader(),
                new Class<?>[] {IRevision.class}, (proxy, method, args) -> {
                    if (method.getName().equals("toString")) {
                        return revision;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        return (ICommit) Proxy.newProxyInstance(ICommit.class.getClassLoader(),
                new Class<?>[] {ICommit.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                    case "getTime":
                        return new Date(day * DAY);
                    case "getRevision":
                        return rev;
                    case "toString":
                        return revision;
                    case "hashCode":
                        return revision.hashCode();
                    case "equals":
                        return proxy == args[0];
                    default:
                        throw new UnsupportedOperationException(method.getName());
                    }
                });
    }

    private static ReviewRoundInfo round(int number, int day) {
        return new ReviewRoundInfo(number, new Date(day * DAY), "ALICE");
    }

    @Test
    public void testOnlyTheCommitsSinceTheLastReviewedRound() {
        final ICommit first = commit("a1", 1);
        final ICommit second = commit("b2", 2);
        final ICommit fix1 = commit("c3", 6);
        final ICommit fix2 = commit("d4", 9);
        // unsorted, like the commits may come from the change source
        final List<ICommit> commits = Arrays.asList(fix1, first, fix2, second);
        assertEquals(Arrays.asList(first, second, fix1, fix2), CommitSelection.sortedByTime(commits));

        // round 1 started on day 3, round 2 (the current one) on day 10: the fixes are new
        final ReviewRoundInfo last = CommitSelection.lastReviewedRound(commits,
                Arrays.asList(round(1, 3), round(2, 10)));
        assertEquals(1, last.getNumber());
        assertEquals(Arrays.asList(fix1, fix2), CommitSelection.since(commits, last));

        // two earlier rounds: only the commits since the later one
        assertEquals(2, CommitSelection.lastReviewedRound(commits,
                Arrays.asList(round(1, 3), round(2, 7), round(3, 10))).getNumber());
    }

    @Test
    public void testNothingToLimitInTheFirstRound() {
        final List<ICommit> commits = Arrays.asList(commit("a1", 1), commit("b2", 2));
        // the round started after all commits: everything is new
        assertNull(CommitSelection.lastReviewedRound(commits, Collections.singletonList(round(1, 5))));
        assertNull(CommitSelection.lastReviewedRound(commits, Collections.emptyList()));
        assertNull(CommitSelection.lastReviewedRound(Collections.emptyList(),
                Collections.singletonList(round(1, 5))));
    }

    @Test
    public void testSavedSelection() {
        final ICommit first = commit("a1", 1);
        final ICommit fix = commit("c3", 6);
        final List<ICommit> commits = Arrays.asList(first, fix);
        assertEquals("c3", CommitSelection.revision(fix));
        assertEquals(Collections.singletonList(fix),
                CommitSelection.withRevisions(commits, new LinkedHashSet<>(Arrays.asList("c3", "x9"))));
        // e.g. after the history has been rewritten: all commits instead of none
        assertEquals(commits, CommitSelection.withRevisions(commits, Collections.singleton("x9")));
    }

}
