package de.setsoftware.reviewtool.intellij;

import static org.junit.Assert.assertEquals;

import java.nio.charset.StandardCharsets;

import org.junit.Test;

/**
 * Tests for {@link FileTexts}.
 */
public class FileTextsTest {

    private static final byte[] UTF8 = "// Größe in €\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] WINDOWS_1252 = "// Größe in €\n".getBytes(FileTexts.FALLBACK);

    @Test
    public void testUtf8() {
        assertEquals(StandardCharsets.UTF_8, FileTexts.charsetFor(UTF8, null));
        assertEquals("// Größe in €\n", FileTexts.decode(UTF8));
        // a byte order mark is not part of the text
        final byte[] withBom = new byte[UTF8.length + 3];
        withBom[0] = (byte) 0xEF;
        withBom[1] = (byte) 0xBB;
        withBom[2] = (byte) 0xBF;
        System.arraycopy(UTF8, 0, withBom, 3, UTF8.length);
        assertEquals("// Größe in €\n", FileTexts.decode(withBom));
    }

    @Test
    public void testOlderSourcesInWindows1252() {
        // like in the core: one encoding for all versions of the file, UTF-8 only if all are valid UTF-8
        assertEquals(FileTexts.FALLBACK, FileTexts.charsetFor(UTF8, WINDOWS_1252));
        assertEquals("// Größe in €\n", FileTexts.decode(WINDOWS_1252));
        // one character per byte, so that the positions match the core's (which uses ISO-8859-1)
        assertEquals(WINDOWS_1252.length, FileTexts.decode(WINDOWS_1252).length());
    }

    @Test
    public void testLineSeparatorsAreNormalized() {
        assertEquals("a\nb\nc\nd", FileTexts.decode("a\r\nb\rc\nd".getBytes(StandardCharsets.UTF_8)));
        assertEquals("", FileTexts.decode(null, StandardCharsets.UTF_8));
    }

}
