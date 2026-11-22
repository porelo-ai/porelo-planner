package org.fuin.porelo.planner.store;

import java.net.URI;
import java.util.Objects;

/**
 * An open issue of one of the project's repositories that is no item of the project, as one made by
 * hand on the forge is; it is neither on the board nor outside its workflow.
 *
 * @param id its tracker reference
 * @param repository the name of the repository that holds it
 * @param title its title, as the forge holds it
 * @param kind whether the forge marks it as a story or as an issue
 * @param link where a person reads it on the forge
 */
public record UnlistedIssue(String id, String repository, String title, Kind kind, URI link) {

    /** What an issue made outside the project stands for. */
    public enum Kind {

        /** An issue of work. */
        ISSUE,

        /** A story, as the forge's issue type says. */
        STORY
    }

    /** Requires a tracker reference within its own repository, and every part. */
    public UnlistedIssue {
        Ids.name(repository);
        // Only a reference names an issue a later add can find again on the forge.
        if (!Ids.isReference(id) || !Ids.repositoryOf(id).equals(repository)) {
            throw new IllegalArgumentException(
                    "An issue outside the project is named by no reference into " + repository);
        }
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(link, "link");
    }
}
