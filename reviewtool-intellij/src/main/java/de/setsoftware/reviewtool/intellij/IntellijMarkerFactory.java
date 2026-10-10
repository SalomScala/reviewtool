package de.setsoftware.reviewtool.intellij;

import java.awt.Color;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

import javax.swing.Icon;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionGroup;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.impl.DocumentMarkupModel;
import com.intellij.openapi.editor.markup.GutterIconRenderer;
import com.intellij.openapi.editor.markup.HighlighterLayer;
import com.intellij.openapi.editor.markup.HighlighterTargetArea;
import com.intellij.openapi.editor.markup.MarkupModel;
import com.intellij.openapi.editor.markup.RangeHighlighter;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.ui.popup.ListPopup;
import com.intellij.openapi.util.IconLoader;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.ColorUtil;
import com.intellij.ui.awt.RelativePoint;

/**
 * Creates and manages the gutter markers that visualize review remarks and review tour stops
 * inside the IntelliJ editors.
 *
 * <p>In contrast to Eclipse, where markers are persisted on the workspace resources, IntelliJ
 * markers are transient {@link RangeHighlighter}s that live in the document markup model. They are
 * therefore re-created from the review data / tours each time and removed via the {@code clear*}
 * methods. Review remark markers carry a popup menu (the IntelliJ counterpart of the Eclipse
 * marker quick-fixes) with the resolution actions.
 */
public final class IntellijMarkerFactory {

    // the colors depend on the editor color scheme (which can be light while the IDE theme is dark
    // and vice versa), so they are not JBColors, which follow the IDE theme
    private static final Color ACTIVE_BACKGROUND_LIGHT = new Color(0xE6EFFC);
    private static final Color ACTIVE_BACKGROUND_DARK = new Color(0x27324A);
    private static final Color INACTIVE_BACKGROUND_LIGHT = new Color(0xF2F2F2);
    private static final Color INACTIVE_BACKGROUND_DARK = new Color(0x323438);
    private static final Color ACTIVE_STRIPE_LIGHT = new Color(0x3E7BD6);
    private static final Color ACTIVE_STRIPE_DARK = new Color(0x5E8AD6);
    private static final Color INACTIVE_STRIPE_LIGHT = new Color(0xB0B0B0);
    private static final Color INACTIVE_STRIPE_DARK = new Color(0x707070);

    private static final Icon STOP_ICON =
            IconLoader.getIcon("/icons/cortStop.svg", IntellijMarkerFactory.class);
    private static final Icon INACTIVE_STOP_ICON =
            IconLoader.getIcon("/icons/cortStopInactive.svg", IntellijMarkerFactory.class);

    private final Project project;
    private final List<MarkerHandle> remarkHandles = new ArrayList<>();
    private final List<IntellijStopMarker> stopMarkers = new ArrayList<>();

    public IntellijMarkerFactory(Project project) {
        this.project = project;
    }

    public Project getProject() {
        return this.project;
    }

    /**
     * Adds a gutter marker for a review remark at the given line, with a popup menu containing the
     * resolution actions. Must be called on the EDT.
     */
    public void addRemarkMarker(
            VirtualFile file, int line, boolean warning, String tooltip, ActionGroup popupActions) {
        runOnEdt(() -> ApplicationManager.getApplication().runReadAction(() -> {
            final MarkerHandle handle = this.showGutterMarker(
                    file, line, reviewIcon(warning), tooltip, popupActions);
            if (handle != null) {
                this.remarkHandles.add(handle);
            }
        }));
    }

