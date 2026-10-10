package de.setsoftware.reviewtool.model.viewtracking;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

/**
 * Tests for exporting and restoring the {@link ViewStatistics} (used to keep the review progress
 * over a restart of the IDE).
 */
public class ViewStatisticsTest {

    @Test
    public void testExportAndRestore() {
        final File file = new File("Foo.java").getAbsoluteFile();
        final ViewStatistics original = new ViewStatistics();
        original.mark(file, 3, 5);
        original.mark(file, 5, 6);
        original.markUnknownPosition(file);

        final ViewStatisticsForFile exported = original.getStatisticsPerFile().get(file);
        assertEquals(1, exported.getUnspecificCount());
        final Map<Integer, Integer> expectedCounts = new HashMap<>();
        expectedCounts.put(3, 1);
        expectedCounts.put(4, 1);
        expectedCounts.put(5, 2);
        expectedCounts.put(6, 1);
        assertEquals(expectedCounts, exported.getCountsPerLine());

        final ViewStatistics restored = new ViewStatistics();
        restored.restore(file, exported.getUnspecificCount(), exported.getCountsPerLine());
        final ViewStatisticsForFile restoredFile = restored.getStatisticsPerFile().get(file);
        assertEquals(1, restoredFile.getUnspecificCount());
        assertEquals(expectedCounts, restoredFile.getCountsPerLine());
        assertEquals(1.0, restoredFile.determineViewRatio(5, 5, 2).getAverageRatio(), 0.001);
        assertEquals(0.5, restoredFile.determineViewRatio(3, 3, 2).getAverageRatio(), 0.001);
    }

    @Test
    public void testRestoreNotifiesAndClearRemovesEverything() {
        final File file = new File("Bar.java").getAbsoluteFile();
        final ViewStatistics stats = new ViewStatistics();
        final int[] notifications = new int[1];
        final IViewStatisticsListener listener = (f) -> notifications[0]++;
        stats.addListener(listener);
        final Map<Integer, Integer> counts = new HashMap<>();
        counts.put(1, 2);
        stats.restore(file, 0, counts);
        assertEquals(1, notifications[0]);

        stats.clear();
        assertTrue(stats.getStatisticsPerFile().isEmpty());
        assertTrue(stats.getCheckedStops().isEmpty());
        assertEquals(2, notifications[0]);
    }
}
