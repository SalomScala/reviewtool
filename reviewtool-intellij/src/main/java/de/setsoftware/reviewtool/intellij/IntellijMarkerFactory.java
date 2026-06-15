package de.setsoftware.reviewtool.intellij;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

import javax.swing.Icon;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.impl.DocumentMarkupModel;
import com.intellij.openapi.editor.markup.GutterIconRenderer;
import com.intellij.openapi.editor.markup.HighlighterLayer;
import com.intellij.openapi.editor.markup.HighlighterTargetArea;
import com.intellij.openapi.editor.markup.MarkupModel;
import com.intellij.openapi.editor.markup.RangeHighlighter;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.JBColor;

import de.setsoftware.reviewtool.model.remarks.IMarkerFactory;
import de.setsoftware.reviewtool.model.remarks.IReviewMarker;
import de.setsoftware.reviewtool.model.remarks.Position;
import de.setsoftware.reviewtool.model.remarks.ReviewRemarkException;

/**
 * Creates and manages the gutter markers that visualize review remarks and review tour stops
 * inside the IntelliJ editors. It implements the platform-independent {@link IMarkerFactory}
 * abstraction (used while parsing the review data) and offers additional helper methods for the
 * tour stop markers.
 *
 * <p>In contrast to Eclipse, where markers are persisted on the workspace resources, IntelliJ
 * markers are transient {@link RangeHighlighter}s that live in the document markup model. They are
 * therefore re-created from the review data / tours each time and removed via the {@code clear*}
 * methods.
 */
public final class IntellijMarkerFactory implements IMarkerFactory {

    private static final JBColor ACTIVE_BACKGROUND = new JBColor(new Color(0xD8E8FF), new Color(0x2E436E));
    private static final JBColor INACTIVE_BACKGROUND = new JBColor(new Color(0xEDEDED), new Color(0x3A3C3F));
    private static final JBColor ACTIVE_STRIPE = new JBColor(new Color(0x3E7BD6), new Color(0x5E8AD6));
    private static final JBColor INACTIVE_STRIPE = new JBColor(new Color(0xB0B0B0), new Color(0x707070));

    private final Project project;
    private final List<IntellijReviewMarker> reviewMarkers = new ArrayList<>();
    private final List<IntellijStopMarker> stopMarkers = new ArrayList<>();

    public IntellijMarkerFactory(Project project) {
        this.project = project;
    }

    public Project getProject() {
        return this.project;
    }

    @Override
    public IReviewMarker createMarker(Position pos) throws ReviewRemarkException {
        final VirtualFile file = pos.getShortFileName() == null
                ? null
                : IntellijFileResolver.findByShortName(this.project, pos.getShortFileName());
        final IntellijReviewMarker marker = new IntellijReviewMarker(this, pos, file);
        this.reviewMarkers.add(marker);
        return marker;
    }

    /**
     * Renders all currently known review markers in the editor gutters.
     * Must be called after the review data has been parsed (so all attributes are set).
     */
    public void renderReviewMarkers() {
        runOnEdt(() -> {
            for (final IntellijReviewMarker marker : new ArrayList<>(this.reviewMarkers)) {
                marker.render();
            }
        });
    }

    /**
     * Removes all review remark markers from the editors.
     */
    public void clearReviewMarkers() {
        runOnEdtAndWait(() -> {
            for (final IntellijReviewMarker marker : new ArrayList<>(this.reviewMarkers)) {
                marker.disposeHandle();
            }
            this.reviewMarkers.clear();
        });
    }

    /**
     * Creates a marker for a review tour stop at the given absolute file and line range and renders it.
     */
    public IntellijStopMarker createStopMarker(
            VirtualFile file, int fromLine, int toLine, boolean tourActive, String message) {
        final IntellijStopMarker marker =
                new IntellijStopMarker(this, file, fromLine, toLine, tourActive, message);
        this.stopMarkers.add(marker);
        runOnEdt(marker::render);
        return marker;
    }

    /**
     * Removes all tour stop markers from the editors.
     */
    public void clearStopMarkers() {
        runOnEdtAndWait(() -> {
            for (final IntellijStopMarker marker : new ArrayList<>(this.stopMarkers)) {
                marker.disposeHandle();
            }
            this.stopMarkers.clear();
        });
    }

    void forget(IntellijReviewMarker marker) {
        this.reviewMarkers.remove(marker);
    }

    void forget(IntellijStopMarker marker) {
        this.stopMarkers.remove(marker);
    }

