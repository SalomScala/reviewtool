package de.setsoftware.reviewtool.intellij;

import com.intellij.notification.Notification;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.Project;

import de.setsoftware.reviewtool.base.Logger;
import de.setsoftware.reviewtool.base.ReviewtoolException;

/**
 * Helper to show the balloon notifications of the review tool. Errors caused by a missing or wrong
 * configuration get an "Open Settings" link.
 */
final class IntellijNotifications {

    private static final String GROUP_ID = "CoRT";
    private static final String TITLE = "Code Review Tool";

    private IntellijNotifications() {
    }

    static void info(Project project, String message) {
        notify(project, message, NotificationType.INFORMATION, false);
    }

    /**
     * Shows an information with an action link (e.g. the next step of the workflow).
     */
    static void info(Project project, String message, String actionText, Runnable action) {
        IntellijMarkerFactory.runOnEdt(() -> {
            if (project.isDisposed()) {
                return;
            }
            final Notification notification = NotificationGroupManager.getInstance()
                    .getNotificationGroup(GROUP_ID)
                    .createNotification(TITLE, message, NotificationType.INFORMATION);
            notification.addAction(NotificationAction.createSimpleExpiring(actionText, action));
            notification.notify(project);
        });
    }

    static void warn(Project project, String message) {
        notify(project, message, NotificationType.WARNING, false);
    }

    /**
     * Logs the exception and shows an error notification for it.
     */
    static void error(Project project, String message, Throwable exception) {
        Logger.warn(message, exception);
        final boolean configProblem = exception instanceof ReviewtoolException
                && String.valueOf(exception.getMessage()).contains("not configured");
        final String details = exception.getMessage() != null ? exception.getMessage() : exception.toString();
        notify(project, message + ": " + details, NotificationType.ERROR, configProblem);
    }

    private static void notify(Project project, String message, NotificationType type, boolean withSettingsLink) {
        IntellijMarkerFactory.runOnEdt(() -> {
            if (project.isDisposed()) {
                return;
            }
            final Notification notification = NotificationGroupManager.getInstance()
                    .getNotificationGroup(GROUP_ID)
                    .createNotification(TITLE, message, type);
            if (withSettingsLink) {
                notification.addAction(NotificationAction.createSimpleExpiring("Open settings", () ->
                        ShowSettingsUtil.getInstance().showSettingsDialog(project, ReviewToolConfigurable.class)));
            }
            notification.notify(project);
        });
    }

}
