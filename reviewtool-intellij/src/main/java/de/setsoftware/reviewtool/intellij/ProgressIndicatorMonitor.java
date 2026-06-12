package de.setsoftware.reviewtool.intellij;

import com.intellij.openapi.progress.ProgressIndicator;

import de.setsoftware.reviewtool.model.api.ICortProgressMonitor;

/**
 * Adapts an IntelliJ {@link ProgressIndicator} to the progress monitor interface of the CoRT core.
 */
public class ProgressIndicatorMonitor implements ICortProgressMonitor {

    private final ProgressIndicator indicator;

    public ProgressIndicatorMonitor(ProgressIndicator indicator) {
        this.indicator = indicator;
    }

    @Override
    public boolean isCanceled() {
        return this.indicator.isCanceled();
    }

    @Override
    public void beginTask(String name, int totalWork) {
        this.indicator.setText(name);
    }

    @Override
    public void subTask(String name) {
        this.indicator.setText2(name);
    }

    @Override
    public void done() {
        this.indicator.setText2("");
    }

}
