package de.setsoftware.reviewtool.intellij;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

import de.setsoftware.reviewtool.base.Logger;
import de.setsoftware.reviewtool.model.remarks.DummyMarker;
import de.setsoftware.reviewtool.model.remarks.ResolutionType;
import de.setsoftware.reviewtool.model.remarks.ReviewData;
import de.setsoftware.reviewtool.model.remarks.ReviewRemark;
import de.setsoftware.reviewtool.model.remarks.ReviewRound;

/**
 * Shared model of the current review remarks. It parses the review remarks text into a
 * {@link ReviewData} object, offers the resolution / comment / delete operations on the remarks and
 * writes the result back into the review remarks text. Both the "Remarks" view and the editor
 * quick-fix gutter actions operate on this single model so that they stay in sync.
 */
final class ReviewRemarksModel {

    /**
     * Observer that is notified whenever the remarks change.
     */
    interface Listener {
        void remarksChanged();
    }

    private final Supplier<String> remarksGetter;
    private final Consumer<String> remarksSetter;
    private final List<Listener> listeners = new ArrayList<>();

    private ReviewData reviewData = new ReviewData();

    ReviewRemarksModel(Supplier<String> remarksGetter, Consumer<String> remarksSetter) {
        this.remarksGetter = remarksGetter;
        this.remarksSetter = remarksSetter;
    }

    void addListener(Listener listener) {
        this.listeners.add(listener);
    }

    ReviewData getReviewData() {
        return this.reviewData;
    }

    /**
     * All remarks of all review rounds.
     */
    List<ReviewRemark> getAllRemarks() {
        final List<ReviewRemark> ret = new ArrayList<>();
        for (final ReviewRound round : this.reviewData.getReviewRounds()) {
            ret.addAll(round.getRemarks());
        }
        return ret;
    }

    /**
     * Re-parses the review remarks text into the model and notifies the listeners.
     */
    void reload() {
        try {
            this.reviewData = ReviewData.parse(
                    Collections.<Integer, String>emptyMap(),
                    DummyMarker.FACTORY,
                    this.remarksGetter.get());
        } catch (final RuntimeException e) {
            Logger.warn("could not parse review remarks", e);
            this.reviewData = new ReviewData();
        }
        this.notifyListeners();
    }

    void mergeNewRemark(ReviewRemark remark) {
        this.reviewData.merge(remark, 1);
        this.persist();
    }

    void resolve(ReviewRemark remark, ResolutionType resolution, String optionalComment) {
        if (optionalComment != null && !optionalComment.trim().isEmpty()) {
            remark.addComment(currentUser(), optionalComment.trim());
        }
        remark.setResolution(resolution);
        this.persist();
    }

    void addComment(ReviewRemark remark, String comment) {
        remark.addComment(currentUser(), comment.trim());
        this.persist();
    }

    void delete(ReviewRemark remark) {
        this.reviewData.deleteRemark(remark);
        this.persist();
    }

    private void persist() {
        this.remarksSetter.accept(this.reviewData.serialize());
        this.notifyListeners();
    }

    private void notifyListeners() {
        for (final Listener listener : new ArrayList<>(this.listeners)) {
            listener.remarksChanged();
        }
    }

    static String currentUser() {
        return System.getProperty("user.name", "reviewer");
    }

}
