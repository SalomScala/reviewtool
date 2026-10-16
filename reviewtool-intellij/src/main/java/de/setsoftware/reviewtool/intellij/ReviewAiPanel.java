package de.setsoftware.reviewtool.intellij;

import java.awt.BorderLayout;
import java.awt.Point;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.swing.JEditorPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.event.HyperlinkEvent;
import javax.swing.text.DefaultCaret;

import com.eclipsesource.json.Json;
import com.eclipsesource.json.JsonArray;
import com.eclipsesource.json.JsonObject;
import com.eclipsesource.json.JsonValue;
import com.intellij.icons.AllIcons;
import com.intellij.ide.ActivityTracker;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.HTMLEditorKitBuilder;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;

import de.setsoftware.reviewtool.base.Logger;
import de.setsoftware.reviewtool.base.Pair;
import de.setsoftware.reviewtool.intellij.OpencodeClient.Answer;
import de.setsoftware.reviewtool.intellij.OpencodeClient.OpencodeException;
import de.setsoftware.reviewtool.intellij.ReviewAiPrompts.FilteredRemarks;
import de.setsoftware.reviewtool.intellij.ReviewAiPrompts.RemarkCheck;
import de.setsoftware.reviewtool.intellij.ReviewAiPrompts.RemarkForAi;
import de.setsoftware.reviewtool.intellij.ReviewAiPrompts.RemarkSuggestion;
import de.setsoftware.reviewtool.intellij.ReviewAiPrompts.StopForAi;
import de.setsoftware.reviewtool.intellij.ReviewAiPrompts.StopRisk;
import de.setsoftware.reviewtool.intellij.ReviewToolSettings.SettingsState;
import de.setsoftware.reviewtool.model.api.IClassification;
import de.setsoftware.reviewtool.model.api.IFragment;
import de.setsoftware.reviewtool.model.changestructure.Stop;
import de.setsoftware.reviewtool.model.changestructure.Tour;
import de.setsoftware.reviewtool.model.changestructure.ToursInReview;
import de.setsoftware.reviewtool.model.remarks.RemarkType;
import de.setsoftware.reviewtool.model.remarks.ReviewRemark;

/**
 * The "AI" tab: AI support for the review via a local opencode (optional, off by default). It asks the model
 * (the default model of the opencode configuration or the one chosen in the settings) for a summary of the
 * changes as a starting point, for explanations of single stops, for remark suggestions (which the reviewer
 * accepts or rejects) and for a rating of the stops' risk (shown in the "Tours" tab), and shows the answers. All
 * requests about the same tours go to one opencode session, so the changes are sent only once; the model may
 * read (but not change) the project's files.
 */
final class ReviewAiPanel extends JPanel {

    private static final long serialVersionUID = 1L;

    /**
     * The kinds of requests.
     */
    private enum Kind {
        SUMMARY("Summary of the changes"),
        EXPLAIN("Explanation"),
        REMARKS("Suggested remarks"),
        RISKS("Risk of the stops"),
        CHECK("Check of the remarks");

        private final String title;

        Kind(String title) {
            this.title = title;
        }
    }

    /**
     * Adds a remark suggested by the AI (after the user has checked and possibly edited it).
     */
    interface RemarkAcceptor {
        /**
         * @param line The line in the stop's file, or 0 for the stop as a whole.
         * @return True iff the remark has been added.
         */
        boolean accept(Stop stop, int line, RemarkType type, String text);
    }

    /**
     * A remark suggestion shown in the panel, with its state.
     */
    private static final class Suggestion {
        private final RemarkSuggestion remark;
        private final Stop stop;
        private final StopForAi stopForAi;
        private final int conversation;
        /** The model that suggested the remark (for the local acceptance statistics). */
        private final String model;
        private boolean accepted;
        private boolean rejected;

        Suggestion(RemarkSuggestion remark, Stop stop, StopForAi stopForAi, int conversation, String model) {
            this.remark = remark;
            this.stop = stop;
            this.stopForAi = stopForAi;
            this.conversation = conversation;
            this.model = model;
        }
    }

    /**
     * An answer (or an error) shown in the panel.
     */
    private static final class Entry {
        private final String title;
        private final String html;
        private final boolean error;
        /** The remark suggestions of the answer (they are rendered with their current state), or null. */
        private final List<Suggestion> suggestions;
        /** The answer of the model (to save it), or null for errors and hints. */
        private SavedAnswer saved;
        /** The conversation the entry belongs to. */
        private int conversation;
        /** True iff the minor remark suggestions are shown (they are collapsed at first). */
        private boolean minorShown;

        Entry(String title, String html, boolean error) {
            this(title, html, error, null);
        }

        Entry(String title, String html, boolean error, List<Suggestion> suggestions) {
            this.title = title;
            this.html = html;
            this.error = error;
            this.suggestions = suggestions;
        }
    }

    /**
     * An answer of the model as it is saved, so that it can be shown again after a restart of the IDE
     * (the answers are shown again with the same methods that showed them first).
     */
    private static final class SavedAnswer {
        private final Kind kind;
        private final String title;
        private final Answer answer;
        private final int omittedDiffs;
        /** The number of the stop the request was about (0 if it was about all stops). */
        private final int stop;
        /** The remarks that were checked (only for {@link Kind#CHECK}). */
        private final List<RemarkForAi> remarks;

        SavedAnswer(Kind kind, String title, Answer answer, int omittedDiffs, int stop, List<RemarkForAi> remarks) {
            this.kind = kind;
            this.title = title;
            this.answer = answer;
            this.omittedDiffs = omittedDiffs;
            this.stop = stop;
            this.remarks = remarks;
        }
    }

    private final transient Project project;
    private final transient Supplier<ToursInReview> tours;
    private final transient Supplier<String> reviewTitle;
    private final transient Supplier<Stop> selectedStop;
    private final transient Runnable openSettings;
    private final JEditorPane content = new JEditorPane();
    private final JBScrollPane scrollPane;
    private final JBLabel status = new JBLabel();
    private final List<Entry> entries = new ArrayList<>();

    /** The tours the opencode session belongs to (a new session is used for other tours). */
    private transient ToursInReview sessionTours;
    private String sessionId;
    private boolean changesSent;
    private transient Map<Stop, StopForAi> stopsForAi;
    /** The stops by their number in the current conversation (for the links in the answers). */
    private transient Map<Integer, Stop> stopsByNumber = new HashMap<>();
    /** Counts the conversations, so that links in answers about older tours are not followed. */
    private int conversation;
    private boolean busy;
    private final transient Consumer<Stop> showStop;
    /** All remark suggestions shown, by their id (used in the links). */
    private final transient List<Suggestion> suggestions = new ArrayList<>();
    private transient RemarkAcceptor remarkAcceptor = (stop, line, type, text) -> false;
    /** Returns the remarks of the earlier review rounds with their round numbers (for "Check Remarks"). */
    private transient Supplier<List<Pair<ReviewRemark, Integer>>> earlierRemarks = ArrayList::new;
    private transient Consumer<ReviewRemark> remarkJumper = (remark) -> { };
    /** The remarks shown in the answers, by their id (used in the links). */
    private final transient List<ReviewRemark> remarkLinks = new ArrayList<>();
    private transient BiConsumer<ToursInReview, Map<Stop, StopRisk>> riskListener = (t, r) -> { };
    /** The stops the answers of the current conversation refer to (to save them with the answers). */
    private transient Map<Stop, StopForAi> conversationStops;
    /** Is called when the answers to save have changed. */
    private transient Runnable historyListener = () -> { };
    /** True while saved answers are shown again (they must not be saved again then). */
    private boolean restoring;
    /** True iff the reviewer has looked at the stop (viewed it at least partly or marked it as checked). */
    private transient Predicate<Stop> lookedAt = (stop) -> true;
    /** The number of relevant stops the reviewer has not looked at yet. */
    private transient IntSupplier relevantStopsNotLookedAt = () -> 0;
    /** The request the reviewer can make with "Ask anyway" before the own review is finished. */
    private transient Runnable deferredRequest;
    /** The entry that offers the deferred request (it is removed when the request is made). */
    private transient Entry deferredEntry;

