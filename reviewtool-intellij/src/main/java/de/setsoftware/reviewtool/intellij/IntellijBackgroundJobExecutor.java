package de.setsoftware.reviewtool.intellij;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.util.concurrency.AppExecutorUtil;

import de.setsoftware.reviewtool.base.Logger;
import de.setsoftware.reviewtool.model.api.BackgroundJobExecutor;
import de.setsoftware.reviewtool.model.api.ICortProgressMonitor;

/**
 * Executes CoRT background jobs as IntelliJ background tasks.
 */
public class IntellijBackgroundJobExecutor extends BackgroundJobExecutor {

    private final Map<Object, Object> locks = new ConcurrentHashMap<>();

    @Override
    protected void startJob(
            String name,
            Object mutexResource,
            Function<ICortProgressMonitor, Throwable> job,
            long processingDelay) {

        final Runnable startTask = () -> new Task.Backgroundable(null, name, true) {
            @Override
            public void run(ProgressIndicator indicator) {
                IntellijBackgroundJobExecutor.this.runJob(name, mutexResource, job, indicator);
            }
        }.queue();

        if (processingDelay > 0) {
            AppExecutorUtil.getAppScheduledExecutorService().schedule(
                    () -> ApplicationManager.getApplication().invokeLater(startTask),
                    processingDelay,
                    TimeUnit.MILLISECONDS);
        } else {
            ApplicationManager.getApplication().invokeLater(startTask);
        }
    }

    private void runJob(
            String name,
            Object mutexResource,
            Function<ICortProgressMonitor, Throwable> job,
            ProgressIndicator indicator) {

        final Throwable result;
        if (mutexResource == null) {
            result = job.apply(new ProgressIndicatorMonitor(indicator));
        } else {
            synchronized (this.locks.computeIfAbsent(mutexResource, (Object k) -> new Object())) {
                result = job.apply(new ProgressIndicatorMonitor(indicator));
            }
        }
        if (result instanceof ProcessCanceledException) {
            throw (ProcessCanceledException) result;
        } else if (result != null) {
            Logger.warn("error in background job " + name, result);
        }
    }

    @Override
    protected RuntimeException doCreateOperationCanceledException() {
        return new ProcessCanceledException();
    }

}