    /**
     * Removes all review remark markers from the editors.
     */
    public void clearReviewMarkers() {
        runOnEdtAndWait(() -> {
            for (final MarkerHandle handle : new ArrayList<>(this.remarkHandles)) {
                handle.dispose();
            }
            this.remarkHandles.clear();
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
        runOnEdt(() -> ApplicationManager.getApplication().runReadAction(marker::render));
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

    void forget(IntellijStopMarker marker) {
        this.stopMarkers.remove(marker);
    }

    /**
     * Adds a gutter highlighter for the given line to the markup model of the given file.
     * Returns a handle that allows to remove it again, or null if the file has no document.
     * Must be called on the EDT.
     */
    MarkerHandle showGutterMarker(VirtualFile file, int line, Icon icon, String tooltip, ActionGroup popupActions) {
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
        highlighter.setGutterIconRenderer(new CortGutterIconRenderer(icon, tooltip, popupActions));
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
        final boolean dark = isDarkEditor();
        final TextAttributes attributes = new TextAttributes();
        attributes.setBackgroundColor(active
                ? (dark ? ACTIVE_BACKGROUND_DARK : ACTIVE_BACKGROUND_LIGHT)
                : (dark ? INACTIVE_BACKGROUND_DARK : INACTIVE_BACKGROUND_LIGHT));
        final RangeHighlighter highlighter = markup.addRangeHighlighter(
                doc.getLineStartOffset(from),
                doc.getLineEndOffset(to),
                HighlighterLayer.SELECTION - 1,
                attributes,
                HighlighterTargetArea.LINES_IN_RANGE);
        highlighter.setErrorStripeMarkColor(active
                ? (dark ? ACTIVE_STRIPE_DARK : ACTIVE_STRIPE_LIGHT)
                : (dark ? INACTIVE_STRIPE_DARK : INACTIVE_STRIPE_LIGHT));
        highlighter.setErrorStripeTooltip(tooltip);
        highlighter.setGutterIconRenderer(new CortGutterIconRenderer(icon, tooltip, null));
        return new MarkerHandle(markup, highlighter);
    }

    private static boolean isDarkEditor() {
        return ColorUtil.isDark(EditorColorsManager.getInstance().getGlobalScheme().getDefaultBackground());
    }

    static Icon reviewIcon(boolean warning) {
        return warning ? AllIcons.General.Warning : AllIcons.General.Information;
    }

    static Icon stopIcon(boolean active) {
        return active ? STOP_ICON : INACTIVE_STOP_ICON;
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
     * Gutter icon renderer that shows the remark/stop message as tooltip and optionally offers a
     * popup menu with actions (used for the review remark quick-fixes).
     */
    private static final class CortGutterIconRenderer extends GutterIconRenderer {
        private final Icon icon;
        private final String tooltip;
        private final ActionGroup popupActions;

        CortGutterIconRenderer(Icon icon, String tooltip, ActionGroup popupActions) {
            this.icon = icon;
            this.tooltip = tooltip;
            this.popupActions = popupActions;
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
            return Alignment.RIGHT;
        }

        @Override
        public ActionGroup getPopupMenuActions() {
            return this.popupActions;
        }

        /**
         * A click shows the actions in a popup whose title is the (first line of the) tooltip, so that
         * it is clear which remark the actions refer to.
         */
        @Override
        public AnAction getClickAction() {
            if (this.popupActions == null) {
                return null;
            }
            return new DumbAwareAction() {
                @Override
                public void actionPerformed(AnActionEvent e) {
                    final String firstLine = CortGutterIconRenderer.this.tooltip == null
                            ? "" : CortGutterIconRenderer.this.tooltip.split("\n")[0];
                    final String title = firstLine.length() > 80 ? firstLine.substring(0, 77) + "..." : firstLine;
                    final ListPopup popup = JBPopupFactory.getInstance().createActionGroupPopup(
                            title.isEmpty() ? null : title, CortGutterIconRenderer.this.popupActions,
                            e.getDataContext(), JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, true);
                    if (e.getInputEvent() instanceof MouseEvent) {
                        popup.show(new RelativePoint((MouseEvent) e.getInputEvent()));
                    } else {
                        popup.showInBestPositionFor(e.getDataContext());
                    }
                }
            };
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
