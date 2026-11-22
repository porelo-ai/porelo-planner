package org.fuin.porelo.planner.store;

import org.jspecify.annotations.Nullable;

/**
 * An issue a person adds through the planner in one repository, its number given by the store.
 *
 * @param repository the name of the project's repository it belongs to
 * @param title its title, one line
 * @param mustBeTrue what must be true, the paragraph its file opens with
 * @param size its size, or null when it is not known yet
 * @param worker who works it
 * @param task the Porelo task that proposed it, or null when none did
 * @param priority its priority, or null for the store's own
 * @param story the story it is hung into last, or null for none
 * @param state its initial state for a store that creates in a state, or null for the first state
 */
public record NewIssue(
        String repository,
        String title,
        String mustBeTrue,
        @Nullable String size,
        Worker worker,
        @Nullable String task,
        @Nullable String priority,
        @Nullable String story,
        @Nullable String state) {

    /**
     * Checks the repository's name, so no store resolves a path from an unchecked one, and the
     * task's, so none writes a line from a malformed one.
     */
    public NewIssue {
        Ids.name(repository);
        if (task != null) {
            Ids.task(task);
        }
        if (story != null) {
            Ids.story(story);
        }
    }

    /**
     * A new issue in the workflow's first state.
     *
     * @param repository the repository it belongs to
     * @param title its title
     * @param mustBeTrue what must be true
     * @param size its size, or null when unknown
     * @param worker who works it
     * @param task the task that proposed it, or null
     * @param priority its priority, or null for the store's own
     * @param story its story, or null for none
     */
    public NewIssue(
            final String repository,
            final String title,
            final String mustBeTrue,
            final @Nullable String size,
            final Worker worker,
            final @Nullable String task,
            final @Nullable String priority,
            final @Nullable String story) {
        this(repository, title, mustBeTrue, size, worker, task, priority, story, null);
    }

    /**
     * A new issue without a priority or story of its own.
     *
     * @param repository the name of the project's repository it belongs to
     * @param title its title, one line
     * @param mustBeTrue what must be true
     * @param size its size, or null when it is not known yet
     * @param worker who works it
     * @param task the Porelo task that proposed it, or null when none did
     */
    public NewIssue(
            final String repository,
            final String title,
            final String mustBeTrue,
            final @Nullable String size,
            final Worker worker,
            final @Nullable String task) {
        this(repository, title, mustBeTrue, size, worker, task, null, null);
    }

    /**
     * A new issue no task proposed.
     *
     * @param repository the name of the project's repository it belongs to
     * @param title its title, one line
     * @param mustBeTrue what must be true
     * @param size its size, or null when it is not known yet
     * @param worker who works it
     */
    public NewIssue(
            final String repository,
            final String title,
            final String mustBeTrue,
            final @Nullable String size,
            final Worker worker) {
        this(repository, title, mustBeTrue, size, worker, null);
    }

    /**
     * A new issue an agent works.
     *
     * @param repository the name of the project's repository it belongs to
     * @param title its title, one line
     * @param mustBeTrue what must be true
     * @param size its size, or null when it is not known yet
     */
    public NewIssue(
            final String repository,
            final String title,
            final String mustBeTrue,
            final @Nullable String size) {
        this(repository, title, mustBeTrue, size, Worker.AGENT);
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