    /**
     * @param tours Returns the current tours (null if there are none).
     * @param reviewTitle Returns the title of the review (e.g. ticket key and summary).
     * @param selectedStop Returns the stop selected in the "Tours" tab (or null).
     */
    ReviewAiPanel(Project project, Supplier<ToursInReview> tours, Supplier<String> reviewTitle,
            Supplier<Stop> selectedStop, Consumer<Stop> showStop, Runnable openSettings) {
        super(new BorderLayout());
        this.project = project;
        this.tours = tours;
        this.reviewTitle = reviewTitle;
        this.selectedStop = selectedStop;
        this.openSettings = openSettings;
        this.showStop = showStop;

        final DefaultActionGroup group = new DefaultActionGroup();
        group.add(PanelActions.withTextInToolbar(PanelActions.action("Summarize Changes", AllIcons.Actions.Lightning,
                () -> !this.busy, this::summarize)));
        group.add(PanelActions.withTextInToolbar(PanelActions.action("Explain Selected Stop",
                AllIcons.Actions.IntentionBulb, () -> !this.busy, () -> this.explain(this.selectedStop.get()))));
        group.add(PanelActions.withTextInToolbar(PanelActions.action("Suggest Remarks", AllIcons.General.Balloon,
                () -> !this.busy, this::suggestRemarks)));
        group.add(PanelActions.withTextInToolbar(PanelActions.action("Rate Risks", AllIcons.General.Warning,
                () -> !this.busy, this::rateRisks)));
        group.add(PanelActions.withTextInToolbar(PanelActions.action("Check Remarks", AllIcons.Actions.Checked,
                () -> !this.busy, this::checkRemarks)));
        group.add(PanelActions.action("Clear Answers", AllIcons.Actions.GC, () -> !this.entries.isEmpty() && !this.busy,
                this::clear));
        final ActionToolbar toolbar = ActionManager.getInstance().createActionToolbar("CoRT.Ai", group, true);
        toolbar.setTargetComponent(this);
        this.add(toolbar.getComponent(), BorderLayout.NORTH);

        this.content.setEditable(false);
        // the panel scrolls itself (to the top for a new answer, to the old position after accepting a remark)
        if (this.content.getCaret() instanceof DefaultCaret) {
            ((DefaultCaret) this.content.getCaret()).setUpdatePolicy(DefaultCaret.NEVER_UPDATE);
        }
        // the IDE's font and colors, with line wrapping
        this.content.setEditorKit(
                new HTMLEditorKitBuilder().withWordWrapViewFactory().withGapsBetweenParagraphs().build());
        this.content.setBackground(UIUtil.getPanelBackground());
        this.content.setBorder(JBUI.Borders.empty(4, 8));
        this.content.addHyperlinkListener((e) -> {
            if (e.getEventType() != HyperlinkEvent.EventType.ACTIVATED) {
                return;
            }
            if ("settings".equals(e.getDescription())) {
                this.openSettings.run();
            } else if ("deferred".equals(e.getDescription())) {
                final Runnable request = this.deferredRequest;
                if (request != null && !this.busy) {
                    this.deferredRequest = null;
                    this.entries.remove(this.deferredEntry);
                    this.deferredEntry = null;
                    request.run();
                }
            } else if (e.getDescription() != null && e.getDescription().startsWith("minor:")) {
                this.showMinorSuggestions(Integer.parseInt(e.getDescription().substring("minor:".length())));
            } else if (e.getDescription() != null && e.getDescription().startsWith("stop:")) {
                this.linkToStopClicked(e.getDescription());
            } else if (e.getDescription() != null && e.getDescription().startsWith("accept:")) {
                this.accept(Integer.parseInt(e.getDescription().substring("accept:".length())));
            } else if (e.getDescription() != null && e.getDescription().startsWith("remark:")) {
                final int id = Integer.parseInt(e.getDescription().substring("remark:".length()));
                if (id >= 0 && id < this.remarkLinks.size()) {
                    this.remarkJumper.accept(this.remarkLinks.get(id));
                }
            } else if (e.getDescription() != null && e.getDescription().startsWith("reject:")) {
                this.reject(Integer.parseInt(e.getDescription().substring("reject:".length())));
            }
        });
        this.scrollPane = new JBScrollPane(this.content);
        this.add(this.scrollPane, BorderLayout.CENTER);
        this.status.setBorder(JBUI.Borders.empty(2, 6));
        this.status.setComponentStyle(UIUtil.ComponentStyle.SMALL);
        this.add(this.status, BorderLayout.SOUTH);
        this.render();
    }

    /**
     * Sets the callback that adds an accepted remark suggestion to the review remarks.
     */
    void setRemarkAcceptor(RemarkAcceptor acceptor) {
        this.remarkAcceptor = acceptor;
    }

    /**
     * Sets the callback that shows the risks of the stops (for the given tours; null and an empty map
     * when the risks shall not be shown anymore).
     */
    void setRiskListener(BiConsumer<ToursInReview, Map<Stop, StopRisk>> listener) {
        this.riskListener = listener;
    }

    /**
     * Sets the source of the remarks of the earlier review rounds (with their round numbers) and the
     * callback that jumps to a remark.
     */
    void setRemarks(Supplier<List<Pair<ReviewRemark, Integer>>> earlierRemarks, Consumer<ReviewRemark> jumper) {
        this.earlierRemarks = earlierRemarks;
        this.remarkJumper = jumper;
    }

    /**
     * Sets the sources of the reviewer's progress: remark suggestions are offered as a second opinion
     * after the reviewer's own review (see {@link SettingsState#aiSuggestionsAfterOwnReview}).
     *
     * @param lookedAt True iff the reviewer has looked at the stop.
     * @param relevantStopsNotLookedAt The number of relevant stops the reviewer has not looked at yet.
     */
    void setReviewerProgress(Predicate<Stop> lookedAt, IntSupplier relevantStopsNotLookedAt) {
        this.lookedAt = lookedAt;
        this.relevantStopsNotLookedAt = relevantStopsNotLookedAt;
    }

    /**
     * Sets the callback that is called when the answers to save (see {@link #exportHistory()}) have changed.
     */
    void setHistoryListener(Runnable listener) {
        this.historyListener = listener;
    }

    private boolean isEnabledInSettings() {
        return ReviewToolSettings.getInstance(this.project).getState().aiEnabled;
    }

