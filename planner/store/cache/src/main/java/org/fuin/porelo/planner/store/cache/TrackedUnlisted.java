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
 * @param author the login of the account that opened it, or null when none was given
 * @param authorAssociation how that account stands to the repository, or null when none was given
 */
public record TrackedUnlisted(
        String id,
        String repository,
        int number,
        String title,
        @Nullable String type,
        @Nullable String author,
        @Nullable String authorAssociation) {

    /** Requires the id, the repository and the title. */
    public TrackedUnlisted {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(repository, "repository");
        Objects.requireNonNull(title, "title");
    }

    /**
     * An issue whose author is not known.
     *
     * @param id its own id at the tracker
     * @param repository the project's repository whose list gave it
     * @param number its number in its repository
     * @param title its title
     * @param type the name of its issue type at the tracker, or null when it has none
     */
    public TrackedUnlisted(
            final String id,
            final String repository,
            final int number,
            final String title,
            final @Nullable String type) {
        this(id, repository, number, title, type, null, null);
    }
}
