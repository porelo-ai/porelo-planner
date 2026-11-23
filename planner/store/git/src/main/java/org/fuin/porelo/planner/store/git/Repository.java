package org.fuin.porelo.planner.store.git;

import org.fuin.porelo.planner.store.Ids;
import org.jspecify.annotations.Nullable;

/**
 * One repository of a project, as the store reads it.
 *
 * @param name its name, which names its clone
 * @param url its clone URL
 * @param branch its branch, or {@code null} for the remote's default
 * @param folder the folder a task reading the whole project finds it in, as {@code project.yml}
 *     names it
 */
public record Repository(String name, CloneUrl url, @Nullable String branch, String folder) {

    /** Checks the name and the folder. */
    public Repository {
        Ids.name(name);
        Ids.name(folder);
    }

    /**
     * A repository whose folder is its name.
     *
     * @param name its name, which names its clone and its folder
     * @param url its clone URL
     * @param branch its branch, or {@code null} for the remote's default
     */
    public Repository(final String name, final CloneUrl url, final @Nullable String branch) {
        this(name, url, branch, name);
    }
}