    /**
     * Updates the panel after the settings have been changed.
     */
    void settingsChanged() {
        this.render();
    }

    /**
     * Asks the model for a summary of the changes as a starting point for the review.
     */
    void summarize() {
        this.ask(Kind.SUMMARY, null);
    }

    /**
     * Asks the model for remark suggestions, which the user can accept or reject.
     */
    void suggestRemarks() {
        final int notLookedAt = this.relevantStopsNotLookedAt.getAsInt();
        if (this.isOwnReviewFirst() && this.tours.get() != null && notLookedAt > 0) {
            this.offerAfterOwnReview(Kind.REMARKS.title, (notLookedAt == 1 ? "1 relevant stop has" : notLookedAt
                    + " relevant stops have") + " not been looked at yet.", () -> this.ask(Kind.REMARKS, null));
            return;
        }
        this.ask(Kind.REMARKS, null);
    }

    private boolean isOwnReviewFirst() {
        final SettingsState settings = ReviewToolSettings.getInstance(this.project).getState();
        return settings.aiEnabled && settings.aiSuggestionsAfterOwnReview;
    }

    /**
     * Explains that the remark suggestions are a second opinion after the reviewer's own review and lets
     * the reviewer ask anyway.
     */
    private void offerAfterOwnReview(String title, String reason, Runnable request) {
        // only the latest offer is shown
        this.entries.remove(this.deferredEntry);
        this.deferredRequest = request;
        this.deferredEntry = new Entry(title + ": your own review first", "<p>" + SimpleMarkdown.escape(reason)
                + " Review the code yourself first and use the AI suggestions as a second opinion afterwards: in"
                + " a study, reviewers who saw an AI review first looked at less of the code and did not find"
                + " more of the severe problems.</p><p><a href='deferred'>Ask the AI anyway</a></p>"
                + "<p class='hint'>This can be switched off in the settings (\"Offer AI remark suggestions only"
                + " after my own review\").</p>", false);
        this.addEntry(this.deferredEntry);
    }

    /**
     * Asks the model for remark suggestions for the given stop only.
     */
    void suggestRemarks(Stop stop) {
        if (stop == null) {
            this.addEntry(new Entry("Suggest remarks for a stop",
                    "<p>Select a stop in the \"Tours\" tab first (or use \"Suggest Remarks with AI\" in its"
                    + " context menu).</p>", true));
            return;
        }
        if (this.isOwnReviewFirst() && !this.lookedAt.test(stop)) {
            this.offerAfterOwnReview(Kind.REMARKS.title, "You have not looked at the stop "
                    + this.relativePath(stop) + (lines(stop) == null ? "" : ":" + lines(stop)) + " yet.",
                    () -> this.ask(Kind.REMARKS, stop));
            return;
        }
        this.ask(Kind.REMARKS, stop);
    }

    /**
     * Asks the model to rate the risk of the stops; the ratings are shown in the "Tours" tab.
     */
    void rateRisks() {
        this.ask(Kind.RISKS, null);
    }

    /**
     * Asks the model whether the changes address the remarks of the earlier review rounds.
     */
    void checkRemarks() {
        if (!this.isEnabledInSettings()) {
            this.render();
            return;
        }
        final List<RemarkForAi> remarks = new ArrayList<>();
        final List<ReviewRemark> refs = new ArrayList<>();
        for (final Pair<ReviewRemark, Integer> p : this.earlierRemarks.get()) {
            final ReviewRemark remark = p.getFirst();
            refs.add(remark);
            remarks.add(new RemarkForAi(remarks.size() + 1, p.getSecond(), ReviewRemarksPanel.typeLabel(remark),
                    remark.getResolution().name().toLowerCase(Locale.ROOT).replace('_', ' '),
                    ReviewRemarksPanel.describePosition(remark), remark.getText()));
        }
        if (remarks.isEmpty()) {
            this.addEntry(new Entry(Kind.CHECK.title, "<p>There are no remarks from earlier review rounds to check."
                    + " \"Check Remarks\" is meant for the second review round and later: it checks whether the"
                    + " changes since then address the remarks.</p>", true));
            return;
        }
        this.ask(Kind.CHECK, null, remarks, refs);
    }

    /**
     * Asks the model to explain the given stop.
     */
    void explain(Stop stop) {
        if (stop == null) {
            this.addEntry(new Entry("Explain a stop",
                    "<p>Select a stop in the \"Tours\" tab first (or use \"Explain with AI\" in its context menu).</p>",
                    true));
            return;
        }
        this.ask(Kind.EXPLAIN, stop);
    }

    private void clear() {
        this.entries.clear();
        this.suggestions.clear();
        this.remarkLinks.clear();
        this.riskListener.accept(null, new HashMap<>());
        this.render();
        this.historyListener.run();
    }

    private void ask(Kind kind, Stop stop) {
        this.ask(kind, stop, null, null);
    }

