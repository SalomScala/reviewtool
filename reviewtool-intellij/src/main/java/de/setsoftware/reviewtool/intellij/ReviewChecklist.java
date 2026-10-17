package de.setsoftware.reviewtool.intellij;

import java.nio.file.FileSystems;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import de.setsoftware.reviewtool.base.Logger;

/**
 * The review checklist of a project (".cort/review-checklist.md"): questions per path pattern, e.g.
 * <pre>
 * ## **&#47;persistence/**, *.sql
 * - Are all SQL statements parameterized?
 * - Is every transaction closed, also in case of an error?
 * </pre>
 * The headings contain glob patterns (comma separated); a pattern without "/" applies to the file name, the
 * others to the path relative to the project. The list items below a heading are its questions. Short,
 * specific checklists helped in experiments, in particular with complex changes (Wurzel Gonçalves et al., EMSE
 * 2022); they are shown with the stops of the matching files.
 */
final class ReviewChecklist {

    /** The file of the checklist, relative to the project directory. */
    static final String FILE = ".cort/review-checklist.md";

    /**
     * The questions for the files that match one of the patterns.
     */
    private static final class Section {
        private final List<PathMatcher> pathMatchers = new ArrayList<>();
        private final List<PathMatcher> nameMatchers = new ArrayList<>();
        private final List<String> questions = new ArrayList<>();

        boolean matches(Path relativePath) {
            for (final PathMatcher m : this.pathMatchers) {
                if (m.matches(relativePath)) {
                    return true;
                }
            }
            final Path name = relativePath.getFileName();
            for (final PathMatcher m : this.nameMatchers) {
                if (name != null && m.matches(name)) {
                    return true;
                }
            }
            return false;
        }
    }

    private final List<Section> sections;

    private ReviewChecklist(List<Section> sections) {
        this.sections = sections;
    }

    /**
     * Parses the checklist (lines that are neither headings nor list items are ignored).
     */
    static ReviewChecklist parse(String text) {
        final List<Section> sections = new ArrayList<>();
        Section current = null;
        for (final String rawLine : text.split("\r?\n")) {
            final String line = rawLine.trim();
            if (line.startsWith("#")) {
                current = new Section();
                sections.add(current);
                for (final String pattern : line.replaceFirst("^#+", "").split(",")) {
                    addPattern(current, pattern.trim().replace("`", ""));
                }
            } else if (current != null && (line.startsWith("- ") || line.startsWith("* "))) {
                final String question = line.substring(2).replaceFirst("^\\[[ xX]\\]\\s*", "").trim();
                if (!question.isEmpty()) {
                    current.questions.add(question);
                }
            }
        }
        return new ReviewChecklist(sections);
    }

    private static void addPattern(Section section, String pattern) {
        if (pattern.isEmpty()) {
            return;
        }
        try {
            final PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
            if (pattern.contains("/")) {
                section.pathMatchers.add(matcher);
                // "**/x/**" shall also match "x/..." directly in the project directory
                if (pattern.startsWith("**/")) {
                    section.pathMatchers.add(FileSystems.getDefault().getPathMatcher("glob:" + pattern.substring(3)));
                }
            } else {
                section.nameMatchers.add(matcher);
            }
        } catch (final IllegalArgumentException e) {
            // also a PatternSyntaxException
            Logger.debug("invalid pattern in the review checklist: " + pattern);
        }
    }

    /**
     * The questions for the file with the given path (relative to the project, with "/" or "\"), in the order
     * of the checklist and without duplicates.
     */
    List<String> questionsFor(String relativePath) {
        final Set<String> ret = new LinkedHashSet<>();
        final Path path;
        try {
            path = Paths.get(relativePath.replace('\\', '/'));
        } catch (final InvalidPathException e) {
            return new ArrayList<>();
        }
        for (final Section section : this.sections) {
            if (section.matches(path)) {
                ret.addAll(section.questions);
            }
        }
        return new ArrayList<>(ret);
    }

    boolean isEmpty() {
        for (final Section section : this.sections) {
            if (!section.questions.isEmpty()) {
                return false;
            }
        }
        return true;
    }

}
