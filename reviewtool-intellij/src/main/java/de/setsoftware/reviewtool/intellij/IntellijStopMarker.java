package de.setsoftware.reviewtool.intellij;

import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.vfs.VirtualFile;

import de.setsoftware.reviewtool.intellij.IntellijMarkerFactory.MarkerHandle;
import de.setsoftware.reviewtool.model.changestructure.IStopMarker;

/**
 * IntelliJ implementation of {@link IStopMarker}. Renders a tour stop as a gutter marker and can
 * open the corresponding file at the stop's position.
 */
public final class IntellijStopMarker implements IStopMarker {

    private final IntellijMarkerFactory factory;
    private final VirtualFile file;
    private final int fromLine;
    private final int toLine;
    private final boolean active;
    private final String message;
    private MarkerHandle handle;

    IntellijStopMarker(
            IntellijMarkerFactory factory,
            VirtualFile file,
            int fromLine,
            int toLine,
            boolean active,
            String message) {
        this.factory = factory;
        this.file = file;
        this.fromLine = fromLine;
        this.toLine = toLine;
        this.active = active;
        this.message = message;
    }

    @Override
    public void openEditor(boolean forceTextEditor) {
        if (this.file == null) {
            return;
        }
        IntellijMarkerFactory.runOnEdt(() -> new OpenFileDescriptor(
                this.factory.getProject(),
                this.file,
                Math.max(0, this.fromLine - 1),
                0).navigate(true));
    }

    @Override
    public void delete() {
        IntellijMarkerFactory.runOnEdt(() -> {
            this.disposeHandle();
            this.factory.forget(this);
        });
    }

    /**
     * Renders this stop marker in the editor gutter. Must be called on the EDT.
     */
    void render() {
        this.disposeHandle();
        if (this.file == null) {
            return;
        }
        final String tooltip = this.active ? this.message : this.message + " (inactive tour)";
        this.handle = this.factory.showGutterMarker(
                this.file, this.fromLine, IntellijMarkerFactory.stopIcon(this.active), tooltip);
    }

    int getToLine() {
        return this.toLine;
    }

    void disposeHandle() {
        if (this.handle != null) {
            this.handle.dispose();
            this.handle = null;
        }
    }

}
