package de.setsoftware.reviewtool.intellij;

import java.awt.Color;
import java.awt.Font;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.EditorLinePainter;
import com.intellij.openapi.editor.LineExtensionInfo;
import com.intellij.openapi.editor.ex.MarkupModelEx;
import com.intellij.openapi.editor.impl.DocumentMarkupModel;
import com.intellij.openapi.editor.markup.MarkupModel;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.JBColor;

/**
 * Shows the text of a review remark at the end of its line in the editor (like the inline values of
 * the debugger), so that the remarks can be read without hovering over the gutter icons. The text is
 * attached to the remark's highlighter, so it moves with the line when the file is edited.
 */
public final class RemarkLinePainter extends EditorLinePainter {

    /**
     * The text shown for a remark.
     */
    static final class RemarkText {
        private final String text;
        private final boolean open;

        /**
         * @param open True iff the remark still needs to be fixed (it is shown more prominently).
         */
        RemarkText(String text, boolean open) {
            this.text = text;
            this.open = open;
        }

        String getText() {
            return this.text;
        }
    }

    /** The key of the remark text in the remark's highlighter. */
    static final Key<RemarkText> REMARK_TEXT = Key.create("de.setsoftware.reviewtool.remarkText");

    private static final int MAX_LENGTH = 140;
    private static final Color OPEN_COLOR = new JBColor(new Color(0xA0, 0x60, 0x00), new Color(0xD9, 0xA3, 0x43));
    private static final Color DONE_COLOR = new JBColor(new Color(0x80, 0x80, 0x80), new Color(0x80, 0x80, 0x80));

    @Override
    public Collection<LineExtensionInfo> getLineExtensions(Project project, VirtualFile file, int lineNumber) {
        if (project.isDisposed() || !ReviewToolSettings.getInstance(project).getState().showRemarksInEditor) {
            return null;
        }
        final Document document = FileDocumentManager.getInstance().getCachedDocument(file);
        if (document == null || lineNumber < 0 || lineNumber >= document.getLineCount()) {
            return null;
        }
        final MarkupModel markup = DocumentMarkupModel.forDocument(document, project, false);
        if (!(markup instanceof MarkupModelEx)) {
            return null;
        }
        final List<RemarkText> texts = new ArrayList<>();
        ((MarkupModelEx) markup).processRangeHighlightersOverlappingWith(
                document.getLineStartOffset(lineNumber), document.getLineEndOffset(lineNumber), (highlighter) -> {
                    final RemarkText text = highlighter.getUserData(REMARK_TEXT);
                    if (text != null && highlighter.isValid()
                            && document.getLineNumber(highlighter.getStartOffset()) == lineNumber) {
                        texts.add(text);
                    }
                    return true;
                });
        if (texts.isEmpty()) {
            return null;
        }
        final List<LineExtensionInfo> ret = new ArrayList<>();
        for (final RemarkText text : texts) {
            ret.add(new LineExtensionInfo("    ◆ " + abbreviate(text.text),
                    text.open ? OPEN_COLOR : DONE_COLOR, null, null, Font.ITALIC));
        }
        return ret;
    }

    /**
     * The first line of the text, shortened if it is too long for the end of a line.
     */
    static String abbreviate(String text) {
        String s = text.trim();
        final int newline = s.indexOf('\n');
        if (newline >= 0) {
            s = s.substring(0, newline).trim() + " …";
        }
        return s.length() > MAX_LENGTH ? s.substring(0, MAX_LENGTH - 1) + "…" : s;
    }

}
