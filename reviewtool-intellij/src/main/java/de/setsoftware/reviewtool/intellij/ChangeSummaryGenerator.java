package de.setsoftware.reviewtool.intellij;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
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
 * Generates a human readable summary of the changes under review. This is a lightweight,
 * platform-independent counterpart of the Eclipse review content summary view: it groups the
 * changes by file, shows the added/removed line counts and (for Java files) lists the changed
 * types and methods, determined by mapping the changed line ranges onto the declarations found by
 * {@link JavaParser}. The heavier refactoring-detection and delta-doc techniques of the Eclipse
 * summary (which rely on Eclipse JDT and external libraries) are not reproduced here.
 */
public final class ChangeSummaryGenerator {

    /**
     * Per-file accumulator.
     */
    private static final class FileSummary {
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
     * Builds the summary text for the given tours.
     */
    public static String generate(ToursInReview tours) {
        if (tours == null) {
            return "No tours created yet. Use \"Create Tours\" to build the review tours first.";
        }

        final TreeMap<File, FileSummary> byFile = new TreeMap<>();
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

        final StringBuilder ret = new StringBuilder();
        ret.append("=== Review Summary ===\n\n");
        ret.append("Tours: ").append(tours.getTopmostTours().size()).append('\n');
        ret.append("Stops: ").append(stopCount)
                .append(" (relevant ").append(stopCount - irrelevantCount)
                .append(", irrelevant ").append(irrelevantCount).append(")\n");
        ret.append("Files changed: ").append(byFile.size());
        int totalAdded = 0;
        int totalRemoved = 0;
        for (final FileSummary fs : byFile.values()) {
            totalAdded += fs.added;
            totalRemoved += fs.removed;
        }
        ret.append("  (+").append(totalAdded).append(" / -").append(totalRemoved).append(" lines)\n\n");

        ret.append("Changed files:\n");
        for (final java.util.Map.Entry<File, FileSummary> e : byFile.entrySet()) {
            final FileSummary fs = e.getValue();
            ret.append("  ").append(e.getKey().getPath());
            if (fs.binary) {
                ret.append("  (binary)\n");
                continue;
            }
            ret.append("  (+").append(fs.added).append(" / -").append(fs.removed).append(")\n");
            if (fs.java && fs.contents != null) {
                appendJavaParts(ret, fs);
            }
        }
        return ret.toString();
    }

    private static void collectStop(TreeMap<File, FileSummary> byFile, Stop stop) {
        final File file = stop.getAbsoluteFile();
        FileSummary fs = byFile.get(file);
        if (fs == null) {
            fs = new FileSummary();
            fs.binary = stop.isBinaryChange();
            fs.java = file.getName().endsWith(".java");
            if (fs.java && fs.contents == null) {
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

    private static void appendJavaParts(StringBuilder ret, FileSummary fs) {
        if (fs.changedRanges.isEmpty()) {
            return;
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
                    ret.append("     ").append(type.getNameAsString()).append(": ")
                            .append(String.join(", ", changedMethods)).append('\n');
                } else if (overlaps(beginLine(type), endLine(type), fs.changedRanges)) {
                    ret.append("     ").append(type.getNameAsString())
                            .append(" (declaration / non-method change)\n");
                }
            }
        } catch (final Exception e) {
            //a parse problem should not break the whole summary
            Logger.info("could not parse Java file for summary: " + e);
        }
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
