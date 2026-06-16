package de.setsoftware.reviewtool.intellij;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;

import de.setsoftware.reviewtool.base.Pair;
import de.setsoftware.reviewtool.base.Multiset;
import de.setsoftware.reviewtool.model.api.IClassification;
import de.setsoftware.reviewtool.model.api.ICommit;
import de.setsoftware.reviewtool.model.changestructure.Tour;
import de.setsoftware.reviewtool.model.changestructure.ToursInReview.ICreateToursUi;
import de.setsoftware.reviewtool.model.changestructure.ToursInReview.ReviewRoundInfo;
import de.setsoftware.reviewtool.model.changestructure.ToursInReview.UserSelectedReductions;

/**
 * IntelliJ implementation of the tour creation UI. It lets the user choose the tour structure
 * (the "tour ordering" step) when there is more than one possibility and otherwise picks the only
 * available structure. It also lets the user pick which of the automatically detected change
 * classifications should be treated as irrelevant for the review. All commits are always kept.
 */
public final class IntellijCreateToursUi implements ICreateToursUi {

    private final Project project;

    public IntellijCreateToursUi(Project project) {
        this.project = project;
    }

    @Override
    public List<? extends Tour> selectInitialTours(
            List<? extends Pair<String, List<? extends Tour>>> choices) {
        if (choices.isEmpty()) {
            return Collections.emptyList();
        }
        if (choices.size() == 1) {
            return choices.get(0).getSecond();
        }

        final List<String> options = new ArrayList<>();
        for (final Pair<String, List<? extends Tour>> choice : choices) {
            int stops = 0;
            for (final Tour t : choice.getSecond()) {
                stops += t.getStops().size();
            }
            options.add(choice.getFirst()
                    + "  (" + stops + " stops in " + choice.getSecond().size() + " tours)");
        }

        final AtomicInteger selected = new AtomicInteger(-1);
        ApplicationManager.getApplication().invokeAndWait(() -> {
            final SelectTourStructureDialog dialog = new SelectTourStructureDialog(this.project, options);
            if (dialog.showAndGet()) {
                selected.set(dialog.getSelectedIndex());
            }
        });
        if (selected.get() < 0) {
            return null;
        }
        return choices.get(selected.get()).getSecond();
    }

    @Override
    public UserSelectedReductions selectIrrelevant(
            List<? extends ICommit> changes,
            Multiset<IClassification> strategyResults,
            List<ReviewRoundInfo> reviewRounds) {
        final List<IClassification> classifications = new ArrayList<>(strategyResults.keySet());
        if (classifications.isEmpty()) {
            return new UserSelectedReductions(
                    new ArrayList<>(changes),
                    Collections.<IClassification>emptySet());
        }

        final List<Integer> counts = new ArrayList<>();
        for (final IClassification c : classifications) {
            counts.add(strategyResults.get(c));
        }

        final AtomicReference<Set<IClassification>> irrelevant = new AtomicReference<>();
        ApplicationManager.getApplication().invokeAndWait(() -> {
            final SelectIrrelevantDialog dialog =
                    new SelectIrrelevantDialog(this.project, classifications, counts);
            if (dialog.showAndGet()) {
                irrelevant.set(new LinkedHashSet<>(dialog.getSelectedClassifications()));
            }
        });
        if (irrelevant.get() == null) {
            //the user cancelled
            return null;
        }
        return new UserSelectedReductions(new ArrayList<>(changes), irrelevant.get());
    }

}
