package org.fuin.porelo.planner.store;

/**
 * A project's planning store, read through one interface; nothing behind it is visible to a caller.
 *
 * <p>Every method answers from the board of the last refresh, so its answers agree with each other.
 */
public interface PlanningStore {

    /**
     * Answers the whole board.
     *
     * @return the board of the last refresh
     * @throws StoreException.WorkflowMissing if the project names no workflow
     */
    Board board();

    /**
     * Answers one story.
     *
     * @param id the story's id
     * @return the story at the board's revision
     * @throws StoreException.NoSuchStory if the board has none of that id
     */
    default Story story(final String id) {
        return board().story(id).orElseThrow(() -> new StoreException.NoSuchStory(id));
    }

    /**
     * Answers one issue.
     *
     * @param id the issue's id
     * @return the issue at the board's revision
     * @throws StoreException.NoSuchIssue if the board has none of that id
     */
    default Issue issue(final String id) {
        return board().issue(id).orElseThrow(() -> new StoreException.NoSuchIssue(id));
    }

    /**
     * Reads the store again and makes its board the one every method answers from.
     *
     * @return the new board
     * @throws StoreException.CredentialRefused if a repository refused the person's login
     * @throws StoreException.Unreachable if a repository could not be reached
     */
    Board refresh();

    /**
     * Saves a batch of writes against the revision the window shows: all of them or none.
     *
     * @param revision the revision the writes were checked against
     * @param writes what to write
     * @return the board after the save
     * @throws StoreException.StaleRevision if the store moved since that revision
     * @throws StoreException.NotSavable if an item cannot be written in its repository
     * @throws StoreException.Busy if another save or a check holds the project too long
     * @throws StoreException.PartlySaved if a later repository failed after earlier ones were saved
     */
    default Board save(final Revision revision, final Writes writes) {
        throw new UnsupportedOperationException("This store does not save moves");
    }

    /**
     * Adds a new issue in state Idea against the revision the window shows, saved as a move is
     * saved: written, indexed and pushed, or not at all.
     *
     * @param revision the revision the window showed when the person wrote it
     * @param issue what the person wrote
     * @return its id and the board after the save
     * @throws StoreException.StaleRevision if the store moved since that revision
     * @throws StoreException.NotSavable if it cannot be written in its repository
     * @throws StoreException.Busy if another save or a check holds the project too long
     */
    default NewIssue.Added createIssue(final Revision revision, final NewIssue issue) {
        throw new UnsupportedOperationException("This store does not add issues");
    }

    /**
     * Derives a story's state from its issues' states, as the store's stories index does.
     *
     * @param issueStates the states of the story's issues
     * @return the story's state, or null when none follows
     */
    default @org.jspecify.annotations.Nullable String storyState(
            final java.util.List<String> issueStates) {
        throw new UnsupportedOperationException("This store does not derive story states");
    }

    /**
     * Retires the store when its project was given anew: it touches its data no more, and a read
     * that waited for it is refused.
     */
    default void retire() {}
}
