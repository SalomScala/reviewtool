package de.setsoftware.reviewtool.intellij;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import com.intellij.openapi.project.Project;

import de.setsoftware.reviewtool.model.changestructure.ITourRestructuring;
import de.setsoftware.reviewtool.model.changestructure.Stop;
import de.setsoftware.reviewtool.model.changestructure.Tour;
import de.setsoftware.reviewtool.tourrestructuring.onestop.OneStopPerPartOfFileRestructuring;

/**
 * Restructures the tours into one tour per independent part of the changes (stops that are not connected by
 * their file, calls, tests or refactorings, see {@link StopAnalysis}). The parts that only consist of
 * refactorings and automatically classified changes (formatting, imports, ...) are collected in a last tour.
 * Many changes contain several independent concerns; in experiments, reviewers of decomposed changes judged
 * them more correctly (Tao and Kim, MSR 2015) and reported fewer false problems (Di Biase et al., PeerJ CS 2019).
 */
final class IndependentPartsRestructuring implements ITourRestructuring {

    private final Project project;

    IndependentPartsRestructuring(Project project) {
        this.project = project;
    }

    @Override
    public String getDescription() {
        return "one tour per independent part of the changes";
    }

    @Override
    public List<? extends Tour> restructure(List<Tour> originalTours) {
        // the changes of several commits to the same part of a file are one stop
        final List<? extends Tour> merged = new OneStopPerPartOfFileRestructuring().restructure(
                new ArrayList<>(originalTours));
        final List<Stop> stops = new ArrayList<>();
        for (final Tour tour : merged == null ? originalTours : merged) {
            stops.addAll(tour.getStops());
        }
        final Map<Stop, StopAnalysis.Info> infos =
                StopContext.analyze(this.project, Collections.singletonList(stops), () -> false);
        return toTours(StopContext.parts(stops, infos));
    }

    /**
     * Creates a tour for each non-trivial part (the largest first) and one for all trivial parts, or returns
     * null if that would not be more than one tour.
     */
    static List<Tour> toTours(List<StopContext.Part> parts) {
        final List<StopContext.Part> relevant = new ArrayList<>();
        final List<Stop> trivialStops = new ArrayList<>();
        int trivialParts = 0;
        for (final StopContext.Part part : parts) {
            if (part.isTrivial()) {
                trivialStops.addAll(part.getStops());
                trivialParts++;
            } else {
                relevant.add(part);
            }
        }
        relevant.sort(Comparator.comparingInt(StopContext.Part::getChangedLines).reversed());
        final List<Tour> ret = new ArrayList<>();
        for (int i = 0; i < relevant.size(); i++) {
            ret.add(new Tour("Part " + (i + 1) + " of " + relevant.size() + ": " + relevant.get(i).describe(),
                    relevant.get(i).getStops()));
        }
        if (!trivialStops.isEmpty()) {
            ret.add(new Tour("Small changes: refactorings, formatting, imports (" + trivialParts
                    + (trivialParts == 1 ? " part)" : " parts)"), trivialStops));
        }
        return ret.size() < 2 ? null : ret;
    }

}
