package de.setsoftware.reviewtool.intellij;

import java.util.List;
import java.util.function.Supplier;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.openapi.vfs.newvfs.BulkFileListener;
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent;
import com.intellij.openapi.vfs.newvfs.events.VFileEvent;
import com.intellij.util.Alarm;

import de.setsoftware.reviewtool.base.Logger;
import de.setsoftware.reviewtool.model.api.ChangeSourceException;
import de.setsoftware.reviewtool.model.changestructure.ToursInReview;

/**
 * Lets the review stops follow local edits, the IntelliJ counterpart of the Eclipse "ChangeManager":
 * when files in the working copy are saved, the local changes are analyzed (after a short delay, in
 * the background) and the stops' most recent fragments are traced through them, so the stop markers,
 * the jump targets and the view tracking refer to the current line numbers.
 */
final class LocalChangeTracker {

    private static final int DELAY_MS = 2000;

    private final ReviewToolService service;
    private final Supplier<ToursInReview> toursSupplier;
    private final Runnable afterUpdate;
    private final Alarm alarm;
    private volatile boolean enabled = true;

    /**
     * Creates the tracker.
     * @param toursSupplier Supplies the tours to update (may supply null, then nothing is done).
     * @param afterUpdate Called on the EDT after the stops have been updated.
     * @param parent The tracker stops listening when this disposable is disposed.
     */
    LocalChangeTracker(Project project, Supplier<ToursInReview> toursSupplier, Runnable afterUpdate,
            Disposable parent) {
        this.service = ReviewToolService.getInstance(project);
        this.toursSupplier = toursSupplier;
        this.afterUpdate = afterUpdate;
        this.alarm = new Alarm(Alarm.ThreadToUse.POOLED_THREAD, parent);
        project.getMessageBus().connect(parent).subscribe(VirtualFileManager.VFS_CHANGES, new BulkFileListener() {
            @Override
            public void after(List<? extends VFileEvent> events) {
                for (final VFileEvent event : events) {
                    if (event instanceof VFileContentChangeEvent && event.getFile().isInLocalFileSystem()) {
                        LocalChangeTracker.this.scheduleUpdate();
                        return;
                    }
                }
            }
        });
    }

    boolean isEnabled() {
        return this.enabled;
    }

    /**
     * Enables or disables the tracking (the counterpart of the Eclipse "Stop local change tracking").
     * Enabling it analyzes the current local changes.
     */
    void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (enabled) {
            this.scheduleUpdate();
        } else {
            this.alarm.cancelAllRequests();
        }
    }

    /**
     * Schedules an analysis of the local changes, e.g. after the tours have been created (there may
     * be changes that have not been committed yet) or after files have been saved. Requests in
     * quick succession are combined.
     */
    void scheduleUpdate() {
        if (!this.enabled || this.toursSupplier.get() == null || this.alarm.isDisposed()) {
            return;
        }
        this.alarm.cancelAllRequests();
        this.alarm.addRequest(this::updateNow, DELAY_MS);
    }

    /**
     * Analyzes the local changes and updates the stops synchronously. Returns false if there was
     * nothing to update.
     */
    boolean updateNow() {
        final ToursInReview tours = this.toursSupplier.get();
        if (!this.enabled || tours == null) {
            return false;
        }
        try {
            // the whole working copy is analyzed, because the change source replaces its knowledge
            // about the local changes with the result of each analysis
            this.service.analyzeLocalChanges();
            tours.updateMostRecentFragmentsWithLocalChanges();
        } catch (final ChangeSourceException | RuntimeException e) {
            Logger.warn("could not analyze the local changes", e);
            return false;
        }
        ApplicationManager.getApplication().invokeLater(() -> {
            // ignore the result if other tours have been created in the meantime
            if (this.toursSupplier.get() == tours) {
                this.afterUpdate.run();
            }
        });
        return true;
    }

}
