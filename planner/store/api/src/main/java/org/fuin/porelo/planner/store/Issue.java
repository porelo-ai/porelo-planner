package org.fuin.porelo.planner.store;

import java.net.URI;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * An issue as its repository's index and its head state it.
 *
 * @param id the issue's id, its prefix and number, and for a sub-issue its parent's id, a dot and a
 *     number
 * @param repository the name of the repository that holds it
 * @param title its title
 * @param state its state, one of the workflow's
 * @param size its size, if it has one yet
 * @param blockedBy the ids of the issues of its repository it waits on
 * @param parent its parent's id, for a sub-issue
 * @param link where a person reads it on the forge, if known
 * @param text its whole text in the store's format, its title and head included; empty when its
 *     file is gone
 * @param worker who works it; a head that names none is worked by an agent
 * @param risk how much harm its change can do; a head that names none is ordinary, and a sub-issue
 *     is worked with the higher of its own and its parent's
 */
public record Issue(
        String id,
        String repository,
        String title,
        String state,
        @Nullable String size,
        List<String> blockedBy,
        @Nullable String parent,
        @Nullable URI link,
        String text,
        Worker worker,
        Risk risk) {

    /** Checks the ids and the repository's name and copies the list. */
    public Issue {
        Ids.issue(id);
        Ids.name(repository);
        blockedBy = List.copyOf(blockedBy);
        blockedBy.forEach(Ids::issue);
        if (parent != null) {
            Ids.issue(parent);
            if (!id.startsWith(parent + ".")) {
                throw new IllegalArgumentException(
                        "Sub-issue " + id + " does not carry its parent's id " + parent);
            }
        }
    }

    /**
     * An issue whose head names neither worker nor risk, so an agent works it as an ordinary one.
     *
     * @param id the issue's id
     * @param repository the name of the repository that holds it
     * @param title its title
     * @param state its state
     * @param size its size, if it has one yet
     * @param blockedBy the ids of the issues it waits on
     * @param parent its parent's id, for a sub-issue
     * @param link where a person reads it on the forge, if known
     * @param text its whole text, title and head included
     */
    public Issue(
            final String id,
            final String repository,
            final String title,
            final String state,
            final @Nullable String size,
            final List<String> blockedBy,
            final @Nullable String parent,
            final @Nullable URI link,
            final String text) {
        this(
                id,
                repository,
                title,
                state,
                size,
                blockedBy,
                parent,
                link,
                text,
                Worker.AGENT,
                Risk.ORDINARY);
    }
}
