package org.fuin.porelo.planner.store;

import java.net.URI;
import org.jspecify.annotations.Nullable;

/**
 * An issue the board leaves out because its tracker's Status is none of the workflow's states, or
 * it has none; a move into a state of the workflow puts it on the board again.
 *
 * @param id its tracker reference
 * @param repository the name of the repository that holds it
 * @param title its title
 * @param status the Status the tracker holds for it, as it holds it; empty when it holds none
 * @param story the story it stands under, when the board holds that story
 * @param parent the issue it is a sub-issue of, on the board or outside its workflow too
 * @param link where a person reads it on the forge, if known
 * @param worker who works it, which decides whether it may be moved into a working state by hand
 */
public record OutsideIssue(
        String id,
        String repository,
        String title,
        String status,
        @Nullable String story,
        @Nullable String parent,
        @Nullable URI link,
        Worker worker) {

    /** Checks the ids and the repository's name. */
    public OutsideIssue {
        Ids.issue(id);
        Ids.name(repository);
        // A reference into another repository would write an issue there.
        if (Ids.isReference(id) && !Ids.repositoryOf(id).equals(repository)) {
            throw new IllegalArgumentException(
                    "Issue " + id + " does not stand in its repository " + repository);
        }
        if (parent != null) {
            Ids.issue(parent);
        }
    }
}
