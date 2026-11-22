package org.fuin.porelo.planner.store.cache;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * An issue a board issue waits on that is no item of the board, as the tracker last answered it.
 *
 * @param id its own id at the tracker
 * @param repository its repository, as the tracker names it with its owner
 * @param number its number in its repository
 * @param closed whether the tracker has it closed, or null when it could not be read
 */
public record TrackedWait(String id, String repository, int number, @Nullable Boolean closed) {

    /** Requires the id and the repository. */
    public TrackedWait {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(repository, "repository");
    }
}
