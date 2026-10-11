package de.setsoftware.reviewtool.intellij;

import java.awt.BorderLayout;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.swing.JEditorPane;
import javax.swing.JPanel;
import javax.swing.event.HyperlinkEvent;

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
import de.setsoftware.reviewtool.intellij.OpencodeClient.Answer;
import de.setsoftware.reviewtool.intellij.OpencodeClient.OpencodeException;
import de.setsoftware.reviewtool.intellij.ReviewAiPrompts.StopForAi;
import de.setsoftware.reviewtool.model.api.IClassification;
import de.setsoftware.reviewtool.model.api.IFragment;
import de.setsoftware.reviewtool.model.changestructure.Stop;
import de.setsoftware.reviewtool.model.changestructure.Tour;
import de.setsoftware.reviewtool.model.changestructure.ToursInReview;

/**
 * The "AI" tab: AI support for the review via a local opencode (optional, off by default). It asks the
 * model configured in opencode for a summary of the changes as a starting point and for explanations
 * of single stops, and shows the answers. All requests about the same tours go to one opencode session,
 * so the changes are sent only once; the model may read (but not change) the project's files.
 */
final class ReviewAiPanel extends JPanel {

    private static final long serialVersionUID = 1L;

    /**
     * An answer (or an error) shown in the panel.
     */
    private static final class Entry {
        private final String title;
        private final String html;
        private final boolean error;

        Entry(String title, String html, boolean error) {
            this.title = title;
            this.html = html;
            this.error = error;
        }
    }

    private final transient Project project;
    private final transient Supplier<ToursInReview> tours;
    private final transient Supplier<String> reviewTitle;
    private final transient Supplier<Stop> selectedStop;
    private final transient Runnable openSettings;
    private final JEditorPane content = new JEditorPane();
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
        group.add(PanelActions.action("Clear Answers", AllIcons.Actions.GC, () -> !this.entries.isEmpty() && !this.busy,
                this::clear));
        final ActionToolbar toolbar = ActionManager.getInstance().createActionToolbar("CoRT.Ai", group, true);
        toolbar.setTargetComponent(this);
        this.add(toolbar.getComponent(), BorderLayout.NORTH);

        this.content.setEditable(false);
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
            } else if (e.getDescription() != null && e.getDescription().startsWith("stop:")) {
                this.linkToStopClicked(e.getDescription());
            }
        });
        this.add(new JBScrollPane(this.content), BorderLayout.CENTER);
        this.status.setBorder(JBUI.Borders.empty(2, 6));
        this.status.setComponentStyle(UIUtil.ComponentStyle.SMALL);
        this.add(this.status, BorderLayout.SOUTH);
        this.render();
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
        this.ask("Summary of the changes", null);
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
        this.ask("Explanation", stop);
    }

    private void clear() {
        this.entries.clear();
        this.render();
    }

    private void ask(String title, Stop stop) {
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
            this.conversation++;
        }
        this.setBusy(true);
        final String reviewName = this.reviewTitle.get();
        final String fullTitle = stop == null ? title
                : title + " of " + this.relativePath(stop) + (lines(stop) == null ? "" : ":" + lines(stop));
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
                    text.append(stop == null ? ReviewAiPrompts.summaryRequest()
                            : ReviewAiPrompts.explainRequest(stopForAi));
                    String session = ReviewAiPanel.this.sessionId;
                    if (session == null) {
                        session = client.createSession("CoRT review: " + reviewName);
                    }
                    indicator.setText("Waiting for the answer of the model (this can take a minute)...");
                    final Answer answer = client.prompt(session, ReviewAiPrompts.system(Locale.getDefault()),
                            text.toString(), indicator::isCanceled);
                    final String finalSession = session;
                    final int finalOmitted = omitted;
                    final Map<Integer, Stop> numbers = new HashMap<>();
                    for (final Map.Entry<Stop, StopForAi> e : stops.entrySet()) {
                        numbers.put(e.getValue().getNumber(), e.getKey());
                    }
                    final String heading = stopForAi == null ? title : title + " of " + stopForAi.describe();
                    ApplicationManager.getApplication().invokeLater(() -> {
                        if (ReviewAiPanel.this.sessionTours == currentTours) {
                            ReviewAiPanel.this.sessionId = finalSession;
                            ReviewAiPanel.this.changesSent = true;
                            ReviewAiPanel.this.stopsByNumber = numbers;
                        }
                        ReviewAiPanel.this.answered(heading, answer, finalOmitted);
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
     * Describes all stops of the tours for the model (numbered like in the "Tours" tab), loading their
     * diffs. The result is kept for further requests about the same tours.
     */
    private Map<Stop, StopForAi> stopsForAi(ToursInReview currentTours, ProgressIndicator indicator)
            throws Exception {
        final Map<Stop, StopForAi> known = this.stopsForAi;
        if (known != null) {
            return known;
        }
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
            indicator.checkCanceled();
            final Stop stop = ordered.get(i);
            inOrder.put(stop, new StopForAi(i + 1, tourNames.get(i), this.relativePath(stop), lines(stop),
                    classification(stop, currentTours), diff(stop)));
        }
        this.stopsForAi = inOrder;
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

    private void answered(String title, Answer answer, int omittedDiffs) {
        this.setBusy(false);
        final StringBuilder html = new StringBuilder(
                linkStops(SimpleMarkdown.toHtml(answer.getText()), this.stopsByNumber.keySet(), this.conversation));
        if (omittedDiffs > 0) {
            html.append("<p class='hint'>The diffs of ").append(omittedDiffs).append(omittedDiffs == 1 ? " stop" : " stops")
                    .append(" were left out because the changes are too big; the model could read the files.</p>");
        }
        this.addEntry(new Entry(title + (answer.getModel() == null ? "" : "  ·  " + answer.getModel()),
                html.toString(), false));
        this.setStatus("");
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

    private void addEntry(Entry entry) {
        this.entries.add(entry);
        this.render();
    }

    private void setStatus(String text) {
        this.status.setText(text);
    }

    private void render() {
        final StringBuilder html = new StringBuilder("<html><body>");
        if (!this.isEnabledInSettings()) {
            html.append("<p><b>AI support is off.</b></p><p>CoRT can ask the AI model configured in your local"
                    + " <b>opencode</b> for a summary of the changes and for explanations of single stops. The diffs"
                    + " of the review are then sent to that model; the model may read the project's files but"
                    + " cannot change anything.</p><p><a href='settings'>Enable it in the settings</a></p>");
        } else if (this.entries.isEmpty()) {
            html.append("<p>Ask the AI model configured in opencode about the review:</p><ul>"
                    + "<li><b>Summarize Changes</b>: an overview as a starting point (what, why, what to look at"
                    + " carefully)</li><li><b>Explain Selected Stop</b> (or \"Explain with AI\" in the context menu"
                    + " of a stop): what the stop changes and what to check</li></ul>"
                    + "<p>AI answers can be wrong - they help you review, they do not replace it.</p>");
        }
        for (int i = this.entries.size() - 1; i >= 0; i--) {
            final Entry e = this.entries.get(i);
            html.append("<h3").append(e.error ? " style='color:#c75450'" : "").append('>')
                    .append(SimpleMarkdown.escape(e.title)).append("</h3>").append(e.html).append("<hr>");
        }
        html.append("</body></html>");
        this.content.setText(html.toString());
        this.content.setCaretPosition(0);
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
