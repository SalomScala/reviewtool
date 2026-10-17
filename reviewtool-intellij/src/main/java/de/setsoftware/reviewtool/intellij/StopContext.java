package de.setsoftware.reviewtool.intellij;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.ToIntFunction;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.search.GlobalSearchScopesCore;
import com.intellij.psi.search.PsiSearchHelper;

import de.setsoftware.reviewtool.base.Logger;
import de.setsoftware.reviewtool.model.api.IClassification;
import de.setsoftware.reviewtool.model.changestructure.IStopOrdering;
import de.setsoftware.reviewtool.model.changestructure.Stop;
import de.setsoftware.reviewtool.model.changestructure.Tour;
import de.setsoftware.reviewtool.model.changestructure.TourElement;
import de.setsoftware.reviewtool.model.changestructure.ToursInReview;
import de.setsoftware.reviewtool.ordering.efficientalgorithm.TourCalculatorControl;

/**
 * The context of the stops of a review (see {@link StopAnalysis}) and the tests outside of the review
 * that use the changed types. Reviewers need to know how the changed code is used and tested, and why a
 * stop is where it is; changes that are "just" refactorings need less attention.
 */
final class StopContext {

    /** At most this many tests outside of the review are listed per type. */
    private static final int MAX_TESTS_OUTSIDE = 5;

    private final ToursInReview tours;
    private final Map<Stop, StopAnalysis.Info> infos;
    /** Type name -> the tests outside of the review that use it (absent if unknown). */
    private final Map<String, List<String>> testsOutsideReview;
    private final ReviewChecklist checklist;
    private final String projectDir;

    private StopContext(ToursInReview tours, Map<Stop, StopAnalysis.Info> infos,
            Map<String, List<String>> testsOutsideReview, ReviewChecklist checklist, String projectDir) {
        this.tours = tours;
        this.infos = infos;
        this.testsOutsideReview = testsOutsideReview;
        this.checklist = checklist;
        this.projectDir = projectDir;
    }

    /**
     * The questions of the project's review checklist for the file of the stop (empty if there are none).
     */
    List<String> checklistFor(Stop stop) {
        if (this.checklist == null || this.projectDir == null) {
            return Collections.emptyList();
        }
        final String path = normalize(stop.getAbsoluteFile().getPath());
        final String base = normalize(this.projectDir);
        return this.checklist.questionsFor(path.startsWith(base + "/") ? path.substring(base.length() + 1) : path);
    }

    /**
     * Reads the review checklist of the project, or returns null if there is none.
     */
    private static ReviewChecklist loadChecklist(String projectDir) {
        if (projectDir == null) {
            return null;
        }
        final Path file = Paths.get(projectDir, ReviewChecklist.FILE);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            final ReviewChecklist ret =
                    ReviewChecklist.parse(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
            return ret.isEmpty() ? null : ret;
        } catch (final IOException e) {
            Logger.warn("could not read the review checklist " + file, e);
            return null;
        }
    }

    ToursInReview getTours() {
        return this.tours;
    }

    /**
     * The analysis of the stop, or null if it is unknown (e.g. a binary change).
     */
    StopAnalysis.Info get(Stop stop) {
        return this.infos.get(stop);
    }

    /**
     * The paths of the tests outside of the review that use the given type, or null if unknown (e.g. while
     * the IDE was indexing).
     */
    List<String> getTestsOutsideReview(String typeName) {
        return this.testsOutsideReview.get(typeName);
    }

    /**
     * The tests of a changed file: for a test, the types it tests; for production code, the changed tests of
     * the review that use it and the tests outside of the review that use it.
     */
    static final class FileTests {
        private final String path;
        private final boolean test;
        private final Set<String> tested = new LinkedHashSet<>();
        private final Set<String> testsInReview = new LinkedHashSet<>();
        private final Set<String> testsOutside = new LinkedHashSet<>();
        private final Set<String> types = new LinkedHashSet<>();
        private boolean outsideKnown = true;

        FileTests(String path, boolean test) {
            this.path = path;
            this.test = test;
        }

        String getPath() {
            return this.path;
        }

        boolean isTest() {
            return this.test;
        }

        /** For a test: the names of the tested types of the review. */
        Set<String> getTested() {
            return this.tested;
        }

        /** For production code: the names of the changed tests of the review that use it. */
        Set<String> getTestsInReview() {
            return this.testsInReview;
        }

        /** For production code: the paths of the tests outside of the review that use its types. */
        Set<String> getTestsOutside() {
            return this.testsOutside;
        }

        /** The types declared in the file. */
        Set<String> getTypes() {
            return this.types;
        }

        /** False if the tests outside of the review could not be searched (e.g. while indexing). */
        boolean isOutsideKnown() {
            return this.outsideKnown;
        }
    }

