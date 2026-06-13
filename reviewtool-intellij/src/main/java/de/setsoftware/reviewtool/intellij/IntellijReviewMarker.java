package de.setsoftware.reviewtool.intellij;

import java.util.HashMap;
import java.util.Map;

import com.intellij.openapi.vfs.VirtualFile;

import de.setsoftware.reviewtool.intellij.IntellijMarkerFactory.MarkerHandle;
import de.setsoftware.reviewtool.model.remarks.IReviewMarker;
import de.setsoftware.reviewtool.model.remarks.Position;
import de.setsoftware.reviewtool.model.remarks.ReviewRemarkException;

/**
 * IntelliJ implementation of {@link IReviewMarker}. It collects the attributes that the core sets
 * while building/parsing a review remark and renders them as a gutter marker via the
 * {@link IntellijMarkerFactory}.
 */
public final class IntellijReviewMarker implements IReviewMarker {

    private final IntellijMarkerFactory factory;
    private final VirtualFile file;
    private final Map<String, String> attributes = new HashMap<>();

    private String message = "";
    private int line;
    private boolean warning = true;
    private MarkerHandle handle;

    IntellijReviewMarker(IntellijMarkerFactory factory, Position position, VirtualFile file) {
        this.factory = factory;
        this.file = file;
        this.line = position.getLine();
    }

    @Override
    public void delete() throws ReviewRemarkException {
        IntellijMarkerFactory.runOnEdt(() -> {
            this.disposeHandle();
            this.factory.forget(this);
        });
    }

    @Override
    public void setMessage(String newText) throws ReviewRemarkException {
        this.message = newText;
    }

    @Override
    public String getMessage() throws ReviewRemarkException {
        return this.message;
    }

    @Override
    public void setAttribute(String attributeName, int value) throws ReviewRemarkException {
        this.attributes.put(attributeName, Integer.toString(value));
    }

    @Override
    public void setAttribute(String attributeName, String value) throws ReviewRemarkException {
        this.attributes.put(attributeName, value);
    }

    @Override
    public String getAttribute(String attributeName, String defaultValue) throws ReviewRemarkException {
        return this.attributes.getOrDefault(attributeName, defaultValue);
    }

    @Override
    public void setLineNumber(int line) {
        this.line = line;
    }

    @Override
    public void setSeverityInfo() {
        this.warning = false;
    }

    @Override
    public void setSeverityWarning() {
        this.warning = true;
    }

    /**
     * Renders this marker in the editor gutter. Must be called on the EDT.
     */
    void render() {
        this.disposeHandle();
        if (this.file == null || this.line <= 0) {
            return;
        }
        this.handle = this.factory.showGutterMarker(
                this.file, this.line, IntellijMarkerFactory.reviewIcon(this.warning), this.message);
    }

    void disposeHandle() {
        if (this.handle != null) {
            this.handle.dispose();
            this.handle = null;
        }
    }

}
