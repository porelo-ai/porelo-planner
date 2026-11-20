package org.fuin.porelo.planner.store.github;

import java.util.List;
import org.fuin.porelo.planner.store.StoreException;

/** Turns a failure of a GitHub call into the store's refusal, scrubbed of the token. */
@FunctionalInterface
public interface AccessRefusals {

    /**
     * Keeps a store refusal as it is, answers a failure after a part of a save was written as
     * partly saved, and any other failure as GitHub unreachable, in words free of the token.
     */
    AccessRefusals DEFAULT =
            (repository, failure, token) -> {
                if (failure instanceof BoardWriter.AfterWriting after) {
                    return after.failure() instanceof StoreException.PartlySaved partly
                            ? partly
                            : new StoreException.PartlySaved(
                                    repository,
                                    List.of(),
                                    "A part of the save was written before a call failed");
                }
                return failure instanceof StoreException refusal
                        ? refusal
                        : new StoreException.Unreachable(
                                repository,
                                Scrubbed.text(
                                        String.valueOf(failure.getMessage()), List.of(token)));
            };

    /**
     * Answers the refusal for a failure.
     *
     * @param repository the repository the call was about, the project's own for a project-wide
     *     call
     * @param failure what the call threw
     * @param token the token the call carried, to be scrubbed from every word; empty when none was
     *     taken
     * @return the store's refusal
     */
    StoreException refusal(String repository, RuntimeException failure, String token);
}
