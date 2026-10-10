package de.setsoftware.reviewtool.intellij;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

import org.junit.Test;

/**
 * Tests for the file format of {@link ReviewProgress}.
 */
public class ReviewProgressTest {

    @Test
    public void testCountsAreEncodedAsRanges() {
        final Map<Integer, Integer> counts = new TreeMap<>();
        counts.put(3, 2);
        counts.put(4, 2);
        counts.put(5, 2);
        counts.put(6, 1);
        counts.put(9, 1);
        assertEquals("3-5:2,6:1,9:1", ReviewProgress.encodeCounts(counts));
        assertEquals(counts, ReviewProgress.decodeCounts("3-5:2,6:1,9:1"));
        assertEquals("", ReviewProgress.encodeCounts(new TreeMap<>()));
        assertEquals(new TreeMap<>(), ReviewProgress.decodeCounts(""));
    }

    @Test
    public void testBrokenOrUnknownFilesAreIgnored() throws IOException {
        final Path dir = Files.createTempDirectory("cort-progress");
        final Path broken = dir.resolve("broken.properties");
        Files.write(broken, "version=1\nround=x\n".getBytes(StandardCharsets.ISO_8859_1));
        assertNull(ReviewProgress.load(broken));
        final Path future = dir.resolve("future.properties");
        Files.write(future, "version=99\nround=1\n".getBytes(StandardCharsets.ISO_8859_1));
        assertNull(ReviewProgress.load(future));
    }

}