    private void ask(Kind kind, Stop stop, List<RemarkForAi> remarks, List<ReviewRemark> remarkRefs) {
        final String title = kind.title;
        if (!this.isEnabledInSettings()) {
            this.render();
            return;
        }
        final ToursInReview currentTours = this.tours.get();
        if (currentTours == null) {
            this.addEntry(new Entry(title, "<p>There are no review tours yet. Start the review (or create the"
                    + " tours) first.</p>", true));
            return;
        }
        if (this.busy) {
            return;
        }
        if (currentTours != this.sessionTours) {
            // other tours: a new conversation
            this.sessionTours = currentTours;
            this.sessionId = null;
            this.changesSent = false;
            this.stopsForAi = null;
            this.stopsByNumber = new HashMap<>();
            this.conversationStops = null;
            this.conversation++;
        }
        this.setBusy(true);
        final String reviewName = this.reviewTitle.get();
        final String model = Objects.toString(ReviewToolSettings.getInstance(this.project).getState().aiModel, "");
        final String about = kind == Kind.REMARKS ? " for " : " of ";
        final String fullTitle = stop == null ? title
                : title + about + this.relativePath(stop) + (lines(stop) == null ? "" : ":" + lines(stop));
        this.setStatus("Asking the AI (" + fullTitle + ")...");
        new Task.Backgroundable(this.project, "Asking the AI via opencode", true) {
            @Override
            public void run(ProgressIndicator indicator) {
                try {
                    indicator.setText("Starting opencode...");
                    final OpencodeClient client = OpencodeServer.getInstance(ReviewAiPanel.this.project)
                            .getClient(indicator::isCanceled);
                    indicator.setText("Preparing the changes...");
                    final Map<Stop, StopForAi> stops = ReviewAiPanel.this.stopsForAi(currentTours, indicator);
                    final StringBuilder text = new StringBuilder();
                    int omitted = 0;
                    if (!ReviewAiPanel.this.changesSent) {
                        final ReviewAiPrompts.Context context = ReviewAiPrompts.reviewContext(
                                reviewName, new ArrayList<>(stops.values()), ReviewAiPrompts.MAX_CONTEXT_CHARS);
                        omitted = context.getOmittedDiffs();
                        text.append(context.getText()).append("\n---\n\n");
                    }
                    final StopForAi stopForAi = stop == null ? null : stops.get(stop);
                    if (stop != null && stopForAi == null) {
                        throw new OpencodeException("The stop is not part of the current tours.", false, null);
                    }
                    final String request = request(kind, stopForAi, remarks);
                    text.append(kind == Kind.REMARKS
                            ? ReviewAiPrompts.withGuidelines(request, ReviewAiPanel.this.readGuidelines()) : request);
                    client.checkModel(model);
                    String session = ReviewAiPanel.this.sessionId;
                    if (session == null) {
                        session = client.createSession("CoRT review: " + reviewName);
                    }
                    indicator.setText("Waiting for the answer of the model (this can take a minute)...");
                    final String system = ReviewAiPrompts.system(Locale.getDefault());
                    final Answer answer;
                    if (kind == Kind.REMARKS) {
                        answer = client.promptStructured(session, model, system, text.toString(),
                                ReviewAiPrompts.remarksSchema(), indicator::isCanceled);
                    } else if (kind == Kind.RISKS) {
                        answer = client.promptStructured(session, model, system, text.toString(),
                                ReviewAiPrompts.risksSchema(), indicator::isCanceled);
                    } else if (kind == Kind.CHECK) {
                        answer = client.promptStructured(session, model, system, text.toString(),
                                ReviewAiPrompts.remarkChecksSchema(), indicator::isCanceled);
                    } else {
                        answer = client.prompt(session, model, system, text.toString(), indicator::isCanceled);
                    }
                    final String finalSession = session;
                    final int finalOmitted = omitted;
                    final Map<Integer, Stop> numbers = byNumber(stops);
                    final String heading = stopForAi == null ? title : title + about + stopForAi.describe();
                    ApplicationManager.getApplication().invokeLater(() -> {
                        if (ReviewAiPanel.this.sessionTours == currentTours) {
                            ReviewAiPanel.this.sessionId = finalSession;
                            ReviewAiPanel.this.changesSent = true;
                            ReviewAiPanel.this.stopsByNumber = numbers;
                        }
                        if (kind == Kind.REMARKS) {
                            ReviewAiPanel.this.remarksSuggested(answer, stops, finalOmitted,
                                    stopForAi == null ? 0 : stopForAi.getNumber());
                        } else if (kind == Kind.RISKS) {
                            ReviewAiPanel.this.risksRated(answer, stops, currentTours, finalOmitted);
                        } else if (kind == Kind.CHECK) {
                            ReviewAiPanel.this.remarksChecked(answer, stops, remarks, remarkRefs, finalOmitted);
                        } else {
                            ReviewAiPanel.this.answered(kind, heading, answer, stops,
                                    stopForAi == null ? 0 : stopForAi.getNumber(), finalOmitted);
                        }
                    });
                } catch (final CancellationException e) {
                    ApplicationManager.getApplication().invokeLater(() -> ReviewAiPanel.this.failed(fullTitle,
                            "The request has been canceled.", false));
                } catch (final OpencodeException e) {
                    ApplicationManager.getApplication().invokeLater(() -> ReviewAiPanel.this.failed(fullTitle,
                            e.getMessage(), e.isConfigurationProblem()));
                } catch (final Exception e) {
                    Logger.warn("AI request failed", e);
                    ApplicationManager.getApplication().invokeLater(() -> ReviewAiPanel.this.failed(fullTitle,
                            "The request failed: " + e, false));
                }
            }
        }.queue();
    }

    /**
     * Reads the review guidelines of the project for the AI (see {@link ReviewAiPrompts#GUIDELINES_FILE}),
     * or returns null if there are none.
     */
    private String readGuidelines() {
        final String base = this.project.getBasePath();
        if (base == null) {
            return null;
        }
        final Path file = Paths.get(base, ReviewAiPrompts.GUIDELINES_FILE);
        try {
            return Files.isRegularFile(file) ? new String(Files.readAllBytes(file), StandardCharsets.UTF_8) : null;
        } catch (final IOException e) {
            Logger.warn("could not read the review guidelines " + file, e);
            return null;
        }
    }

    private static String request(Kind kind, StopForAi stop, List<RemarkForAi> remarks) {
        switch (kind) {
        case CHECK:
            return ReviewAiPrompts.remarkChecksRequest(remarks);
        case EXPLAIN:
            return ReviewAiPrompts.explainRequest(stop);
        case REMARKS:
            return stop == null ? ReviewAiPrompts.remarksRequest() : ReviewAiPrompts.remarksRequest(stop);
        case RISKS:
            return ReviewAiPrompts.risksRequest();
        case SUMMARY:
        default:
            return ReviewAiPrompts.summaryRequest();
        }
    }

    /**
     * Describes all stops of the tours for the model (numbered like in the "Tours" tab), loading their
     * diffs. The result is kept for further requests about the same tours.
     */
    private Map<Stop, StopForAi> stopsForAi(ToursInReview currentTours, ProgressIndicator indicator)
            throws Exception {
        final Map<Stop, StopForAi> known = this.stopsForAi;
        if (known != null) {
            return known;
        }
        final Map<Stop, StopForAi> restored = this.conversationStops;
        final Map<Stop, StopForAi> inOrder;
        if (restored == null) {
            inOrder = this.describeStops(currentTours, indicator, true);
        } else {
            // the answers shown again refer to the stops by these numbers (the tours may have been
            // reordered since, e.g. by activating a tour)
            inOrder = new LinkedHashMap<>();
            for (final Map.Entry<Stop, StopForAi> e : restored.entrySet()) {
                indicator.checkCanceled();
                inOrder.put(e.getKey(), e.getValue().withDiff(diff(e.getKey())));
            }
        }
        this.stopsForAi = inOrder;
        return inOrder;
    }

    /**
     * Describes the stops of the tours for the model in tour order (numbered like in the "Tours" tab).
     *
     * @param withDiffs False to leave out the diffs (e.g. only to compare the stops with saved ones).
     */
    private Map<Stop, StopForAi> describeStops(ToursInReview currentTours, ProgressIndicator indicator,
            boolean withDiffs) throws Exception {
        final List<Stop> ordered = new ArrayList<>();
        final List<String> tourNames = new ArrayList<>();
        for (final Tour tour : currentTours.getTopmostTours()) {
            for (final Stop stop : tour.getStops()) {
                ordered.add(stop);
                tourNames.add(firstLine(tour.getDescription()));
            }
        }
        // the context lists the stops in tour order
        final Map<Stop, StopForAi> inOrder = new LinkedHashMap<>();
        for (int i = 0; i < ordered.size(); i++) {
            if (indicator != null) {
                indicator.checkCanceled();
            }
            final Stop stop = ordered.get(i);
            inOrder.put(stop, new StopForAi(i + 1, tourNames.get(i), this.relativePath(stop), lines(stop),
                    classification(stop, currentTours), withDiffs ? diff(stop) : null));
        }
        return inOrder;
    }

    private String relativePath(Stop stop) {
        final String path = stop.getAbsoluteFile().getPath().replace('\\', '/');
        final String base = this.project.getBasePath();
        return base != null && path.startsWith(base + "/") ? path.substring(base.length() + 1) : path;
    }

