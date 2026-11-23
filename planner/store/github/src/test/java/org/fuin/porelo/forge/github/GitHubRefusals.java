package org.fuin.porelo.forge.github;

import org.jspecify.annotations.Nullable;

/** Builds the forge client's own failure as GitHub's answer makes it, for the store's tests. */
public final class GitHubRefusals {

    private GitHubRefusals() {}

    /**
     * Answers GitHub's failure of a call.
     *
     * @param status the HTTP status GitHub answered
     * @param message the failure's words
     * @param rateLimited whether GitHub refused for its rate limit
     * @param accepted the permissions GitHub named as accepted, or null when it named none
     * @return the failure
     */
    public static GitHubException answered(
            final int status,
            final String message,
            final boolean rateLimited,
            final @Nullable String accepted) {
        return new GitHubException(
                status,
                message,
                Delivery.ANSWERED,
                null,
                rateLimited,
                accepted,
                false,
                rateLimited ? GitHubExceptions.WITHOUT_A_WORD : null);
    }
}
