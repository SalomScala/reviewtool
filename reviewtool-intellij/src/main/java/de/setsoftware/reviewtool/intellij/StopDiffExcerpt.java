package de.setsoftware.reviewtool.intellij;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * An excerpt of the old and the new text of a file that only contains the changes belonging to a
 * review stop (with some lines of context), so that the stop's diff does not show the whole file and
 * the other changes in it. The excerpts of the single changes are separated by a marker line; the
 * line numbers of the excerpts can be mapped back to the line numbers in the files.
 */
final class StopDiffExcerpt {

    static final String SEPARATOR = "⋯";

    /**
     * A changed range of lines (0-based, end exclusive) in the old and in the new text.
     */
    static final class Change {
        private final int oldStart;
        private final int oldEnd;
        private final int newStart;
        private final int newEnd;

        Change(int oldStart, int oldEnd, int newStart, int newEnd) {
            this.oldStart = oldStart;
            this.oldEnd = oldEnd;
            this.newStart = newStart;
            this.newEnd = newEnd;
        }

        /**
         * Returns true iff the change touches the given range of lines (0-based, end exclusive) in the
         * new text. Pure deletions (empty range in the new text) belong to a stop that is adjacent.
         */
        boolean touchesNew(int start, int end) {
            if (this.newStart == this.newEnd || start == end) {
                return this.newStart <= end && this.newEnd >= start;
            }
            return this.newStart < end && this.newEnd > start;
        }

        @Override
        public String toString() {
            return "[" + this.oldStart + "," + this.oldEnd + ") -> [" + this.newStart + "," + this.newEnd + ")";
        }
    }

    private final String oldText;
    private final String newText;
    private final int[] oldLines;
    private final int[] newLines;

    private StopDiffExcerpt(String oldText, String newText, int[] oldLines, int[] newLines) {
        this.oldText = oldText;
        this.newText = newText;
        this.oldLines = oldLines;
        this.newLines = newLines;
    }

    /**
     * Creates the excerpt with the changes that touch the stop's range of lines in the new text
     * (0-based, end exclusive). Returns null if none of the changes belongs to the stop.
     */
    static StopDiffExcerpt create(String oldText, String newText, List<Change> changes,
            int stopStart, int stopEnd, int contextLines) {
        final String[] oldLines = splitLines(oldText);
        final String[] newLines = splitLines(newText);

        // determine the blocks (relevant changes with context), merging overlapping or adjacent ones
        final List<int[]> blocks = new ArrayList<>();
        for (final Change c : changes) {
            if (!c.touchesNew(stopStart, stopEnd)) {
                continue;
            }
            final int before = Math.min(contextLines, Math.min(c.oldStart, c.newStart));
            final int after = Math.min(contextLines,
                    Math.min(oldLines.length - c.oldEnd, newLines.length - c.newEnd));
            final int[] block = {c.oldStart - before, c.oldEnd + after, c.newStart - before, c.newEnd + after};
            final int[] last = blocks.isEmpty() ? null : blocks.get(blocks.size() - 1);
            if (last != null && (block[0] <= last[1] || block[2] <= last[3])) {
                last[1] = Math.max(last[1], block[1]);
                last[3] = Math.max(last[3], block[3]);
            } else {
                blocks.add(block);
            }
        }
        if (blocks.isEmpty()) {
            return null;
        }

        final StringBuilder oldExcerpt = new StringBuilder();
        final StringBuilder newExcerpt = new StringBuilder();
        final List<Integer> oldMap = new ArrayList<>();
        final List<Integer> newMap = new ArrayList<>();
        for (int i = 0; i < blocks.size(); i++) {
            final int[] block = blocks.get(i);
            if (i > 0 || block[0] > 0 || block[2] > 0) {
                appendSeparator(oldExcerpt, oldMap);
                appendSeparator(newExcerpt, newMap);
            }
            appendLines(oldExcerpt, oldMap, oldLines, block[0], block[1]);
            appendLines(newExcerpt, newMap, newLines, block[2], block[3]);
        }
        final int[] lastBlock = blocks.get(blocks.size() - 1);
        if (lastBlock[1] < oldLines.length || lastBlock[3] < newLines.length) {
            appendSeparator(oldExcerpt, oldMap);
            appendSeparator(newExcerpt, newMap);
        }
        return new StopDiffExcerpt(stripLastNewline(oldExcerpt), stripLastNewline(newExcerpt),
                toArray(oldMap), toArray(newMap));
    }

    private static String[] splitLines(String text) {
        if (text.isEmpty()) {
            return new String[0];
        }
        final String withoutTrailingNewline = text.endsWith("\n") ? text.substring(0, text.length() - 1) : text;
        return withoutTrailingNewline.split("\n", -1);
    }

    private static void appendSeparator(StringBuilder text, List<Integer> map) {
        text.append(SEPARATOR).append('\n');
        map.add(-1);
    }

    private static void appendLines(StringBuilder text, List<Integer> map, String[] lines, int start, int end) {
        for (int i = start; i < end; i++) {
            text.append(lines[i]).append('\n');
            map.add(i);
        }
    }

    private static String stripLastNewline(StringBuilder text) {
        return text.length() > 0 ? text.substring(0, text.length() - 1) : "";
    }

    private static int[] toArray(List<Integer> list) {
        final int[] ret = new int[list.size()];
        for (int i = 0; i < ret.length; i++) {
            ret[i] = list.get(i);
        }
        return ret;
    }

    String getOldText() {
        return this.oldText;
    }

    String getNewText() {
        return this.newText;
    }

    /**
     * Maps a (0-based) line of the old excerpt to the line in the old file (-1 for a separator).
     */
    int toOldLine(int excerptLine) {
        return excerptLine >= 0 && excerptLine < this.oldLines.length ? this.oldLines[excerptLine] : -1;
    }

    /**
     * Maps a (0-based) line of the new excerpt to the line in the new file (-1 for a separator).
     */
    int toNewLine(int excerptLine) {
        return excerptLine >= 0 && excerptLine < this.newLines.length ? this.newLines[excerptLine] : -1;
    }

    /**
     * Returns the (0-based) line in the new excerpt that shows the given line of the new file, or the
     * nearest line after it.
     */
    int toNewExcerptLine(int fileLine) {
        for (int i = 0; i < this.newLines.length; i++) {
            if (this.newLines[i] >= fileLine) {
                return i;
            }
        }
        return Math.max(0, this.newLines.length - 1);
    }

    @Override
    public String toString() {
        return Arrays.toString(this.oldLines) + " / " + Arrays.toString(this.newLines);
    }

}
