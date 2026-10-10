package de.setsoftware.reviewtool.intellij;

import java.awt.BorderLayout;
import java.util.concurrent.atomic.AtomicInteger;

import javax.swing.JPanel;

import com.intellij.diff.DiffManager;
import com.intellij.diff.DiffRequestPanel;
import com.intellij.diff.requests.MessageDiffRequest;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;

import de.setsoftware.reviewtool.base.Logger;
import de.setsoftware.reviewtool.model.changestructure.Stop;

/**
 * Shows details of the selected review stop below the tours: a short description (file, lines,
 * classification, visit state) and the diff of the stop's file, scrolled to the stop. This is the
 * IntelliJ counterpart of the Eclipse "Review stop info" view with its combined diff viewer.
 */
final class StopDetailsPanel extends JPanel {

    private static final long serialVersionUID = 1L;

    private final transient Project project;
    private final JBLabel header = new JBLabel();
    private final transient DiffRequestPanel diffPanel;
    private final AtomicInteger request = new AtomicInteger();
    private transient Stop shownStop;
    private boolean noStopShown;
    private volatile Stop loadedStop;

    StopDetailsPanel(Project project, Disposable parent) {
        super(new BorderLayout());
        this.project = project;
        this.diffPanel = DiffManager.getInstance().createRequestPanel(project, parent, null);
        this.header.setBorder(JBUI.Borders.empty(4));
        this.add(this.header, BorderLayout.NORTH);
        this.add(this.diffPanel.getComponent(), BorderLayout.CENTER);
        this.showStop(null, null);
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
                        this.diffPanel.setRequest(StopDiffViewer.createRequest(this.project, stop, data));
                        this.loadedStop = stop;
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
     * Forgets the shown stop, so that it is reloaded on the next selection (e.g. after the stops
     * were updated with local changes).
     */
    void forgetShownStop() {
        this.shownStop = null;
    }

}
