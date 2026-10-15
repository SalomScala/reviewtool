package de.setsoftware.reviewtool.intellij;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * Turns file contents (e.g. of a revision from Git or of the working copy) into text for the diffs,
 * the summary and the AI: the encoding is chosen like in the core (UTF-8 if the contents are valid
 * UTF-8, otherwise a single byte encoding, so that the character positions match the core's), and
 * the line separators are normalized (on Windows, the working copy often has CRLF while Git has LF).
 */
final class FileTexts {

    /**
     * The encoding for contents that are not valid UTF-8: windows-1252 (common for older sources on
     * Windows) has one character per byte like the core's ISO-8859-1, but also shows e.g. "€" and "„“".
     */
    static final Charset FALLBACK = Charset.forName("windows-1252");

    private FileTexts() {
    }

    /**
     * Returns the encoding for the given contents (of the same file in different revisions): UTF-8 if
     * all of them are valid UTF-8, otherwise {@link #FALLBACK}. Null contents are ignored.
     */
    static Charset charsetFor(byte[]... contents) {
        for (final byte[] c : contents) {
            if (c != null && !isValidUtf8(c)) {
                return FALLBACK;
            }
        }
        return StandardCharsets.UTF_8;
    }

    /**
     * Decodes the contents with the given encoding, removes a UTF-8 byte order mark and normalizes the
     * line separators to "\n". Null contents result in an empty text.
     */
    static String decode(byte[] contents, Charset charset) {
        if (contents == null) {
            return "";
        }
        String text = new String(contents, charset);
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1);
        }
        return normalizeLineSeparators(text);
    }

    /**
     * Decodes the contents of a single file, see {@link #charsetFor} and {@link #decode}.
     */
    static String decode(byte[] contents) {
        return decode(contents, charsetFor(contents));
    }

    static String normalizeLineSeparators(String text) {
        return text.indexOf('\r') < 0 ? text : text.replace("\r\n", "\n").replace('\r', '\n');
    }

    private static boolean isValidUtf8(byte[] contents) {
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(contents));
            return true;
        } catch (final CharacterCodingException e) {
            return false;
        }
    }

}