    /**
     * An independent part of the changes (see {@link StopAnalysis.Info#getPart()}).
     */
    static final class Part {
        private final List<Stop> stops = new ArrayList<>();
        private final Set<String> names = new LinkedHashSet<>();
        private boolean trivial = true;
        private int changedLines;

        List<Stop> getStops() {
            return this.stops;
        }

        /** The names of the types (or files) of the part. */
        Set<String> getNames() {
            return this.names;
        }

        /**
         * True if the part only consists of refactorings and changes that have been classified automatically
         * (e.g. formatting, imports, binary files).
         */
        boolean isTrivial() {
            return this.trivial;
        }

        /** The number of removed and added lines of the part. */
        int getChangedLines() {
            return this.changedLines;
        }

        /** The names, at most three, e.g. "Statistics, StatisticsTest, Main and 2 more". */
        String describe() {
            final List<String> list = new ArrayList<>(this.names);
            if (list.size() <= 3) {
                return String.join(", ", list);
            }
            return String.join(", ", list.subList(0, 3)) + " and " + (list.size() - 3) + " more";
        }
    }

    /**
     * Groups the given stops into their independent parts, in the order of the first stop of each part. Stops
     * without an analysis (e.g. binary changes) form a part per file.
     */
    static List<Part> parts(List<Stop> stops, Map<Stop, StopAnalysis.Info> infos) {
        final Map<String, Part> parts = new LinkedHashMap<>();
        for (final Stop stop : stops) {
            final StopAnalysis.Info info = infos.get(stop);
            final String key = info == null ? "file:" + stop.getAbsoluteFile().getPath() : "part:" + info.getPart();
            final Part part = parts.computeIfAbsent(key, (k) -> new Part());
            part.stops.add(stop);
            part.changedLines += info == null ? 0 : info.getChangedLineCount();
            if (info != null && !info.getTypeNames().isEmpty()) {
                part.names.addAll(info.getTypeNames());
            } else {
                part.names.add(stop.getAbsoluteFile().getName());
            }
            final boolean trivial = (info != null && info.isRefactoringOnly()) || stop.getClassification().length > 0;
            part.trivial &= trivial;
        }
        return new ArrayList<>(parts.values());
    }

    /**
     * The independent parts of the stops of the tours.
     */
    List<Part> parts() {
        final List<Stop> stops = new ArrayList<>();
        for (final Tour tour : this.tours.getTopmostTours()) {
            stops.addAll(tour.getStops());
        }
        return parts(stops, this.infos);
    }

    /**
     * The tests per changed file (only files with Java types), in the order of the stops.
     */
    List<FileTests> testsPerFile() {
        final Map<String, FileTests> ret = new LinkedHashMap<>();
        for (final Tour tour : this.tours.getTopmostTours()) {
            for (final Stop stop : tour.getStops()) {
                final StopAnalysis.Info info = this.infos.get(stop);
                if (info == null || info.getTypeNames().isEmpty()) {
                    continue;
                }
                final FileTests file = ret.computeIfAbsent(stop.getAbsoluteFile().getPath(),
                        (p) -> new FileTests(p, info.isTest()));
                file.types.addAll(info.getTypeNames());
                for (final StopAnalysis.Link link : info.getTested()) {
                    file.tested.add(link.getText());
                }
                for (final StopAnalysis.Link link : info.getTests()) {
                    file.testsInReview.add(link.getText());
                }
            }
        }
        for (final FileTests file : ret.values()) {
            if (file.test) {
                continue;
            }
            for (final String type : file.types) {
                final List<String> outside = this.testsOutsideReview.get(type);
                if (outside == null) {
                    file.outsideKnown = false;
                } else {
                    file.testsOutside.addAll(outside);
                }
            }
        }
        return new ArrayList<>(ret.values());
    }

    /**
     * Analyzes the stops of the given tours (the refactorings are detected per topmost tour) and searches the
     * tests outside of the review that use the changed types. Must not be called on the EDT.
     */
    static StopContext load(Project project, ToursInReview tours, BooleanSupplier canceled) {
        final List<List<Stop>> groups = new ArrayList<>();
        final Set<String> reviewedPaths = new HashSet<>();
        for (final Tour tour : tours.getTopmostTours()) {
            groups.add(tour.getStops());
            for (final Stop stop : tour.getStops()) {
                reviewedPaths.add(normalize(stop.getAbsoluteFile().getPath()));
            }
        }
        final Map<Stop, StopAnalysis.Info> infos = analyze(project, groups, canceled);
        final Set<String> productionTypes = new LinkedHashSet<>();
        for (final StopAnalysis.Info info : infos.values()) {
            if (!info.isTest()) {
                productionTypes.addAll(info.getTypeNames());
            }
        }
        final Map<String, List<String>> testsOutside = new HashMap<>();
        for (final String type : productionTypes) {
            if (canceled.getAsBoolean()) {
                break;
            }
            final List<String> tests = findTestsUsing(project, type, reviewedPaths);
            if (tests != null) {
                testsOutside.put(type, tests);
            }
        }
        return new StopContext(tours, infos, testsOutside, loadChecklist(project.getBasePath()), project.getBasePath());
    }