    private static String lines(Stop stop) {
        if (!stop.isDetailedFragmentKnown()) {
            return null;
        }
        // like in the "Tours" tab
        final IFragment fragment = stop.getMostRecentFragment();
        final int from = fragment.getFrom().getLine();
        final int to = fragment.getTo().getLine();
        return from == to ? Integer.toString(from) : from + "-" + to;
    }

    private static String classification(Stop stop, ToursInReview tours) {
        final List<String> names = new ArrayList<>();
        for (final IClassification c : stop.getClassification()) {
            names.add(c.getName());
        }
        if (names.isEmpty()) {
            return null;
        }
        return (stop.isIrrelevantForReview(tours.getIrrelevantCategories()) ? "marked as irrelevant: " : "")
                + String.join(", ", names);
    }

    private static String diff(Stop stop) throws Exception {
        if (stop.isBinaryChange()) {
            return null;
        }
        return StopDiffViewer.load(stop).unifiedDiff();
    }

    private void answered(Kind kind, String title, Answer answer, Map<Stop, StopForAi> stops, int stop,
            int omittedDiffs) {
        this.setBusy(false);
        final StringBuilder html = new StringBuilder(
                linkStops(SimpleMarkdown.toHtml(answer.getText()), this.stopsByNumber.keySet(), this.conversation));
        appendOmittedHint(html, omittedDiffs);
        this.addAnswer(new Entry(titleWithModel(title, answer), html.toString(), false),
                new SavedAnswer(kind, title, answer, omittedDiffs, stop, null), stops);
        this.setStatus("");
    }

    private static String titleWithModel(String title, Answer answer) {
        return title + (answer.getModel() == null ? "" : "  ·  " + answer.getModel());
    }

    private static void appendOmittedHint(StringBuilder html, int omittedDiffs) {
        if (omittedDiffs > 0) {
            html.append("<p class='hint'>The diffs of ").append(omittedDiffs).append(omittedDiffs == 1 ? " stop" : " stops")
                    .append(" were left out because the changes are too big; the model could read the files.</p>");
        }
    }

    private static Map<Integer, Stop> byNumber(Map<Stop, StopForAi> stops) {
        final Map<Integer, Stop> result = new HashMap<>();
        for (final Map.Entry<Stop, StopForAi> e : stops.entrySet()) {
            result.put(e.getValue().getNumber(), e.getKey());
        }
        return result;
    }

    /**
     * Shows the remark suggestions of the answer (package-private for tests).
     */
    void remarksSuggested(Answer answer, Map<Stop, StopForAi> stops, int omittedDiffs) {
        this.remarksSuggested(answer, stops, omittedDiffs, 0);
    }

    /**
     * Shows the remark suggestions of the answer.
     *
     * @param onlyStop The number of the stop the suggestions were requested for (suggestions for other
     *      stops are left out), or 0 if they were requested for all stops.
     */
    void remarksSuggested(Answer answer, Map<Stop, StopForAi> stops, int omittedDiffs, int onlyStop) {
        this.setBusy(false);
        this.setStatus("");
        final Map<Integer, Stop> numbers = byNumber(stops);
        final List<RemarkSuggestion> remarks = ReviewAiPrompts.parseRemarks(answer.getStructured(),
                onlyStop > 0 && numbers.containsKey(onlyStop) ? Collections.singleton(onlyStop) : numbers.keySet());
        final Map<Integer, String> linesOfStops = new HashMap<>();
        for (final StopForAi s : stops.values()) {
            linesOfStops.put(s.getNumber(), s.getLines());
        }
        final FilteredRemarks filtered = ReviewAiPrompts.filterRemarks(remarks, linesOfStops,
                ReviewAiPrompts.MAX_SUGGESTIONS_PER_STOP, ReviewAiPrompts.MAX_SUGGESTIONS);
        final List<Suggestion> entrySuggestions = new ArrayList<>();
        for (final RemarkSuggestion remark : filtered.getShown()) {
            final Stop stop = numbers.get(remark.getStop());
            entrySuggestions.add(new Suggestion(remark, stop, stops.get(stop), this.conversation, answer.getModel()));
        }
        final StringBuilder html = new StringBuilder();
        if (entrySuggestions.isEmpty()) {
            html.append("<p>The model has no remarks to suggest").append(onlyStop > 0 ? " for this stop" : "")
                    .append(".</p>");
        }
        final List<String> leftOut = new ArrayList<>();
        final int invalid = ReviewAiPrompts.count(answer.getStructured(), "remarks") - remarks.size();
        if (invalid > 0) {
            leftOut.add(invalid + (onlyStop > 0 ? " for another stop, with an unknown kind or without text"
                    : " for an unknown stop, with an unknown kind or without text"));
        }
        if (filtered.getNotOnChangedLines() > 0) {
            leftOut.add(filtered.getNotOnChangedLines() + " on lines that have not been changed");
        }
        if (filtered.getOverLimit() > 0) {
            leftOut.add(filtered.getOverLimit() + " less important beyond the limit of "
                    + ReviewAiPrompts.MAX_SUGGESTIONS_PER_STOP + " per stop and " + ReviewAiPrompts.MAX_SUGGESTIONS
                    + " in total");
        }
        if (!leftOut.isEmpty()) {
            final int count = invalid + filtered.getNotOnChangedLines() + filtered.getOverLimit();
            html.append("<p class='hint'>").append(count).append(count == 1 ? " suggestion was" : " suggestions were")
                    .append(" left out: ").append(SimpleMarkdown.escape(String.join("; ", leftOut))).append(".</p>");
        }
        appendOmittedHint(html, omittedDiffs);
        final StopForAi stop = onlyStop > 0 ? stops.get(numbers.get(onlyStop)) : null;
        final String title = stop == null ? Kind.REMARKS.title : Kind.REMARKS.title + " for " + stop.describe();
        this.addAnswer(new Entry(titleWithModel(title, answer), html.toString(), false, entrySuggestions),
                new SavedAnswer(Kind.REMARKS, title, answer, omittedDiffs, onlyStop, null), stops);
    }

    /**
     * Shows the risks of the stops of the answer and passes them to the risk listener (package-private
     * for tests).
     */
    void risksRated(Answer answer, Map<Stop, StopForAi> stops, ToursInReview ratedTours, int omittedDiffs) {
        this.setBusy(false);
        this.setStatus("");
        final Map<Integer, Stop> numbers = byNumber(stops);
        final List<StopRisk> risks = ReviewAiPrompts.parseRisks(answer.getStructured(), numbers.keySet());
        final Map<Stop, StopRisk> byStop = new HashMap<>();
        for (final StopRisk risk : risks) {
            byStop.put(numbers.get(risk.getStop()), risk);
        }
        this.riskListener.accept(ratedTours, byStop);
        final StringBuilder html = new StringBuilder();
        if (risks.isEmpty()) {
            html.append("<p>The model did not rate any stop.</p>");
        } else {
            html.append("<p>The ratings are also shown in the \"Tours\" tab (high and medium risk).</p><ul>");
            for (final StopRisk risk : risks) {
                final StopForAi stop = stops.get(numbers.get(risk.getStop()));
                html.append("<li>").append(riskLabel(risk.getRisk())).append(' ')
                        .append(SimpleMarkdown.escape(stop.describe()));
                if (!risk.getReason().isEmpty()) {
                    html.append(" &ndash; ").append(SimpleMarkdown.escape(risk.getReason()));
                }
                html.append("</li>");
            }
            html.append("</ul>");
        }
        final int notRated = stops.size() - risks.size();
        if (notRated > 0 && !risks.isEmpty()) {
            html.append("<p class='hint'>").append(notRated).append(notRated == 1 ? " stop was" : " stops were")
                    .append(" not rated.</p>");
        }
        appendOmittedHint(html, omittedDiffs);
        this.addAnswer(new Entry(titleWithModel(Kind.RISKS.title, answer),
                linkStops(html.toString(), numbers.keySet(), this.conversation), false),
                new SavedAnswer(Kind.RISKS, Kind.RISKS.title, answer, omittedDiffs, 0, null), stops);
    }

