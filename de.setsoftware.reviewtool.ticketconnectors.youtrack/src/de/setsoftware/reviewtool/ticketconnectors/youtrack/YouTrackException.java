package de.setsoftware.reviewtool.ticketconnectors.youtrack;

import de.setsoftware.reviewtool.base.ReviewtoolException;

/**
 * A problem in the communication with YouTrack, with a message that tells the user what to do (and
 * the kind of problem, so that the UI can offer a suitable action, e.g. opening the settings).
 */
public class YouTrackException extends ReviewtoolException {

    private static final long serialVersionUID = 1L;

    /**
     * The kinds of problems.
     */
    public enum Problem {
        /** The server cannot be reached (wrong URL, no network, server down). */
        UNREACHABLE,
        /** The server rejected the token (HTTP 401/403). */
        UNAUTHORIZED,
        /** The requested resource (e.g. a ticket) does not exist (HTTP 404). */
        NOT_FOUND,
        /** Any other error answer of the server. */
        SERVER_ERROR
    }

    private final Problem problem;

    public YouTrackException(Problem problem, String message, Exception cause) {
        super(message, cause);
        this.problem = problem;
    }

    public Problem getProblem() {
        return this.problem;
    }

    /**
     * Returns true iff the problem can probably be solved by changing the settings (URL or token).
     */
    public boolean isConfigurationProblem() {
        return this.problem == Problem.UNREACHABLE || this.problem == Problem.UNAUTHORIZED;
    }

}
