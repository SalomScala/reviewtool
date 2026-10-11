package de.setsoftware.reviewtool.intellij;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;

import de.setsoftware.reviewtool.base.Logger;
import de.setsoftware.reviewtool.model.api.IFragment;
import de.setsoftware.reviewtool.model.changestructure.Stop;
import de.setsoftware.reviewtool.model.changestructure.Tour;
import de.setsoftware.reviewtool.model.changestructure.ToursInReview;
import de.setsoftware.reviewtool.model.viewtracking.ViewStatistics;
import de.setsoftware.reviewtool.model.viewtracking.ViewStatisticsForFile;

/**
 * The progress of a review that is kept over a restart of the IDE: the choices made when the tours
 * were created (so that the same tours can be created again without asking), how often the lines of
 * the files have been viewed and which stops have been marked as checked. It belongs to one review
 * round of a ticket; the progress of another round is not used.
 */
final class ReviewProgress {

    /**
     * A stop that has been marked as checked, identified by its file and its lines.
     */
    static final class StopRef {
        private final String file;
        private final int fromLine;
        private final int toLine;

        StopRef(String file, int fromLine, int toLine) {
            this.file = file;
            this.fromLine = fromLine;
            this.toLine = toLine;
        }

        static StopRef of(Stop stop) {
            final IFragment fragment = stop.isDetailedFragmentKnown() ? stop.getMostRecentFragment() : null;
            return new StopRef(stop.getAbsoluteFile().getPath(),
                    fragment == null ? -1 : fragment.getFrom().getLine(),
                    fragment == null ? -1 : fragment.getTo().getLine());
        }

        boolean matches(Stop stop) {
            final StopRef other = of(stop);
            return this.file.equals(other.file) && this.fromLine == other.fromLine && this.toLine == other.toLine;
        }
    }

    /**
     * The view statistics of one file.
     */
    static final class FileStats {
        private final int unspecificCount;
        private final Map<Integer, Integer> countsPerLine;

        FileStats(int unspecificCount, Map<Integer, Integer> countsPerLine) {
            this.unspecificCount = unspecificCount;
            this.countsPerLine = countsPerLine;
        }
    }

    private static final String FORMAT_VERSION = "1";

    private final int round;
    private final String tourStructure;
    private final Set<String> irrelevantClassifications;
    private final Map<String, FileStats> files;
    private final List<StopRef> checkedStops;

    ReviewProgress(int round, String tourStructure, Set<String> irrelevantClassifications,
            Map<String, FileStats> files, List<StopRef> checkedStops) {
        this.round = round;
        this.tourStructure = tourStructure;
        this.irrelevantClassifications = irrelevantClassifications;
        this.files = files;
        this.checkedStops = checkedStops;
    }

    /**
     * Captures the current progress. Only the statistics of files that belong to the tours are kept.
     *
     * @param tourStructure The name of the tour structure that was chosen (or null).
     * @param irrelevantClassifications The names of the classifications that were marked as
     *      irrelevant (or null if unknown).
     */
    static ReviewProgress capture(int round, String tourStructure, Set<String> irrelevantClassifications,
            ViewStatistics statistics, ToursInReview tours) {
        final Set<String> tourFiles = new LinkedHashSet<>();
        for (final Stop stop : allStops(tours)) {
            tourFiles.add(stop.getAbsoluteFile().getPath());
        }
        final Map<String, FileStats> files = new LinkedHashMap<>();
        for (final Entry<File, ViewStatisticsForFile> e : statistics.getStatisticsPerFile().entrySet()) {
            final String path = e.getKey().getPath();
            if (tourFiles.contains(path)) {
                files.put(path, new FileStats(
                        e.getValue().getUnspecificCount(), new TreeMap<>(e.getValue().getCountsPerLine())));
            }
        }
        final List<StopRef> checked = new ArrayList<>();
        for (final Stop stop : allStops(tours)) {
            if (statistics.isMarkedAsChecked(stop)) {
                checked.add(StopRef.of(stop));
            }
        }
        return new ReviewProgress(round, tourStructure, irrelevantClassifications, files, checked);
    }

    /**
     * Replaces the given statistics with the saved progress and marks the saved checked stops of the
     * tours as checked.
     *
     * @return The number of stops that have been marked as checked.
     */
    int applyTo(ViewStatistics statistics, ToursInReview tours) {
        statistics.clear();
        for (final Entry<String, FileStats> e : this.files.entrySet()) {
            statistics.restore(new File(e.getKey()), e.getValue().unspecificCount, e.getValue().countsPerLine);
        }
        final List<Stop> toCheck = new ArrayList<>();
        for (final Stop stop : allStops(tours)) {
            for (final StopRef ref : this.checkedStops) {
                if (ref.matches(stop)) {
                    toCheck.add(stop);
                    break;
                }
            }
        }
        statistics.markAsChecked(toCheck);
        return toCheck.size();
    }

    private static List<Stop> allStops(ToursInReview tours) {
        final List<Stop> ret = new ArrayList<>();
        if (tours != null) {
            for (final Tour tour : tours.getTopmostTours()) {
                ret.addAll(tour.getStops());
            }
        }
        return ret;
    }

    int getRound() {
        return this.round;
    }

    /**
     * The name of the tour structure that was chosen (null if unknown).
     */
    String getTourStructure() {
        return this.tourStructure;
    }

    /**
     * The names of the classifications that were marked as irrelevant (null if unknown).
     */
    Set<String> getIrrelevantClassifications() {
        return this.irrelevantClassifications;
    }

