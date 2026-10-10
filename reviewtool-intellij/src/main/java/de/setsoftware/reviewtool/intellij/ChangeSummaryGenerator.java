package de.setsoftware.reviewtool.intellij;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
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
import de.setsoftware.reviewtool.model.api.IRevisionedFile;
import de.setsoftware.reviewtool.model.changestructure.Stop;
import de.setsoftware.reviewtool.model.changestructure.Tour;
import de.setsoftware.reviewtool.model.changestructure.ToursInReview;

/**
 * Builds a structured summary of the changes under review. This is a lightweight,
 * platform-independent counterpart of the Eclipse review content summary view: it groups the
 * changes by file, computes the added/removed line counts and (for Java files) determines the
 * changed types and methods by mapping the changed line ranges onto the declarations found by
 * {@link JavaParser}. Additionally, refactorings (renamed/moved classes and methods, changed
 * signatures, extracted/inlined methods) are detected with the {@link RefactoringDetector}, the
 * counterpart of the RefDiff technique of the Eclipse summary. The delta-doc technique of the Eclipse
 * summary is not reproduced here.
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
        private final List<RefactoringDetector.Refactoring> refactorings;

        SummaryResult(int tourCount, int stopCount, int irrelevantCount,
                int totalAdded, int totalRemoved, List<FileItem> files,
                List<RefactoringDetector.Refactoring> refactorings) {
            this.tourCount = tourCount;
            this.stopCount = stopCount;
            this.irrelevantCount = irrelevantCount;
            this.totalAdded = totalAdded;
            this.totalRemoved = totalRemoved;
            this.files = files;
            this.refactorings = refactorings;
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

        /**
         * The refactorings detected in the changed Java files.
         */
        public List<RefactoringDetector.Refactoring> getRefactorings() {
            return this.refactorings;
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
        private String path;
        private IRevisionedFile oldestRevision;
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
        final TreeMap<File, FileAccumulator> allFiles = new TreeMap<>();
        int stopCount = 0;
        int irrelevantCount = 0;
        for (final Tour tour : tours.getTopmostTours()) {
            for (final Stop stop : tour.getStops()) {
                stopCount++;
                // refactorings are also detected in irrelevant stops (e.g. a moved class in an "ignore" category)
                collectStop(allFiles, stop);
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
                tours.getTopmostTours().size(), stopCount, irrelevantCount, totalAdded, totalRemoved, files,
                detectRefactorings(allFiles));
    }

    private static List<RefactoringDetector.Refactoring> detectRefactorings(Map<File, FileAccumulator> files) {
        final List<RefactoringDetector.FileVersions> versions = new ArrayList<>();
        for (final FileAccumulator fs : files.values()) {
            if (fs.java && !fs.binary) {
                versions.add(new RefactoringDetector.FileVersions(
                        fs.path, readContents(fs.oldestRevision), toText(fs.contents)));
            }
        }
        try {
            return RefactoringDetector.detect(versions);
        } catch (final RuntimeException e) {
            //the refactoring detection is optional and should not break the whole summary
            Logger.warn("could not detect refactorings", e);
            return new ArrayList<>();
        }
    }

    private static String readContents(IRevisionedFile file) {
        if (file == null) {
            return "";
        }
        try {
            return toText(file.getContents());
        } catch (final Exception e) {
            //e.g. a file that did not exist before
            return "";
        }
    }

    private static String toText(byte[] contents) {
        return contents == null ? "" : new String(contents, StandardCharsets.UTF_8);
    }

    private static void collectStop(TreeMap<File, FileAccumulator> byFile, Stop stop) {
        final File file = stop.getAbsoluteFile();
        FileAccumulator fs = byFile.get(file);
        if (fs == null) {
            fs = new FileAccumulator();
            fs.binary = stop.isBinaryChange();
            fs.java = file.getName().endsWith(".java");
            fs.path = file.getPath();
            fs.oldestRevision = stop.getHistory().isEmpty() ? null : stop.getHistory().keySet().iterator().next();
            if (fs.java) {
                try {
                    fs.contents = stop.getMostRecentFile().getContents();
                } catch (final Exception e) {
                    Logger.info("could not read contents of " + file + ": " + e);
                }
            }
            byFile.put(file, fs);
        }
        final int added = stop.getNumberOfAddedLines();
        final int removed = stop.getNumberOfRemovedLines();
        if (added == 0 && removed == 0 && isChangeWithinLine(stop)) {
            // like in Git, a change within a line counts as one changed (removed and added) line
            fs.added++;
            fs.removed++;
        } else {
            fs.added += added;
            fs.removed += removed;
        }
        if (!stop.isBinaryChange() && stop.isDetailedFragmentKnown()) {
            final IFragment fragment = stop.getMostRecentFragment();
            if (fragment != null) {
                fs.changedRanges.add(new int[] {fragment.getFrom().getLine(), fragment.getTo().getLine()});
            }
        }
    }

    private static boolean isChangeWithinLine(Stop stop) {
        if (stop.isBinaryChange() || !stop.isDetailedFragmentKnown()) {
            return false;
        }
        final IFragment fragment = stop.getMostRecentFragment();
        return fragment != null && fragment.isInline() && fragment.getTo().getColumn() > fragment.getFrom().getColumn();
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