    /**
     * Shows the model's assessment of the remarks of the earlier review rounds (package-private for tests).
     */
    void remarksChecked(Answer answer, Map<Stop, StopForAi> stops, List<RemarkForAi> remarks,
            List<ReviewRemark> remarkRefs, int omittedDiffs) {
        this.setBusy(false);
        this.setStatus("");
        final Map<Integer, Stop> numbers = byNumber(stops);
        final Set<Integer> remarkNumbers = new HashSet<>();
        for (final RemarkForAi remark : remarks) {
            remarkNumbers.add(remark.getNumber());
        }
        final List<RemarkCheck> checks =
                ReviewAiPrompts.parseRemarkChecks(answer.getStructured(), remarkNumbers, numbers.keySet());
        final StringBuilder html = new StringBuilder();
        if (checks.isEmpty()) {
            html.append("<p>The model did not check any remark.</p>");
        }
        for (final RemarkCheck check : checks) {
            final RemarkForAi remark = remarks.get(check.getRemark() - 1);
            final ReviewRemark ref = remarkRefs.get(check.getRemark() - 1);
            html.append("<p>").append(checkLabel(check.getStatus())).append(' ');
            if (ref == null) {
                // a saved answer whose remark is not known anymore
                html.append(SimpleMarkdown.escape(remark.getLocation()));
            } else {
                final int id = this.remarkLinks.size();
                this.remarkLinks.add(ref);
                html.append("<a href='remark:").append(id).append("'>")
                        .append(SimpleMarkdown.escape(ReviewRemarksPanel.describePosition(ref))).append("</a>");
            }
            html.append(' ').append(SimpleMarkdown.escape(RemarkLinePainter.abbreviate(remark.getText())))
                    .append("<br><span class='hint'>").append(SimpleMarkdown.escape(remark.summary()));
            if (check.getStop() > 0) {
                html.append(", Stop ").append(check.getStop());
            }
            if (!check.getReason().isEmpty()) {
                html.append(" &ndash; ").append(SimpleMarkdown.escape(check.getReason()));
            }
            html.append("</span></p>");
        }
        final int notChecked = remarks.size() - checks.size();
        if (notChecked > 0 && !checks.isEmpty()) {
            html.append("<p class='hint'>").append(notChecked).append(notChecked == 1 ? " remark was" : " remarks were")
                    .append(" not checked.</p>");
        }
        appendOmittedHint(html, omittedDiffs);
        this.addAnswer(new Entry(titleWithModel(Kind.CHECK.title, answer),
                linkStops(html.toString(), numbers.keySet(), this.conversation), false),
                new SavedAnswer(Kind.CHECK, Kind.CHECK.title, answer, omittedDiffs, 0, remarks), stops);
    }

    private static String checkLabel(String status) {
        switch (status) {
        case "fixed":
            return "<b style='color:#3c9a3c'>fixed</b>";
        case "not_fixed":
            return "<b style='color:#d04040'>not fixed</b>";
        default:
            return "<b>unclear</b>";
        }
    }

    private static String riskLabel(String risk) {
        switch (risk) {
        case "high":
            return "<b style='color:#d04040'>high</b>";
        case "medium":
            return "<b style='color:#c08020'>medium</b>";
        default:
            return "<b>" + SimpleMarkdown.escape(risk) + "</b>";
        }
    }

    private static RemarkType remarkType(String kind) {
        switch (kind) {
        case "must":
            return RemarkType.MUST_FIX;
        case "can":
            return RemarkType.CAN_FIX;
        case "positive":
            return RemarkType.POSITIVE;
        default:
            return RemarkType.OTHER;
        }
    }

    private static String kindLabel(String kind) {
        switch (kind) {
        case "must":
            return "must fix";
        case "can":
            return "can fix";
        default:
            return kind;
        }
    }

    private String suggestionsHtml(Entry entry, int entryIndex) {
        final StringBuilder html = new StringBuilder();
        final List<Suggestion> minor = new ArrayList<>();
        for (final Suggestion s : entry.suggestions) {
            if (s.remark.isMinor()) {
                minor.add(s);
            } else {
                this.appendSuggestion(html, s);
            }
        }
        if (!minor.isEmpty() && !entry.minorShown) {
            html.append("<p class='hint'>").append(minor.size())
                    .append(minor.size() == 1 ? " minor suggestion (e.g. naming or style)" : " minor suggestions (e.g."
                            + " naming or style)")
                    .append(" &nbsp; <a href='minor:").append(entryIndex).append("'>Show</a></p>");
        } else {
            for (final Suggestion s : minor) {
                this.appendSuggestion(html, s);
            }
        }
        return html.toString();
    }

    /**
     * Shows the collapsed minor remark suggestions of the entry with the given index (package-private for
     * tests).
     */
    void showMinorSuggestions(int entryIndex) {
        if (entryIndex >= 0 && entryIndex < this.entries.size()) {
            this.entries.get(entryIndex).minorShown = true;
            this.renderKeepingPosition();
        }
    }

    /**
     * True iff the reviewer can still make a request with "Ask the AI anyway" (package-private for tests).
     */
    boolean hasDeferredRequest() {
        return this.deferredRequest != null;
    }

    private void appendSuggestion(StringBuilder html, Suggestion s) {
        final int id = this.suggestions.indexOf(s);
        final RemarkSuggestion r = s.remark;
        final String location = s.stopForAi.getPath().replaceFirst(".*/", "")
                + (r.getLine() > 0 ? ":" + r.getLine() : "");
        html.append("<p>").append(s.rejected ? "<s>" : "").append("<b>[").append(kindLabel(r.getKind()))
                .append("high".equals(r.getImportance()) ? ", important" : r.isMinor() ? ", minor" : "")
                .append("]</b> ").append(SimpleMarkdown.escape(r.getText())).append(s.rejected ? "</s>" : "")
                .append("<br>");
        final String stopLink = s.conversation == this.conversation
                ? "<a href='stop:" + s.conversation + ":" + r.getStop() + "'>Stop " + r.getStop() + "</a>"
                : "Stop " + r.getStop();
        html.append("<span class='hint'>").append(stopLink).append(", ").append(SimpleMarkdown.escape(location));
        if (!r.getReason().isEmpty()) {
            html.append(" &ndash; ").append(SimpleMarkdown.escape(r.getReason()));
        }
        html.append("</span><br>");
        if (s.accepted) {
            html.append("<i>Added to the review remarks.</i>");
        } else if (s.rejected) {
            html.append("<i>Rejected.</i> <a href='accept:").append(id).append("'>Accept anyway...</a>");
        } else {
            html.append("<a href='accept:").append(id).append("'>Accept...</a> &nbsp; <a href='reject:")
                    .append(id).append("'>Reject</a>");
        }
        html.append("</p>");
    }

