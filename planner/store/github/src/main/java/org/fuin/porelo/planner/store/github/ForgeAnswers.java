package org.fuin.porelo.planner.store.github;

import java.util.List;
import org.fuin.porelo.forge.github.Delivery;
import org.fuin.porelo.forge.github.GitHubException;
import org.fuin.porelo.planner.store.ForgeAnswer;
import org.fuin.porelo.planner.store.StoreException;
import org.jspecify.annotations.Nullable;

/** Keeps GitHub's own answer on the refusal a failed call is turned into, free of the token. */
final class ForgeAnswers {

    private ForgeAnswers() {}

    /**
     * Gives a refusal GitHub's answer to the call that failed, when GitHub answered it and the
     * refusal holds none yet.
     *
     * @param refusal the store's refusal for the failure
     * @param failure what the call threw
     * @param token the token the call carried, taken out of the answer's words; empty when none
     * @return the refusal
     */
    static StoreException kept(
            final StoreException refusal, final RuntimeException failure, final String token) {
        final GitHubException answered = answered(failure);
        if (answered != null && refusal.answer().isEmpty()) {
            refusal.answered(
                    ForgeAnswer.of(
                            answered.status(),
                            answered.forgeMessage(),
                            answered.forgeDetails(),
                            answered.requestId(),
                            List.of(token)));
        }
        return refusal;
    }

    /** GitHub's answered refusal behind a failure, a part of a save written before it or not. */
    private static @Nullable GitHubException answered(final RuntimeException failure) {
        final RuntimeException cause =
                failure instanceof BoardWriter.AfterWriting after ? after.failure() : failure;
        return cause instanceof GitHubException refused && refused.delivery() == Delivery.ANSWERED
                ? refused
                : null;
    }
}
