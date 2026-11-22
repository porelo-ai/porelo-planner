package org.fuin.porelo.forge.github;

import java.time.Duration;
import java.time.Instant;

/** Makes the forge client's failures in a test, whose constructors only its package reaches. */
public final class GitHubExceptions {

    private GitHubExceptions() {}

    /**
     * A refusal GitHub marks as rate limited, with the wait it names.
     *
     * @param status the HTTP status
     * @param message its words
     * @param wait how long GitHub asks to wait
     * @return the failure
     */
    public static GitHubException rateLimited(
            final int status, final String message, final Duration wait) {
        return new GitHubException(
                status,
                message,
                Delivery.ANSWERED,
                new RateLimit(5000L, 0L, Instant.now().plus(wait), wait),
                true,
                null);
    }

    /**
     * A refusal GitHub does not mark as rate limited.
     *
     * @param status the HTTP status
     * @param message its words
     * @return the failure
     */
    public static GitHubException refused(final int status, final String message) {
        return new GitHubException(
                status,
                message,
                Delivery.ANSWERED,
                new RateLimit(5000L, 4000L, Instant.now().plusSeconds(3600), null),
                false,
                null);
    }

    /**
     * A call sent to GitHub that gave no answer, so whether GitHub took it is unknown.
     *
     * @param message its words
     * @return the failure
     */
    public static GitHubException unanswered(final String message) {
        return new GitHubException(0, message, Delivery.SENT_UNANSWERED, null, false, null);
    }
}