    int getCheckedStopCount() {
        return this.checkedStops.size();
    }

    boolean hasViews() {
        return !this.files.isEmpty();
    }

    /**
     * Saves the progress to the given file (atomically, so that a crash does not leave a broken file).
     */
    void save(Path file) throws IOException {
        final Properties p = new Properties();
        p.setProperty("version", FORMAT_VERSION);
        p.setProperty("round", Integer.toString(this.round));
        if (this.tourStructure != null) {
            p.setProperty("tourStructure", this.tourStructure);
        }
        if (this.irrelevantClassifications != null) {
            p.setProperty("irrelevant.count", Integer.toString(this.irrelevantClassifications.size()));
            int i = 0;
            for (final String name : this.irrelevantClassifications) {
                p.setProperty("irrelevant." + i++, name);
            }
        }
        int i = 0;
        for (final Entry<String, FileStats> e : this.files.entrySet()) {
            p.setProperty("file." + i + ".path", e.getKey());
            p.setProperty("file." + i + ".unspecific", Integer.toString(e.getValue().unspecificCount));
            p.setProperty("file." + i + ".lines", encodeCounts(e.getValue().countsPerLine));
            i++;
        }
        p.setProperty("file.count", Integer.toString(i));
        p.setProperty("checked.count", Integer.toString(this.checkedStops.size()));
        for (int j = 0; j < this.checkedStops.size(); j++) {
            final StopRef ref = this.checkedStops.get(j);
            p.setProperty("checked." + j + ".path", ref.file);
            p.setProperty("checked." + j + ".lines", ref.fromLine + "-" + ref.toLine);
        }
        Files.createDirectories(file.getParent());
        final Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (OutputStream out = Files.newOutputStream(tmp)) {
            p.store(out, "CoRT review progress");
        }
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (final AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Loads the progress from the given file. Returns null if there is no (usable) saved progress.
     */
    static ReviewProgress load(Path file) {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        final Properties p = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            p.load(in);
            if (!FORMAT_VERSION.equals(p.getProperty("version"))) {
                return null;
            }
            final int round = Integer.parseInt(p.getProperty("round"));
            Set<String> irrelevant = null;
            if (p.getProperty("irrelevant.count") != null) {
                irrelevant = new LinkedHashSet<>();
                final int count = Integer.parseInt(p.getProperty("irrelevant.count"));
                for (int i = 0; i < count; i++) {
                    irrelevant.add(p.getProperty("irrelevant." + i));
                }
            }
            final Map<String, FileStats> files = new LinkedHashMap<>();
            final int fileCount = Integer.parseInt(p.getProperty("file.count", "0"));
            for (int i = 0; i < fileCount; i++) {
                files.put(p.getProperty("file." + i + ".path"), new FileStats(
                        Integer.parseInt(p.getProperty("file." + i + ".unspecific", "0")),
                        decodeCounts(p.getProperty("file." + i + ".lines", ""))));
            }
            final List<StopRef> checked = new ArrayList<>();
            final int checkedCount = Integer.parseInt(p.getProperty("checked.count", "0"));
            for (int i = 0; i < checkedCount; i++) {
                final String[] lines = p.getProperty("checked." + i + ".lines").split("(?<=\\d)-", 2);
                checked.add(new StopRef(p.getProperty("checked." + i + ".path"),
                        Integer.parseInt(lines[0]), Integer.parseInt(lines[1])));
            }
            return new ReviewProgress(round, p.getProperty("tourStructure"), irrelevant, files, checked);
        } catch (final IOException | RuntimeException e) {
            Logger.warn("could not read the saved review progress " + file, e);
            return null;
        }
    }

    /**
     * Encodes the view counts compactly: consecutive lines with the same count become one range,
     * e.g. "3-5:2,8:1".
     */
    static String encodeCounts(Map<Integer, Integer> counts) {
        final StringBuilder sb = new StringBuilder();
        int rangeStart = -1;
        int rangeEnd = -1;
        int rangeCount = -1;
        for (final Entry<Integer, Integer> e : new TreeMap<>(counts).entrySet()) {
            if (e.getKey() == rangeEnd + 1 && e.getValue() == rangeCount) {
                rangeEnd = e.getKey();
                continue;
            }
            appendRange(sb, rangeStart, rangeEnd, rangeCount);
            rangeStart = e.getKey();
            rangeEnd = e.getKey();
            rangeCount = e.getValue();
        }
        appendRange(sb, rangeStart, rangeEnd, rangeCount);
        return sb.toString();
    }

    private static void appendRange(StringBuilder sb, int start, int end, int count) {
        if (start < 0) {
            return;
        }
        if (sb.length() > 0) {
            sb.append(',');
        }
        sb.append(start);
        if (end != start) {
            sb.append('-').append(end);
        }
        sb.append(':').append(count);
    }

    static Map<Integer, Integer> decodeCounts(String encoded) {
        final Map<Integer, Integer> ret = new TreeMap<>();
        if (encoded.isEmpty()) {
            return ret;
        }
        for (final String part : encoded.split(",")) {
            final String[] rangeAndCount = part.split(":");
            final String[] range = rangeAndCount[0].split("-");
            final int start = Integer.parseInt(range[0]);
            final int end = range.length > 1 ? Integer.parseInt(range[1]) : start;
            final int count = Integer.parseInt(rangeAndCount[1]);
            for (int line = start; line <= end; line++) {
                ret.put(line, count);
            }
        }
        return Collections.unmodifiableMap(ret);
    }

}
