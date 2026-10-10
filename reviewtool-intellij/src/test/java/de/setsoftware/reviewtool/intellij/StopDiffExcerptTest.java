package de.setsoftware.reviewtool.intellij;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.Arrays;
import java.util.List;

import org.junit.Test;

/**
 * Tests for {@link StopDiffExcerpt}.
 */
public class StopDiffExcerptTest {

    private static String lines(String... lines) {
        return String.join("\n", lines) + "\n";
    }

    private static final String OLD = lines("a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "l");
    // "b" changed to "B" (line 1), "x" inserted after "i" (new line 9)
    private static final String NEW = lines("a", "B", "c", "d", "e", "f", "g", "h", "i", "x", "j", "k", "l");
    private static final List<StopDiffExcerpt.Change> CHANGES = Arrays.asList(
            new StopDiffExcerpt.Change(1, 2, 1, 2),
            new StopDiffExcerpt.Change(9, 9, 9, 10));

    @Test
    public void testOnlyTheChangeOfTheStopIsContained() {
        final StopDiffExcerpt excerpt = StopDiffExcerpt.create(OLD, NEW, CHANGES, 9, 10, 2);
        final String sep = StopDiffExcerpt.SEPARATOR;
        assertEquals(lines(sep, "h", "i", "j", "k", sep).trim(), excerpt.getOldText());
        assertEquals(lines(sep, "h", "i", "x", "j", "k", sep).trim(), excerpt.getNewText());
        assertEquals(-1, excerpt.toNewLine(0));
        assertEquals(7, excerpt.toNewLine(1));
        assertEquals(9, excerpt.toNewLine(3));
        assertEquals(9, excerpt.toOldLine(3));
        assertEquals(-1, excerpt.toNewLine(6));
        assertEquals(3, excerpt.toNewExcerptLine(9));
    }

    @Test
    public void testChangeAtTheStartHasNoLeadingSeparator() {
        final StopDiffExcerpt excerpt = StopDiffExcerpt.create(OLD, NEW, CHANGES, 1, 2, 3);
        assertEquals(lines("a", "b", "c", "d", "e", StopDiffExcerpt.SEPARATOR).trim(), excerpt.getOldText());
        assertEquals(lines("a", "B", "c", "d", "e", StopDiffExcerpt.SEPARATOR).trim(), excerpt.getNewText());
        assertEquals(0, excerpt.toNewLine(0));
    }

    @Test
    public void testNearbyChangesAreMerged() {
        final StopDiffExcerpt excerpt = StopDiffExcerpt.create(OLD, NEW, CHANGES, 0, 13, 4);
        final String sep = StopDiffExcerpt.SEPARATOR;
        assertEquals(lines("a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "l").trim(),
                excerpt.getOldText());
        assertEquals(lines("a", "B", "c", "d", "e", "f", "g", "h", "i", "x", "j", "k", "l").trim(),
                excerpt.getNewText());
        final StopDiffExcerpt separate = StopDiffExcerpt.create(OLD, NEW, CHANGES, 0, 13, 1);
        assertEquals(lines("a", "b", "c", sep, "i", "j", sep).trim(), separate.getOldText());
        assertEquals(lines("a", "B", "c", sep, "i", "x", "j", sep).trim(), separate.getNewText());
    }

    @Test
    public void testDeletionAdjacentToTheStopBelongsToIt() {
        final String oldText = lines("a", "b", "c", "d");
        final String newText = lines("a", "d");
        final StopDiffExcerpt excerpt = StopDiffExcerpt.create(oldText, newText,
                Arrays.asList(new StopDiffExcerpt.Change(1, 3, 1, 1)), 1, 1, 1);
        assertEquals(lines("a", "b", "c", "d").trim(), excerpt.getOldText());
        assertEquals(lines("a", "d").trim(), excerpt.getNewText());
    }

    @Test
    public void testNoExcerptWithoutChangesOfTheStop() {
        assertNull(StopDiffExcerpt.create(OLD, NEW, CHANGES, 4, 6, 2));
    }

}
