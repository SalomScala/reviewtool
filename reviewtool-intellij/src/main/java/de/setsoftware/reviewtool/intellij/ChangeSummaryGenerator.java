package de.setsoftware.reviewtool.intellij;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;

import de.setsoftware.reviewtool.base.Logger;
import de.setsoftware.reviewtool.model.api.IFragment;
import de.setsoftware.reviewtool.model.changestructure.Stop;
import de.setsoftware.reviewtool.model.changestructure.Tour;
import de.setsoftware.reviewtool.model.changestructure.ToursInReview;

/**
 * Builds a structured summary of the changes under review. This is a lightweight,
 * platform-independent counterpart of the Eclipse review content summary view: it groups the
 * changes by file, computes the added/removed line counts and (for Java files) determines the
 * changed types and methods by mapping the changed line ranges onto the declarations found by
 * {@link JavaParser}. The heavier refactoring-detection and delta-doc techniques of the Eclipse
 * summary (which rely on Eclipse JDT and external libraries) are not reproduced here.
 */
public final class ChangeSummaryGenerator {

    /**
     * Summary for a single file.
     */
    public static final class FileItem {
        private final String path;
        private final int added;
        private final int removed;
        private final boolean binary;
        private final List<String> parts;

        FileItem(String path, int added, int removed, boolean binary, List<String> parts) {
            this.path = path;
            this.added = added;
            this.removed = removed;
            this.binary = binary;
            this.parts = parts;
        }

        public String getPath() {
            return this.path;
        }

        public int getAdded() {
            return this.added;
        }

        public int getRemoved() {
            return this.removed;
        }

        public boolean isBinary() {
            return this.binary;
        }

        /**
         * The changed types/methods of the file (only for Java files), as readable strings.
         */
        public List<String> getParts() {
            return this.parts;
        }
    }

    /**
     * The whole summary.
     */
    public static final class SummaryResult {
        private final int tourCount;
        private final int stopCount;
        private final int irrelevantCount;
        private final int totalAdded;
        private final int totalRemoved;
        private final List<FileItem> files;

        SummaryResult(int tourCount, int stopCount, int irrelevantCount,
                int totalAdded, int totalRemoved, List<FileItem> files) {
            this.tourCount = tourCount;
            this.stopCount = stopCount;
            this.irrelevantCount = irrelevantCount;
            this.totalAdded = totalAdded;
            this.totalRemoved = totalRemoved;
            this.files = files;
        }

        public int getTourCount() {
            return this.tourCount;
        }

        public int getStopCount() {
            return this.stopCount;
        }

        public int getRelevantCount() {
            return this.stopCount - this.irrelevantCount;
        }

        public int getIrrelevantCount() {
            return this.irrelevantCount;
        }

        public int getTotalAdded() {
            return this.totalAdded;
        }

        public int getTotalRemoved() {
            return this.totalRemoved;
        }

        public List<FileItem> getFiles() {
            return this.files;
        }
    }

    /**
     * Per-file accumulator used while building the result.
     */
    private static final class FileAccumulator {
        private int added;
        private int removed;
        private boolean binary;
        private boolean java;
        private final List<int[]> changedRanges = new ArrayList<>();
        private byte[] contents;
    }

    private ChangeSummaryGenerator() {
    }

    /**
     * Analyzes the given tours and returns the structured summary, or null if there are no tours.
     */
    public static SummaryResult analyze(ToursInReview tours) {
        if (tours == null) {
            return null;
        }

        final TreeMap<File, FileAccumulator> byFile = new TreeMap<>();
        int stopCount = 0;
        int irrelevantCount = 0;
        for (final Tour tour : tours.getTopmostTours()) {
            for (final Stop stop : tour.getStops()) {
                stopCount++;
                if (stop.isIrrelevantForReview(tours.getIrrelevantCategories())) {
                    irrelevantCount++;
                    continue;
                }
                collectStop(byFile, stop);
            }
        }

        int totalAdded = 0;
        int totalRemoved = 0;
        final List<FileItem> files = new ArrayList<>();
        for (final Map.Entry<File, FileAccumulator> e : byFile.entrySet()) {
            final FileAccumulator fs = e.getValue();
            totalAdded += fs.added;
            totalRemoved += fs.removed;
            final List<String> parts = fs.java && fs.contents != null
                    ? determineJavaParts(fs)
                    : new ArrayList<>();
            files.add(new FileItem(e.getKey().getPath(), fs.added, fs.removed, fs.binary, parts));
        }
        return new SummaryResult(
                tours.getTopmostTours().size(), stopCount, irrelevantCount, totalAdded, totalRemoved, files);
    }

    private static void collectStop(TreeMap<File, FileAccumulator> byFile, Stop stop) {
        final File file = stop.getAbsoluteFile();
        FileAccumulator fs = byFile.get(file);
        if (fs == null) {
            fs = new FileAccumulator();
            fs.binary = stop.isBinaryChange();
            fs.java = file.getName().endsWith(".java");
            if (fs.java) {
                try {
                    fs.contents = stop.getMostRecentFile().getContents();
                } catch (final Exception e) {
                    Logger.info("could not read contents of " + file + ": " + e);
                }
            }
            byFile.put(file, fs);
        }
        fs.added += stop.getNumberOfAddedLines();
        fs.removed += stop.getNumberOfRemovedLines();
        if (!stop.isBinaryChange() && stop.isDetailedFragmentKnown()) {
            final IFragment fragment = stop.getMostRecentFragment();
            if (fragment != null) {
                fs.changedRanges.add(new int[] {fragment.getFrom().getLine(), fragment.getTo().getLine()});
            }
        }
    }

    private static List<String> determineJavaParts(FileAccumulator fs) {
        final List<String> parts = new ArrayList<>();
        if (fs.changedRanges.isEmpty()) {
            return parts;
        }
        try {
            final CompilationUnit cu = JavaParser.parse(new ByteArrayInputStream(fs.contents));
            for (final TypeDeclaration<?> type : cu.getTypes()) {
                final List<String> changedMethods = new ArrayList<>();
                for (final MethodDeclaration method : type.findAll(MethodDeclaration.class)) {
                    if (overlaps(beginLine(method), endLine(method), fs.changedRanges)) {
                        changedMethods.add(method.getNameAsString());
                    }
                }
                if (!changedMethods.isEmpty()) {
                    parts.add(type.getNameAsString() + ": " + String.join(", ", changedMethods));
                } else if (overlaps(beginLine(type), endLine(type), fs.changedRanges)) {
                    parts.add(type.getNameAsString() + " (declaration / non-method change)");
                }
            }
        } catch (final Exception e) {
            //a parse problem should not break the whole summary
            Logger.info("could not parse Java file for summary: " + e);
        }
        return parts;
    }

    private static int beginLine(com.github.javaparser.ast.Node node) {
        return node.getBegin().isPresent() ? node.getBegin().get().line : -1;
    }

    private static int endLine(com.github.javaparser.ast.Node node) {
        return node.getEnd().isPresent() ? node.getEnd().get().line : -1;
    }

    private static boolean overlaps(int begin, int end, List<int[]> ranges) {
        if (begin < 0 || end < 0) {
            return false;
        }
        for (final int[] range : ranges) {
            if (begin <= range[1] && range[0] <= end) {
                return true;
            }
        }
        return false;
    }

}
