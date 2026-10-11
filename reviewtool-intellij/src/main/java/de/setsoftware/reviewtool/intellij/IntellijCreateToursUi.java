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
 * The choices are recorded (so that they can be saved with the review progress), and choices made
 * earlier can be given, so that the same tours are created again without asking the user.
 */
public final class IntellijCreateToursUi implements ICreateToursUi {

    private final Project project;
    private final String presetTourStructure;
    private final Set<String> presetIrrelevant;
    private String chosenTourStructure;
    private Set<String> chosenIrrelevant;

    public IntellijCreateToursUi(Project project) {
        this(project, null, null);
    }

    /**
     * Creates a UI that uses the given choices instead of asking the user (if they are not null and
     * still possible).
     *
     * @param presetTourStructure The name of the tour structure to choose.
     * @param presetIrrelevant The names of the classifications to mark as irrelevant.
     */
    public IntellijCreateToursUi(Project project, String presetTourStructure, Set<String> presetIrrelevant) {
        this.project = project;
        this.presetTourStructure = presetTourStructure;
        this.presetIrrelevant = presetIrrelevant;
    }

    /**
     * Returns the name of the tour structure that has been chosen (null if none has been chosen yet).
     */
    public String getChosenTourStructure() {
        return this.chosenTourStructure;
    }

    /**
     * Returns the names of the classifications that have been marked as irrelevant (null if not chosen yet).
     */
    public Set<String> getChosenIrrelevant() {
        return this.chosenIrrelevant;
    }

    @Override
    public List<? extends Tour> selectInitialTours(
            List<? extends Pair<String, List<? extends Tour>>> choices) {
        if (choices.isEmpty()) {
            return Collections.emptyList();
        }
        if (choices.size() == 1) {
            this.chosenTourStructure = choices.get(0).getFirst();
            return choices.get(0).getSecond();
        }
        if (this.presetTourStructure != null) {
            for (final Pair<String, List<? extends Tour>> choice : choices) {
                if (choice.getFirst().equals(this.presetTourStructure)) {
                    this.chosenTourStructure = choice.getFirst();
                    return choice.getSecond();
                }
            }
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
        this.chosenTourStructure = choices.get(selected.get()).getFirst();
        return choices.get(selected.get()).getSecond();
    }

    @Override
    public UserSelectedReductions selectIrrelevant(
            List<? extends ICommit> changes,
            Multiset<IClassification> strategyResults,
            List<ReviewRoundInfo> reviewRounds) {
        final List<IClassification> classifications = new ArrayList<>(strategyResults.keySet());
        if (classifications.isEmpty()) {
            this.chosenIrrelevant = Collections.emptySet();
            return new UserSelectedReductions(
                    new ArrayList<>(changes),
                    Collections.<IClassification>emptySet());
        }

        if (this.presetIrrelevant != null) {
            final Set<IClassification> irrelevant = new LinkedHashSet<>();
            for (final IClassification c : classifications) {
                if (this.presetIrrelevant.contains(c.getName())) {
                    irrelevant.add(c);
                }
            }
            this.chosenIrrelevant = names(irrelevant);
            return new UserSelectedReductions(new ArrayList<>(changes), irrelevant);
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
        this.chosenIrrelevant = names(irrelevant.get());
        return new UserSelectedReductions(new ArrayList<>(changes), irrelevant.get());
    }

    private static Set<String> names(Set<IClassification> classifications) {
        final Set<String> ret = new LinkedHashSet<>();
        for (final IClassification c : classifications) {
            ret.add(c.getName());
        }
        return ret;
    }

}