    /**
     * Lets the user add the remark suggestion with the given id (package-private for tests).
     */
    void accept(int id) {
        if (id < 0 || id >= this.suggestions.size()) {
            return;
        }
        final Suggestion s = this.suggestions.get(id);
        if (s.accepted) {
            return;
        }
        // the user can check and edit the remark before it is added
        if (this.remarkAcceptor.accept(s.stop, s.remark.getLine(), remarkType(s.remark.getKind()), s.remark.getText())) {
            final AiSuggestionStats stats = AiSuggestionStats.getInstance();
            if (s.rejected) {
                stats.count(s.model, s.remark.getKind(), false, -1);
            }
            stats.count(s.model, s.remark.getKind(), true, 1);
            s.accepted = true;
            s.rejected = false;
            this.renderKeepingPosition();
            this.historyListener.run();
        }
    }

    /**
     * Rejects the remark suggestion with the given id (package-private for tests).
     */
    void reject(int id) {
        if (id < 0 || id >= this.suggestions.size()) {
            return;
        }
        final Suggestion s = this.suggestions.get(id);
        if (s.accepted || s.rejected) {
            return;
        }
        AiSuggestionStats.getInstance().count(s.model, s.remark.getKind(), false, 1);
        s.rejected = true;
        this.renderKeepingPosition();
        this.historyListener.run();
    }

    private static final Pattern STOP_REFERENCE = Pattern.compile("\\b([Ss]top) (\\d+)\\b");

