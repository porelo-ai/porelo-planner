package org.fuin.porelo.planner.store.git;

import org.fuin.porelo.planner.store.Ids;
import org.jspecify.annotations.Nullable;

/**
 * One repository of a project, as the store reads it.
 *
 * @param name its name, which names its clone
 * @param url its clone URL
 * @param branch its branch, or {@code null} for the remote's default
 */
public record Repository(String name, CloneUrl url, @Nullable String branch) {

    /** Checks the name. */
    public Repository {
        Ids.name(name);
    }
}
