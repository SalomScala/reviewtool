package de.setsoftware.reviewtool.intellij;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

import de.setsoftware.reviewtool.base.Logger;
import de.setsoftware.reviewtool.base.ReviewtoolException;
import de.setsoftware.reviewtool.model.remarks.DummyMarker;
import de.setsoftware.reviewtool.model.remarks.FileLinePosition;
import de.setsoftware.reviewtool.model.remarks.FilePosition;
import de.setsoftware.reviewtool.model.remarks.GlobalPosition;
import de.setsoftware.reviewtool.model.remarks.RemarkType;
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
    private String parseError;
    private volatile int currentRound = 1;
    private volatile String reviewer;

    ReviewRemarksModel(Supplier<String> remarksGetter, Consumer<String> remarksSetter) {
        this.remarksGetter = remarksGetter;
        this.remarksSetter = remarksSetter;
    }

    void addListener(Listener listener) {
        this.listeners.add(listener);
    }

    /**
     * Sets the review round new remarks are added to and the reviewer they are attributed to (both
     * determined from the ticket). A round smaller than 1 (ticket never reviewed) counts as round 1.
     * A null or empty reviewer falls back to the local user name.
     */
    void setRoundInfo(int round, String reviewer) {
        this.currentRound = Math.max(1, round);
        this.reviewer = reviewer;
    }

    int getCurrentRound() {
        return this.currentRound;
    }

    /**
     * The user new remarks of the current round are attributed to.
     */
    String getReviewer() {
        final String r = this.reviewer;
        return r == null || r.trim().isEmpty() ? currentUser() : r;
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
            this.parseError = null;
        } catch (final RuntimeException e) {
            Logger.debug("could not parse review remarks: " + e);
            this.reviewData = new ReviewData();
            this.parseError = e.getMessage() != null ? e.getMessage() : e.toString();
        }
        this.notifyListeners();
    }

    /**
     * Returns the syntax error of the review remarks text, or null if it could be parsed. While
     * there is a syntax error, the remarks cannot be changed via the model (the model would
     * otherwise write back an empty set of remarks and the text would be lost).
     */
    String getParseError() {
        return this.parseError;
    }

    private void checkWritable() {
        if (this.parseError != null) {
            throw new ReviewtoolException("The review remarks contain a syntax error (" + this.parseError
                    + "). Please correct the remarks text in the \"Remarks\" tab first.");
        }
    }

    /**
     * Creates example review remarks that show the syntax (the IntelliJ counterpart of the example in
     * the Eclipse syntax correction dialog).
     */
    static String createExampleText() {
        final ReviewData data = new ReviewData();
        final ReviewRemark r1 = ReviewRemark.create(new DummyMarker(), "TB", new GlobalPosition(),
                "global review remark important to the reviewer", RemarkType.MUST_FIX);
        r1.addComment("AUTHOR", "question to the reviewer");
        r1.setResolution(ResolutionType.QUESTION);
        data.merge(r1, 1);
        final ReviewRemark r2 = ReviewRemark.create(new DummyMarker(), "TB", new FilePosition("FileName.java"),
                "optional remark, with reference to a file", RemarkType.CAN_FIX);
        r2.addComment("AUTHOR", "comment to refuse fixing");
        r2.setResolution(ResolutionType.WONT_FIX);
        data.merge(r2, 1);
        data.merge(ReviewRemark.create(new DummyMarker(), "TB", new FileLinePosition("FileName.java", 42),
                "remark for direct fixing in a certain line", RemarkType.ALREADY_FIXED), 1);
        data.merge(ReviewRemark.create(new DummyMarker(), "TB", new GlobalPosition(),
                "well done", RemarkType.POSITIVE), 2);
        data.merge(ReviewRemark.create(new DummyMarker(), "TB", new GlobalPosition(),
                "temporary marker for the reviewer", RemarkType.TEMPORARY), 2);
        data.merge(ReviewRemark.create(new DummyMarker(), "TB", new GlobalPosition(),
                "some other remark, e.g. 'part of the remarks have been communicated orally'", RemarkType.OTHER), 2);
        return data.serialize();
    }

    void mergeNewRemark(ReviewRemark remark) {
        this.checkWritable();
        this.reviewData.merge(remark, this.currentRound);
        this.persist();
    }

    /**
     * Returns the first remark (in review round order) that still needs to be addressed, or null.
     */
    ReviewRemark findFirstOpenRemark() {
        for (final ReviewRemark remark : this.getAllRemarks()) {
            if (remark.needsFixing()) {
                return remark;
            }
        }
        return null;
    }

    /**
     * Returns the number of remarks that still need to be addressed.
     */
    int countOpenRemarks() {
        int count = 0;
        for (final ReviewRemark remark : this.getAllRemarks()) {
            if (remark.needsFixing()) {
                count++;
            }
        }
        return count;
    }

    void resolve(ReviewRemark remark, ResolutionType resolution, String optionalComment) {
        this.checkWritable();
        if (optionalComment != null && !optionalComment.trim().isEmpty()) {
            remark.addComment(currentUser(), optionalComment.trim());
        }
        remark.setResolution(resolution);
        this.persist();
    }

    void addComment(ReviewRemark remark, String comment) {
        this.checkWritable();
        remark.addComment(currentUser(), comment.trim());
        this.persist();
    }

    void delete(ReviewRemark remark) {
        this.checkWritable();
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
