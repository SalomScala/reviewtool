package de.setsoftware.reviewtool.intellij;

import com.intellij.notification.Notification;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.Project;

import de.setsoftware.reviewtool.base.Logger;
import de.setsoftware.reviewtool.base.ReviewtoolException;
import de.setsoftware.reviewtool.ticketconnectors.youtrack.YouTrackException;

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

    /**
     * Shows an information with two action links (e.g. "Restore" and "Discard").
     */
    static void info(Project project, String message, String actionText1, Runnable action1,
            String actionText2, Runnable action2) {
        IntellijMarkerFactory.runOnEdt(() -> {
            if (project.isDisposed()) {
                return;
            }
            final Notification notification = NotificationGroupManager.getInstance()
                    .getNotificationGroup(GROUP_ID)
                    .createNotification(TITLE, message, NotificationType.INFORMATION);
            notification.addAction(NotificationAction.createSimpleExpiring(actionText1, action1));
            notification.addAction(NotificationAction.createSimpleExpiring(actionText2, action2));
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
        error(project, message, exception, null);
    }

    /**
     * Logs the exception and shows an error notification for it. Problems with the YouTrack connection
     * are shown with their explanation (instead of the technical exception) and an "Open settings"
     * link; if a retry action is given, the notification offers "Retry".
     */
    static void error(Project project, String message, Throwable exception, Runnable retry) {
        Logger.warn(message, exception);
        final YouTrackException youTrackProblem = findYouTrackProblem(exception);
        final boolean configProblem = youTrackProblem != null
                ? youTrackProblem.isConfigurationProblem()
                : exception instanceof ReviewtoolException
                    && String.valueOf(exception.getMessage()).contains("not configured");
        final String details;
        if (youTrackProblem != null) {
            details = youTrackProblem.getMessage();
        } else {
            details = exception.getMessage() != null ? exception.getMessage() : exception.toString();
        }
        notify(project, message + ": " + details, NotificationType.ERROR, configProblem, retry);
    }

    private static YouTrackException findYouTrackProblem(Throwable exception) {
        for (Throwable t = exception; t != null; t = t.getCause()) {
            if (t instanceof YouTrackException) {
                return (YouTrackException) t;
            }
        }
        return null;
    }

    private static void notify(Project project, String message, NotificationType type, boolean withSettingsLink) {
        notify(project, message, type, withSettingsLink, null);
    }

    private static void notify(Project project, String message, NotificationType type, boolean withSettingsLink,
            Runnable retry) {
        IntellijMarkerFactory.runOnEdt(() -> {
            if (project.isDisposed()) {
                return;
            }
            final Notification notification = NotificationGroupManager.getInstance()
                    .getNotificationGroup(GROUP_ID)
                    .createNotification(TITLE, message, type);
            if (retry != null) {
                notification.addAction(NotificationAction.createSimpleExpiring("Retry", retry));
            }
            if (withSettingsLink) {
                notification.addAction(NotificationAction.createSimpleExpiring("Open settings", () ->
                        ShowSettingsUtil.getInstance().showSettingsDialog(project, ReviewToolConfigurable.class)));
            }
            notification.notify(project);
        });
    }

}