    /**
     * Turns the references to stops ("Stop 3") into links that jump to the stop.
     */
    static String linkStops(String html, java.util.Set<Integer> numbers, int conversation) {
        final Matcher m = STOP_REFERENCE.matcher(html);
        final StringBuffer sb = new StringBuffer();
        while (m.find()) {
            final int number = Integer.parseInt(m.group(2));
            m.appendReplacement(sb, numbers.contains(number)
                    ? "<a href='stop:" + conversation + ":" + number + "'>" + m.group(1) + " " + number + "</a>"
                    : m.group());
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private void linkToStopClicked(String link) {
        final String[] parts = link.split(":");
        final Stop stop = Integer.parseInt(parts[1]) == this.conversation && this.sessionTours == this.tours.get()
                ? this.stopsByNumber.get(Integer.parseInt(parts[2])) : null;
        if (stop == null) {
            IntellijNotifications.info(this.project,
                    "This answer belongs to other review tours; the stop cannot be shown anymore.");
            return;
        }
        this.showStop.accept(stop);
    }

    private void failed(String title, String message, boolean configurationProblem) {
        this.setBusy(false);
        this.setStatus("");
        this.addEntry(new Entry(title, "<p>" + SimpleMarkdown.escape(message).replace("\n", "<br>") + "</p>"
                + (configurationProblem ? "<p><a href='settings'>Open the settings</a></p>" : ""), true));
    }

    private void setBusy(boolean busy) {
        this.busy = busy;
        // the toolbar would otherwise show the old state until the next mouse movement
        ActivityTracker.getInstance().inc();
    }

    /**
     * Shows an answer of the model and remembers it, so that it can be saved.
     */
    private void addAnswer(Entry entry, SavedAnswer saved, Map<Stop, StopForAi> stops) {
        entry.saved = saved;
        this.conversationStops = stops;
        this.addEntry(entry);
        if (!this.restoring) {
            this.historyListener.run();
        }
    }

    private static final int HISTORY_VERSION = 1;

    /**
     * Returns the answers of the model about the current tours (with the state of the remark
     * suggestions) as JSON, so that they can be shown again after a restart of the IDE (see
     * {@link #restoreHistory}). Returns null if there are none.
     */
    String exportHistory() {
        final Map<Stop, StopForAi> stops = this.conversationStops;
        final JsonArray answers = new JsonArray();
        for (final Entry entry : this.entries) {
            if (entry.saved == null || entry.conversation != this.conversation || stops == null) {
                continue;
            }
            final SavedAnswer a = entry.saved;
            final JsonObject json = new JsonObject()
                    .add("kind", a.kind.name())
                    .add("title", a.title)
                    .add("text", a.answer.getText())
                    .add("omittedDiffs", a.omittedDiffs)
                    .add("stop", a.stop);
            if (a.answer.getModel() != null) {
                json.add("model", a.answer.getModel());
            }
            if (a.answer.getStructured() != null) {
                json.add("structured", a.answer.getStructured());
            }
            if (a.remarks != null) {
                final JsonArray remarks = new JsonArray();
                for (final RemarkForAi r : a.remarks) {
                    remarks.add(new JsonObject().add("number", r.getNumber()).add("round", r.getRound())
                            .add("kind", r.getKind()).add("state", r.getState()).add("location", r.getLocation())
                            .add("text", r.getText()));
                }
                json.add("remarks", remarks);
            }
            if (entry.suggestions != null) {
                final JsonArray states = new JsonArray();
                for (final Suggestion suggestion : entry.suggestions) {
                    states.add(suggestion.accepted ? "accepted" : suggestion.rejected ? "rejected" : "");
                }
                json.add("suggestions", states);
            }
            answers.add(json);
        }
        if (answers.isEmpty()) {
            return null;
        }
        final JsonArray stopList = new JsonArray();
        for (final StopForAi stop : stops.values()) {
            final JsonObject json = new JsonObject().add("number", stop.getNumber()).add("path", stop.getPath());
            if (stop.getLines() != null) {
                json.add("lines", stop.getLines());
            }
            stopList.add(json);
        }
        return new JsonObject().add("version", HISTORY_VERSION).add("stops", stopList).add("answers", answers)
                .toString();
    }

    /**
     * Shows the saved answers (see {@link #exportHistory()}) again for the given tours, e.g. when a review
     * is continued after a restart of the IDE. The answers are only shown if they are about the same stops
     * (same files and lines in the same order); links to the stops and the risk ratings in the "Tours" tab
     * work again. A new opencode session is used for further requests.
     *
     * @return The number of answers shown again (0 if the answers belong to other stops).
     */
    int restoreHistory(String history, ToursInReview currentTours) {
        final JsonObject json;
        try {
            json = Json.parse(history).asObject();
        } catch (final RuntimeException e) {
            Logger.warn("could not read the saved AI answers", e);
            return 0;
        }
        if (json.getInt("version", 0) != HISTORY_VERSION) {
            return 0;
        }
        final Map<Stop, StopForAi> stops = this.describeStopsWithoutDiffs(currentTours);
        if (!sameStops(json.get("stops"), stops)) {
            return 0;
        }
        this.sessionTours = currentTours;
        this.sessionId = null;
        this.changesSent = false;
        this.stopsForAi = null;
        this.conversation++;
        this.stopsByNumber = byNumber(stops);
        this.conversationStops = stops;
        int restored = 0;
        this.restoring = true;
        try {
            for (final JsonValue value : json.get("answers").asArray()) {
                if (this.restoreAnswer(value.asObject(), stops, currentTours)) {
                    restored++;
                }
            }
        } catch (final RuntimeException e) {
            Logger.warn("could not show the saved AI answers again", e);
        } finally {
            this.restoring = false;
        }
        this.render();
        return restored;
    }

    private static boolean sameStops(JsonValue saved, Map<Stop, StopForAi> stops) {
        if (saved == null || !saved.isArray() || saved.asArray().size() != stops.size()) {
            return false;
        }
        int i = 0;
        for (final StopForAi stop : stops.values()) {
            final JsonObject s = saved.asArray().get(i++).asObject();
            if (s.getInt("number", -1) != stop.getNumber() || !stop.getPath().equals(s.getString("path", null))
                    || !Objects.equals(stop.getLines(), s.getString("lines", null))) {
                return false;
            }
        }
        return true;
    }

    private boolean restoreAnswer(JsonObject json, Map<Stop, StopForAi> stops, ToursInReview currentTours) {
        final Kind kind;
        try {
            kind = Kind.valueOf(json.getString("kind", ""));
        } catch (final IllegalArgumentException e) {
            return false;
        }
        final JsonValue structured = json.get("structured");
        final Answer answer = new Answer(json.getString("text", ""), json.getString("model", null),
                structured != null && structured.isObject() ? structured.asObject() : null);
        final int omitted = json.getInt("omittedDiffs", 0);
        final int stop = json.getInt("stop", 0);
        switch (kind) {
        case REMARKS:
            this.remarksSuggested(answer, stops, omitted, stop);
            final JsonValue states = json.get("suggestions");
            final List<Suggestion> shown = this.entries.get(this.entries.size() - 1).suggestions;
            if (states != null && states.isArray() && shown != null && states.asArray().size() == shown.size()) {
                for (int i = 0; i < shown.size(); i++) {
                    final String state = states.asArray().get(i).asString();
                    shown.get(i).accepted = "accepted".equals(state);
                    shown.get(i).rejected = "rejected".equals(state);
                }
            }
            return true;
        case RISKS:
            this.risksRated(answer, stops, currentTours, omitted);
            return true;
        case CHECK:
            final List<RemarkForAi> remarks = new ArrayList<>();
            final List<ReviewRemark> refs = new ArrayList<>();
            for (final JsonValue value : json.get("remarks").asArray()) {
                final JsonObject r = value.asObject();
                final RemarkForAi remark = new RemarkForAi(r.getInt("number", 0), r.getInt("round", 0),
                        r.getString("kind", ""), r.getString("state", ""), r.getString("location", ""),
                        r.getString("text", ""));
                remarks.add(remark);
                refs.add(this.findEarlierRemark(remark));
            }
            this.remarksChecked(answer, stops, remarks, refs, omitted);
            return true;
        default:
            this.answered(kind, json.getString("title", kind.title), answer, stops, stop, omitted);
            return true;
        }
    }

    /**
     * Finds the remark of an earlier review round that a saved check refers to (null if it is not known
     * anymore).
     */
    private ReviewRemark findEarlierRemark(RemarkForAi remark) {
        for (final Pair<ReviewRemark, Integer> p : this.earlierRemarks.get()) {
            if (p.getSecond() == remark.getRound() && p.getFirst().getText().equals(remark.getText())
                    && ReviewRemarksPanel.describePosition(p.getFirst()).equals(remark.getLocation())) {
                return p.getFirst();
            }
        }
        return null;
    }

    /**
     * Describes the stops of the tours like for the model, but without loading the diffs (package-private
     * for tests).
     */
    Map<Stop, StopForAi> describeStopsWithoutDiffs(ToursInReview currentTours) {
        try {
            return this.describeStops(currentTours, null, false);
        } catch (final Exception e) {
            // only loading the diffs can fail
            throw new IllegalStateException(e);
        }
    }

    private void addEntry(Entry entry) {
        entry.conversation = this.conversation;
        this.entries.add(entry);
        if (entry.suggestions != null) {
            this.suggestions.addAll(entry.suggestions);
        }
        this.render();
    }

    private void setStatus(String text) {
        this.status.setText(text);
    }

    /**
     * Renders the panel without scrolling to the top (e.g. after a suggestion has been accepted).
     */
    private void renderKeepingPosition() {
        this.render(this.scrollPane.getViewport().getViewPosition());
    }

    private void render() {
        this.render(new Point(0, 0));
    }

    private void render(Point position) {
        final StringBuilder html = new StringBuilder("<html><body>");
        if (!this.isEnabledInSettings()) {
            html.append("<p><b>AI support is off.</b></p><p>CoRT can ask an AI model via your local"
                    + " <b>opencode</b> for a summary of the changes, for explanations of single stops, for remark"
                    + " suggestions and for the stops that need particular attention. The diffs of the review are"
                    + " then sent to that model; the model may read the project's files but cannot change"
                    + " anything.</p><p><a href='settings'>Enable it in the settings</a></p>");
        } else if (this.entries.isEmpty()) {
            final String model = ReviewToolSettings.getInstance(this.project).getState().aiModel;
            html.append("<p>Ask the AI model (").append(model == null || model.isEmpty()
                    ? "the default of the opencode configuration" : SimpleMarkdown.escape(model))
                    .append(", <a href='settings'>change</a>) about the review:</p><ul>"
                    + "<li><b>Summarize Changes</b>: an overview as a starting point (what, why, what to look at"
                    + " carefully)</li><li><b>Explain Selected Stop</b> (or \"Explain with AI\" in the context menu"
                    + " of a stop): what the stop changes and what to check</li>"
                    + "<li><b>Suggest Remarks</b>: a second opinion after your own review - remarks you can accept"
                    + " (and edit) or reject (for a single stop: \"Suggest Remarks with AI\" in its context"
                    + " menu)</li>"
                    + "<li><b>Rate Risks</b>: which stops need particular attention (shown in the \"Tours\""
                    + " tab)</li>"
                    + "<li><b>Check Remarks</b> (from the second review round on): whether the changes fix the"
                    + " remarks of the earlier rounds</li></ul>"
                    + "<p>The answers are kept with the review progress: when you continue the review after a"
                    + " restart of the IDE, they are shown again.</p>"
                    + "<p>AI answers can be wrong - they help you review, they do not replace it.</p>");
        }
        for (int i = this.entries.size() - 1; i >= 0; i--) {
            final Entry e = this.entries.get(i);
            html.append("<h3").append(e.error ? " style='color:#c75450'" : "").append('>')
                    .append(SimpleMarkdown.escape(e.title)).append("</h3>")
                    .append(e.suggestions == null ? "" : this.suggestionsHtml(e, i))
                    .append(e.html).append("<hr>");
        }
        html.append("</body></html>");
        this.content.setText(html.toString());
        // the new text is laid out later
        SwingUtilities.invokeLater(() -> this.scrollPane.getViewport().setViewPosition(position));
    }

    private static String firstLine(String text) {
        final int newline = text.indexOf('\n');
        return newline < 0 ? text : text.substring(0, newline);
    }

    /**
     * Returns the text of the shown answers (for tests).
     */
    String getShownText() {
        try {
            return this.content.getDocument().getText(0, this.content.getDocument().getLength());
        } catch (final javax.swing.text.BadLocationException e) {
            return "";
        }
    }

}