    /**
     * Analyzes the given stops (the refactorings are detected per group). Stops whose files cannot be loaded
     * are left out.
     */
    static Map<Stop, StopAnalysis.Info> analyze(Project project, List<List<Stop>> groups, BooleanSupplier canceled) {
        final List<StopAnalysis.Input> inputs = new ArrayList<>();
        for (int group = 0; group < groups.size(); group++) {
            for (final Stop stop : groups.get(group)) {
                if (canceled.getAsBoolean()) {
                    return Collections.emptyMap();
                }
                if (stop.isBinaryChange()) {
                    continue;
                }
                try {
                    final StopDiffViewer.StopDiffData data = StopDiffViewer.load(stop);
                    inputs.add(new StopAnalysis.Input(stop, group, stop.getAbsoluteFile().getPath(),
                            data.getOldText(), data.getNewText(), data.stopChanges(),
                            isTest(project, stop.getAbsoluteFile())));
                } catch (final Exception e) {
                    Logger.debug("could not load the texts of " + stop + " for the stop context: " + e);
                }
            }
        }
        final Map<Stop, StopAnalysis.Info> ret = new HashMap<>();
        for (final Map.Entry<Object, StopAnalysis.Info> e : StopAnalysis.analyze(inputs).entrySet()) {
            ret.put((Stop) e.getKey(), e.getValue());
        }
        return ret;
    }

    /**
     * Returns true if the file is a test: in a test source folder of the project or with a test-like path.
     */
    static boolean isTest(Project project, File file) {
        if (StopAnalysis.looksLikeTest(file.getPath())) {
            return true;
        }
        final VirtualFile vf = IntellijFileResolver.findByAbsoluteFile(file);
        return vf != null && ApplicationManager.getApplication().runReadAction((Computable<Boolean>) () ->
                !project.isDisposed() && ProjectFileIndex.getInstance(project).isInTestSourceContent(vf));
    }

    /**
     * The tests (in the test sources of the project) outside of the review that mention the given type, or
     * null if that is unknown (the IDE is indexing).
     */
    private static List<String> findTestsUsing(Project project, String typeName, Set<String> reviewedPaths) {
        try {
            return ApplicationManager.getApplication().runReadAction((Computable<List<String>>) () -> {
                if (project.isDisposed() || DumbService.isDumb(project)) {
                    return null;
                }
                final List<String> ret = new ArrayList<>();
                PsiSearchHelper.getInstance(project).processAllFilesWithWord(typeName,
                        GlobalSearchScopesCore.projectTestScope(project), (file) -> {
                            final VirtualFile vf = file.getVirtualFile();
                            if (vf != null && !reviewedPaths.contains(normalize(vf.getPath()))
                                    && !ret.contains(vf.getPath())) {
                                ret.add(vf.getPath());
                            }
                            return ret.size() < MAX_TESTS_OUTSIDE;
                        }, true);
                Collections.sort(ret);
                return ret;
            });
        } catch (final RuntimeException e) {
            Logger.debug("could not search the tests using " + typeName + ": " + e);
            return null;
        }
    }

    private static String normalize(String path) {
        return path.replace('\\', '/');
    }

    /**
     * The order of the stops (and groups of stops) within their group, after the stops that are only
     * refactorings and (optionally) the tests. Reviewers order the files of a review in different ways, and many
     * of them want to choose the order.
     */
    enum Order {
        RELATIONS("Relations (as grouped, the default)"),
        LARGEST_FIRST("Largest changes first"),
        TICKET_FIRST("Closest to the ticket first (words of its summary and description)"),
        FILE_NAME("By file name");

        private final String label;

        Order(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return this.label;
        }

        /**
         * The order with the given name, or the default for an unknown name.
         */
        static Order parse(String name) {
            for (final Order order : values()) {
                if (order.name().equals(name)) {
                    return order;
                }
            }
            return RELATIONS;
        }
    }

    /**
     * The stop ordering of the core, followed by moving the stops (and groups of stops) that are only
     * refactorings before the other stops of their group (they are easy to review and explain the following
     * changes), optionally the tests before the production code, and finally sorting by the chosen order.
     * The groups themselves stay together.
     */
    static final class Ordering implements IStopOrdering {
        private final IStopOrdering inner;
        private final Project project;
        private final boolean refactoringsFirst;
        private final boolean testsFirst;
        private final Order order;
        private final Set<String> ticketWords;

