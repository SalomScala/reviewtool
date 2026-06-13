package de.setsoftware.reviewtool.intellij;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;

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
 * available structure. The relevance filtering of the Eclipse UI is not offered here; all commits
 * are kept and nothing is marked as irrelevant.
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

        final String[] options = new String[choices.size()];
        for (int i = 0; i < choices.size(); i++) {
            final Pair<String, List<? extends Tour>> choice = choices.get(i);
            int stops = 0;
            for (final Tour t : choice.getSecond()) {
                stops += t.getStops().size();
            }
            options[i] = choice.getFirst()
                    + " (" + stops + " stops in " + choice.getSecond().size() + " tours)";
        }

        final AtomicInteger selected = new AtomicInteger(-1);
        ApplicationManager.getApplication().invokeAndWait(() ->
                selected.set(Messages.showChooseDialog(
                        this.project,
                        "Choose how the changes should be grouped into review tours:",
                        "Create Review Tours",
                        null,
                        options,
                        options[0])));
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
        return new UserSelectedReductions(
                new ArrayList<>(changes),
                Collections.<IClassification>emptySet());
    }

}
