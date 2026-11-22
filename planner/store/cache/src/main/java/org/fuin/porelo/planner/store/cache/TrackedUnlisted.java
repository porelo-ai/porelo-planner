package org.fuin.porelo.planner.store.cache;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * An open issue of one of the project's repositories that is no item of the board, as the tracker's
 * list of the repository's issues last gave it.
 *
 * @param id its own id at the tracker
 * @param repository the project's repository whose list gave it
 * @param number its number in its repository
 * @param title its title
 * @param type the name of its issue type at the tracker, or null when it has none
 */
public record TrackedUnlisted(
        String id, String repository, int number, String title, @Nullable String type) {

    /** Requires the id, the repository and the title. */
    public TrackedUnlisted {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(repository, "repository");
        Objects.requireNonNull(title, "title");
    }
}
