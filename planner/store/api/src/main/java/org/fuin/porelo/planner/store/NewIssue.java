package org.fuin.porelo.planner.store;

import org.jspecify.annotations.Nullable;

/**
 * An idea a person adds through the planner: a new issue in state Idea of one repository, its
 * number given by the store.
 *
 * @param repository the name of the project's repository it belongs to
 * @param title its title, one line
 * @param mustBeTrue what must be true, the paragraph its file opens with
 * @param size its size, or null when it is not known yet
 */
public record NewIssue(String repository, String title, String mustBeTrue, @Nullable String size) {

    /** Checks the repository's name, so no store resolves a path from an unchecked one. */
    public NewIssue {
        Ids.name(repository);
    }

    /**
     * What a store answers for an added issue.
     *
     * @param id the id the store gave it
     * @param board the board after the save, holding it
     */
    public record Added(String id, Board board) {

        /** Checks the id. */
        public Added {
            Ids.issue(id);
        }
    }
}
