package de.setsoftware.reviewtool.intellij;

import java.util.concurrent.atomic.AtomicInteger;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;

import de.setsoftware.reviewtool.model.api.IChangeSourceUi;

/**
 * Adapts the IntelliJ progress and dialog facilities to the change source UI callbacks.
 */
public class ChangeSourceUiAdapter extends ProgressIndicatorMonitor implements IChangeSourceUi {

    private final Project project;

    public ChangeSourceUiAdapter(Project project, ProgressIndicator indicator) {
        super(indicator);
        this.project = project;
    }

    @Override
    public Boolean handleLocalWorkingIncomplete(String detailInfo) {
        final AtomicInteger answer = new AtomicInteger();
        ApplicationManager.getApplication().invokeAndWait(() ->
                answer.set(Messages.showYesNoCancelDialog(
                        this.project, detailInfo, "Code Review Tool", null)));
        if (answer.get() == Messages.YES) {
            return Boolean.TRUE;
        } else if (answer.get() == Messages.NO) {
            return Boolean.FALSE;
        } else {
            return null;
        }
    }

    @Override
    public void increaseTaskNestingLevel() {
    }

    @Override
    public void decreaseTaskNestingLevel() {
    }

}