    /**
     * Adds a gutter highlighter for the given line to the markup model of the given file.
     * Returns a handle that allows to remove it again, or null if the file has no document.
     * Must be called on the EDT.
     */
    MarkerHandle showGutterMarker(VirtualFile file, int line, Icon icon, String tooltip) {
        if (file == null) {
            return null;
        }
        final Document doc = FileDocumentManager.getInstance().getDocument(file);
        if (doc == null || doc.getLineCount() == 0) {
            return null;
        }
        final int lineIndex = Math.max(0, Math.min(line - 1, doc.getLineCount() - 1));
        final MarkupModel markup = DocumentMarkupModel.forDocument(doc, this.project, true);
        final RangeHighlighter highlighter =
                markup.addLineHighlighter(lineIndex, HighlighterLayer.WARNING, null);
        highlighter.setGutterIconRenderer(new CortGutterIconRenderer(icon, tooltip));
        return new MarkerHandle(markup, highlighter);
    }

    /**
     * Highlights the whole changed line range [fromLine, toLine] (1-based) of a tour stop in the
     * editor: the lines get a background color, a mark in the scrollbar (error stripe) and a gutter
     * icon, so the reviewer can see which relevant code locations were changed.
     * Must be called on the EDT.
     */
    MarkerHandle showLineRangeMarker(
            VirtualFile file, int fromLine, int toLine, Icon icon, String tooltip, boolean active) {
        if (file == null) {
            return null;
        }
        final Document doc = FileDocumentManager.getInstance().getDocument(file);
        if (doc == null || doc.getLineCount() == 0) {
            return null;
        }
        final int lastLine = doc.getLineCount() - 1;
        final int from = Math.max(0, Math.min(fromLine - 1, lastLine));
        final int to = Math.max(from, Math.min(toLine - 1, lastLine));
        final MarkupModel markup = DocumentMarkupModel.forDocument(doc, this.project, true);
        final TextAttributes attributes = new TextAttributes();
        attributes.setBackgroundColor(active ? ACTIVE_BACKGROUND : INACTIVE_BACKGROUND);
        final RangeHighlighter highlighter = markup.addRangeHighlighter(
                doc.getLineStartOffset(from),
                doc.getLineEndOffset(to),
                HighlighterLayer.SELECTION - 1,
                attributes,
                HighlighterTargetArea.LINES_IN_RANGE);
        highlighter.setErrorStripeMarkColor(active ? ACTIVE_STRIPE : INACTIVE_STRIPE);
        highlighter.setErrorStripeTooltip(tooltip);
        highlighter.setGutterIconRenderer(new CortGutterIconRenderer(icon, tooltip));
        return new MarkerHandle(markup, highlighter);
    }

    static Icon reviewIcon(boolean warning) {
        return warning ? AllIcons.General.BalloonWarning : AllIcons.General.BalloonInformation;
    }

    static Icon stopIcon(boolean active) {
        return active ? AllIcons.General.ArrowRight : AllIcons.General.ArrowDown;
    }

    static void runOnEdt(Runnable r) {
        final Application app = ApplicationManager.getApplication();
        if (app.isDispatchThread()) {
            r.run();
        } else {
            app.invokeLater(r);
        }
    }

    static void runOnEdtAndWait(Runnable r) {
        final Application app = ApplicationManager.getApplication();
        if (app.isDispatchThread()) {
            r.run();
        } else {
            app.invokeAndWait(r);
        }
    }

    /**
     * Handle for a single highlighter, so it can be removed again.
     */
    static final class MarkerHandle {
        private final MarkupModel model;
        private final RangeHighlighter highlighter;

        MarkerHandle(MarkupModel model, RangeHighlighter highlighter) {
            this.model = model;
            this.highlighter = highlighter;
        }

        void dispose() {
            try {
                this.model.removeHighlighter(this.highlighter);
            } catch (final RuntimeException e) {
                // the document/highlighter may already be gone; ignore
            }
        }
    }

    /**
     * Gutter icon renderer that shows the remark/stop message as tooltip.
     */
    private static final class CortGutterIconRenderer extends GutterIconRenderer {
        private final Icon icon;
        private final String tooltip;

        CortGutterIconRenderer(Icon icon, String tooltip) {
            this.icon = icon;
            this.tooltip = tooltip;
        }

        @Override
        public Icon getIcon() {
            return this.icon;
        }

        @Override
        public String getTooltipText() {
            return this.tooltip;
        }

        @Override
        public Alignment getAlignment() {
            return Alignment.LEFT;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof CortGutterIconRenderer)) {
                return false;
            }
            final CortGutterIconRenderer other = (CortGutterIconRenderer) o;
            return this.icon.equals(other.icon)
                    && java.util.Objects.equals(this.tooltip, other.tooltip);
        }

        @Override
        public int hashCode() {
            return this.icon.hashCode() ^ (this.tooltip == null ? 0 : this.tooltip.hashCode());
        }
    }

}
