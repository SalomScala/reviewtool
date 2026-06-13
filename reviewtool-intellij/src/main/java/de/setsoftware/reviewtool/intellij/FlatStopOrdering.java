package de.setsoftware.reviewtool.intellij;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import de.setsoftware.reviewtool.model.api.IClassification;
import de.setsoftware.reviewtool.model.changestructure.IStopOrdering;
import de.setsoftware.reviewtool.model.changestructure.Stop;
import de.setsoftware.reviewtool.model.changestructure.TourElement;
import de.setsoftware.reviewtool.ordering.efficientalgorithm.TourCalculatorControl;

/**
 * Trivial {@link IStopOrdering} that keeps the stops in their original order without any grouping.
 * This is the IntelliJ default; the (potentially long running) clustering algorithms of the Eclipse
 * UI are not wired up here. The order can still be influenced manually in the tours view.
 */
public final class FlatStopOrdering implements IStopOrdering {

    @Override
    public List<? extends TourElement> groupAndSort(
            List<Stop> stops,
            TourCalculatorControl isCanceled,
            Set<? extends IClassification> irrelevantCategories) {
        return new ArrayList<TourElement>(stops);
    }

}
