package de.setsoftware.reviewtool.intellij;

import java.awt.BorderLayout;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import javax.swing.JEditorPane;
import javax.swing.JPanel;
import javax.swing.event.HyperlinkEvent;

import com.intellij.diff.DiffManager;
import com.intellij.diff.DiffRequestPanel;
import com.intellij.diff.requests.MessageDiffRequest;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.HTMLEditorKitBuilder;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;

import de.setsoftware.reviewtool.base.Logger;
import de.setsoftware.reviewtool.model.changestructure.Stop;

/**
 * Shows details of the selected review stop below the tours: a short description (file, lines,
 * classification, visit state), its context (e.g. the stops it calls and the tests that use it) and the
 * diff of the stop's file, scrolled to the stop. By default, the
 * diff only contains the changed sections of the stop (with some context) instead of the whole file.
 * This is the IntelliJ counterpart of the Eclipse "Review stop info" view with its combined diff viewer.
 */
final class StopDetailsPanel extends JPanel {

    private static final long serialVersionUID = 1L;

    private final transient Project project;
    private final JBLabel header = new JBLabel();
    /** The context of the stop, with links to other stops ("stop:...") and files ("file:..."). */
    private final JEditorPane context = new JEditorPane();
    private transient Consumer<String> linkListener;
    private String shownContext = "";
    private final JBCheckBox onlyStopChanges = new JBCheckBox("Only the stop's changes");
    private final transient DiffRequestPanel diffPanel;
    private final AtomicInteger request = new AtomicInteger();
    private transient Stop shownStop;
    private boolean noStopShown;
    private volatile Stop loadedStop;
    private transient StopDiffViewer.StopDiffData loadedData;

    StopDetailsPanel(Project project, Disposable parent) {
        super(new BorderLayout());
        this.project = project;
        this.diffPanel = DiffManager.getInstance().createRequestPanel(project, parent, null);
        this.header.setBorder(JBUI.Borders.empty(4));
        this.onlyStopChanges.setSelected(StopDiffViewer.isOnlyStopChanges());
        this.onlyStopChanges.setToolTipText(
                "Show only the changed sections of the stop (with " + StopDiffViewer.CONTEXT_LINES
                + " lines of context) instead of the whole file with all its changes");
        this.onlyStopChanges.addActionListener((e) -> {
            StopDiffViewer.setOnlyStopChanges(this.onlyStopChanges.isSelected());
            this.showLoadedDiff();
        });
        this.context.setEditable(false);
        this.context.setEditorKit(new HTMLEditorKitBuilder().withWordWrapViewFactory().build());
        this.context.setBackground(UIUtil.getPanelBackground());
        this.context.setBorder(JBUI.Borders.empty(0, 4, 4, 4));
        this.context.setVisible(false);
        this.context.addHyperlinkListener((e) -> {
            if (e.getEventType() == HyperlinkEvent.EventType.ACTIVATED && this.linkListener != null) {
                this.linkListener.accept(e.getDescription());
            }
        });
        final JPanel headerLine = new JPanel(new BorderLayout());
        headerLine.add(this.header, BorderLayout.CENTER);
        headerLine.add(this.onlyStopChanges, BorderLayout.EAST);
        final JPanel north = new JPanel(new BorderLayout());
        north.add(headerLine, BorderLayout.NORTH);
        north.add(this.context, BorderLayout.CENTER);
        this.add(north, BorderLayout.NORTH);
        this.add(this.diffPanel.getComponent(), BorderLayout.CENTER);
        this.showStop(null, null);
    }

    /**
     * Sets the action for the links in the context (it gets the link target, e.g. "stop:3").
     */
    void setLinkListener(Consumer<String> listener) {
        this.linkListener = listener;
    }

    /**
     * Shows the context of the stop (HTML body content), or hides it if it is null or empty.
     */
    void setContext(String html) {
        final boolean visible = html != null && !html.isEmpty();
        final String text = visible ? "<html><body>" + html + "</body></html>" : "";
        // setting the same text again would reset the scroll position and flicker
        if (!text.equals(this.shownContext)) {
            this.shownContext = text;
            this.context.setText(text);
            this.context.setCaretPosition(0);
            this.context.setVisible(visible);
            this.revalidate();
        }
    }

    Stop getShownStop() {
        return this.shownStop;
    }

    /**
     * The stop whose diff is currently shown (null while it is loading).
     */
    Stop getLoadedStop() {
        return this.loadedStop;
    }

    /**
     * Shows the given stop (or a hint if it is null). The file contents are loaded in the background;
     * if another stop is selected in the meantime, the outdated result is discarded.
     */
    void showStop(Stop stop, String description) {
        this.header.setText(description == null ? "Select a stop to see its details and diff." : description);
        this.header.setToolTipText(stop == null ? null : stop.getMostRecentFile().getPath());
        if (stop == this.shownStop && (stop != null || this.noStopShown)) {
            // only the description changed (e.g. the visit state), the diff is still valid or loading
            return;
        }
        // a newer request invalidates the results of older ones that are still loading
        final int current = this.request.incrementAndGet();
        this.noStopShown = stop == null;
        if (stop == null) {
            this.shownStop = null;
            this.diffPanel.setRequest(new MessageDiffRequest("No stop selected"));
            return;
        }
        this.shownStop = stop;
        this.loadedStop = null;
        this.loadedData = null;
        if (stop.isBinaryChange()) {
            this.diffPanel.setRequest(new MessageDiffRequest("Binary change - no textual diff"));
            return;
        }
        this.diffPanel.setRequest(new MessageDiffRequest("Loading diff..."));
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                final StopDiffViewer.StopDiffData data = StopDiffViewer.load(stop);
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (current == this.request.get() && !this.project.isDisposed()) {
                        this.loadedData = data;
                        this.loadedStop = stop;
                        this.showLoadedDiff();
                    }
                });
            } catch (final Exception e) {
                Logger.warn("could not load the diff of the stop", e);
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (current == this.request.get()) {
                        this.diffPanel.setRequest(new MessageDiffRequest("Could not load the diff: " + e));
                    }
                });
            }
        });
    }

    /**
     * Shows the diff of the loaded stop, with only the stop's changes or the whole file.
     */
    private void showLoadedDiff() {
        final Stop stop = this.loadedStop;
        final StopDiffViewer.StopDiffData data = this.loadedData;
        if (stop == null || data == null) {
            return;
        }
        this.diffPanel.setRequest(StopDiffViewer.createRequest(
                this.project, stop, data, this.onlyStopChanges.isSelected()));
    }

    /**
     * Returns true iff only the stop's changes are shown in the diff (and not the whole file).
     */
    boolean isShowingOnlyStopChanges() {
        return this.onlyStopChanges.isSelected() && this.loadedData != null && this.loadedData.getExcerpt() != null;
    }

    void setOnlyStopChanges(boolean value) {
        this.onlyStopChanges.setSelected(value);
        StopDiffViewer.setOnlyStopChanges(value);
        this.showLoadedDiff();
    }

    /**
     * Forgets the shown stop, so that it is reloaded on the next selection (e.g. after the stops
     * were updated with local changes).
     */
    void forgetShownStop() {
        this.shownStop = null;
    }

}
