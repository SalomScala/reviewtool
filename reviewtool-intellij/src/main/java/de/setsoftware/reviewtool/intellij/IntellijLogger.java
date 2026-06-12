package de.setsoftware.reviewtool.intellij;

import de.setsoftware.reviewtool.base.Logger;

/**
 * Routes the CoRT core logging to the IntelliJ log.
 */
public class IntellijLogger extends Logger {

    private static final com.intellij.openapi.diagnostic.Logger LOG =
            com.intellij.openapi.diagnostic.Logger.getInstance("CoRT");

    @Override
    protected void log(int status, String message) {
        this.log(status, message, null);
    }

    @Override
    protected void log(int status, String message, Throwable exception) {
        switch (status) {
        case 4:
        case 2:
            //don't use LOG.error here because that would trigger IntelliJ's fatal error reporting
            //  for conditions that the Eclipse version treats as normal log output
            LOG.warn(message, exception);
            break;
        case 1:
            LOG.info(message, exception);
            break;
        default:
            LOG.debug(message, exception);
            break;
        }
    }

}