        /**
         * @param ticketWords The words of the ticket (see {@link StopAnalysis#words}), for
         *      {@link Order#TICKET_FIRST}.
         */
        Ordering(IStopOrdering inner, Project project, boolean refactoringsFirst, boolean testsFirst, Order order,
                Set<String> ticketWords) {
            this.inner = inner;
            this.project = project;
            this.refactoringsFirst = refactoringsFirst;
            this.testsFirst = testsFirst;
            this.order = order;
            this.ticketWords = ticketWords;
        }

        @Override
        public List<? extends TourElement> groupAndSort(List<Stop> stops, TourCalculatorControl isCanceled,
                Set<? extends IClassification> irrelevantCategories) throws InterruptedException {
            final List<? extends TourElement> sorted = this.inner.groupAndSort(stops, isCanceled, irrelevantCategories);
            if ((!this.refactoringsFirst && !this.testsFirst && this.order == Order.RELATIONS)
                    || isCanceled.isFastModeNeeded()) {
                // a very large review already takes long: no further analysis for the order
                return sorted;
            }
            final Map<Stop, StopAnalysis.Info> infos;
            try {
                infos = analyze(this.project, Collections.singletonList(stops), isCanceled::isCanceled);
            } catch (final RuntimeException e) {
                Logger.warn("could not analyze the stops for the ordering", e);
                return sorted;
            }
            if (isCanceled.isCanceled()) {
                throw new InterruptedException();
            }
            return reorder(sorted, (stop) -> {
                final StopAnalysis.Info info = infos.get(stop);
                if (info == null) {
                    return 2;
                } else if (this.refactoringsFirst && info.isRefactoringOnly()) {
                    return 0;
                } else if (this.testsFirst && info.isTest()) {
                    return 1;
                }
                return 2;
            }, comparator(this.order, infos, this.ticketWords));
        }
    }

    /**
     * Compares groups of stops (given as their stops) by the given order, or returns null for the order of
     * the relations.
     */
    static Comparator<List<Stop>> comparator(Order order, Map<Stop, StopAnalysis.Info> infos,
            Set<String> ticketWords) {
        switch (order) {
        case LARGEST_FIRST:
            return Comparator.comparingInt((List<Stop> stops) -> {
                int lines = 0;
                for (final Stop stop : stops) {
                    final StopAnalysis.Info info = infos.get(stop);
                    lines += info == null ? 0 : info.getChangedLineCount();
                }
                return lines;
            }).reversed();
        case TICKET_FIRST:
            return Comparator.comparingInt((List<Stop> stops) -> {
                int best = 0;
                for (final Stop stop : stops) {
                    final StopAnalysis.Info info = infos.get(stop);
                    if (info != null) {
                        final Set<String> common = new HashSet<>(info.getWords());
                        common.retainAll(ticketWords);
                        best = Math.max(best, common.size());
                    }
                }
                return best;
            }).reversed();
        case FILE_NAME:
            return Comparator.comparing((List<Stop> stops) -> {
                String first = null;
                for (final Stop stop : stops) {
                    final String path = stop.getAbsoluteFile().getPath();
                    if (first == null || path.compareTo(first) < 0) {
                        first = path;
                    }
                }
                return first == null ? "" : first;
            });
        default:
            return null;
        }
    }

    /**
     * Sorts the elements on each level by their rank and then by the given comparator, if any (stable, so
     * that the order of equal elements is kept). The rank of a group is the highest rank of its stops.
     */
    static List<TourElement> reorder(List<? extends TourElement> elements,
            ToIntFunction<Stop> rank, Comparator<List<Stop>> within) {
        final List<TourElement> ret = new ArrayList<>();
        for (final TourElement e : elements) {
            if (e instanceof Tour) {
                final Tour tour = (Tour) e;
                ret.add(new Tour(tour.getDescription(), reorder(tour.getChildren(), rank, within)));
            } else {
                ret.add(e);
            }
        }
        final Map<TourElement, Integer> ranks = new IdentityHashMap<>();
        for (final TourElement e : ret) {
            int max = 0;
            for (final Stop stop : stopsOf(e)) {
                max = Math.max(max, rank.applyAsInt(stop));
            }
            ranks.put(e, max);
        }
        Comparator<TourElement> comparator = Comparator.comparingInt(ranks::get);
        if (within != null) {
            comparator = comparator.thenComparing((a, b) -> within.compare(stopsOf(a), stopsOf(b)));
        }
        ret.sort(comparator);
        return ret;
    }

    private static List<Stop> stopsOf(TourElement e) {
        return e instanceof Tour ? ((Tour) e).getStops() : Collections.singletonList((Stop) e);
    }

}
