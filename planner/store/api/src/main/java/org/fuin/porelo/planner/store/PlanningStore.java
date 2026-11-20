package org.fuin.porelo.planner.store;

/**
 * A project's planning store, read through one interface; nothing behind it is visible to a caller.
 *
 * <p>Every method answers from the board of the last refresh, so its answers agree with each other.
 */
public interface PlanningStore {

    /** The words of a store's refusal of an edit it does not offer. */
    String NOT_OFFERED = "not offered by this store";

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
     * Reads the store again on the planner's own schedule, while a window follows the project; a
     * store whose source asked it to wait answers the board it holds instead, naming the wait among
     * its faults. A person's reload is {@link #refresh()}.
     *
     * @return the board
     * @throws StoreException.CredentialRefused if a repository refused the person's login
     * @throws StoreException.Unreachable if a repository could not be reached
     */
    default Board poll() {
        return refresh();
    }

    /**
     * Answers how long to wait before the next scheduled poll.
     *
     * @param fetched the interval of a store that fetches its whole source
     * @param probed the interval of a store that asks cheaply whether its source changed
     * @return the wait; a store that fetches answers {@code fetched}
     */
    default java.time.Duration pollDelay(
            final java.time.Duration fetched, final java.time.Duration probed) {
        return fetched;
    }

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
     * Adds an idea against the revision the window shows: its story and every issue it names in one
     * save, all of them or none. The answer means saved here; the pushes follow, and one that fails
     * or is dropped is named in the board's faults.
     *
     * @param revision the revision the window showed when the idea was checked
     * @param idea the idea
     * @return the story's id, the issues' ids and the board after the save
     * @throws StoreException.StaleRevision if the store moved since that revision
     * @throws StoreException.NotSavable if a part of it cannot be written in its repository
     * @throws StoreException.Busy if another save or a check holds the project too long
     */
    default NewIdea.Added createIdea(final Revision revision, final NewIdea idea) {
        throw new UnsupportedOperationException("This store does not add ideas");
    }

    /**
     * Sets an issue's fields a person keeps by hand against the revision the caller read.
     *
     * @param revision the revision the caller read
     * @param issue the issue's id
     * @param fields the fields to write
     * @return the board after the save, the one read when nothing changed
     * @throws StoreException.StaleRevision if the store moved since that revision
     * @throws StoreException.NoSuchIssue if the board has no such issue
     * @throws StoreException.NotSavable if the store cannot write it
     * @throws UnsupportedOperationException if the store does not offer it
     * @throws StoreException.Busy if another save or a check holds the project too long
     * @throws StoreException.PartlySaved if a later call failed after an earlier one wrote
     */
    default Board setFields(final Revision revision, final String issue, final Fields fields) {
        throw new UnsupportedOperationException(NOT_OFFERED);
    }

    /**
     * Sets a story's whole issue list, in its order, against the revision the caller read: an issue
     * taken out is no longer the story's, one hung in becomes the story's.
     *
     * @param revision the revision the caller read
     * @param story the story's id
     * @param issues the issues' ids, in the story's order
     * @return the board after the save, the one read when nothing changed
     * @throws StoreException.StaleRevision if the store moved since that revision
     * @throws StoreException.NoSuchStory if the board has no such story
     * @throws StoreException.NotSavable if the store cannot write it
     * @throws UnsupportedOperationException if the store does not offer it
     * @throws StoreException.Busy if another save or a check holds the project too long
     * @throws StoreException.PartlySaved if a later call failed after an earlier one wrote
     */
    default Board setStoryIssues(
            final Revision revision, final String story, final java.util.List<String> issues) {
        throw new UnsupportedOperationException(NOT_OFFERED);
    }

    /**
     * Sets an issue's whole list of blockers against the revision the caller read.
     *
     * @param revision the revision the caller read
     * @param issue the issue's id
     * @param blockedBy the blockers' ids
     * @return the board after the save, the one read when nothing changed
     * @throws StoreException.StaleRevision if the store moved since that revision
     * @throws StoreException.NoSuchIssue if the board has no such issue
     * @throws StoreException.NotSavable if the store cannot write it
     * @throws UnsupportedOperationException if the store does not offer it
     * @throws StoreException.Busy if another save or a check holds the project too long
     * @throws StoreException.PartlySaved if a later call failed after an earlier one wrote
     */
    default Board setBlockedBy(
            final Revision revision, final String issue, final java.util.List<String> blockedBy) {
        throw new UnsupportedOperationException(NOT_OFFERED);
    }

    /**
     * Writes one comment of the person's text on an issue, with the person's own access; the board
     * shows no comment, so no revision is checked.
     *
     * @param issue the issue's id
     * @param text the comment's text
     * @throws StoreException.NoSuchIssue if the board has no such issue
     * @throws StoreException.NotSavable if the store cannot write it
     * @throws UnsupportedOperationException if the store does not offer it
     * @throws StoreException.Busy if another save or a check holds the project too long
     */
    default void comment(final String issue, final String text) {
        throw new UnsupportedOperationException(NOT_OFFERED);
    }

    /**
     * Saves what a stage handed back for its issues, each with its new state, in one save on the
     * store as it stands when saved: a stage runs for minutes, and a change elsewhere meanwhile
     * does not refuse it. All of it is written, or none.
     *
     * @param result the stage's result
     * @return the board after the save
     * @throws StoreException.NoSuchIssue if the board has no issue the result names
     * @throws StoreException.NotSavable if an issue no longer stands in the state the stage
     *     completes, its new state is closed, or its file cannot take the result
     * @throws StoreException.Busy if another save or a check holds the project too long
     */
    default Board saveResult(final StageResult result) {
        throw new UnsupportedOperationException("This store does not save stage results");
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
     * Names the format of every text the store gives - a story's description, an issue's text -
     * since one store writes one format.
     *
     * @return the format, plain text unless the store names its own
     */
    default TextFormat textFormat() {
        return TextFormat.PLAIN;
    }

    /**
     * Retires the store when its project was given anew: it touches its data no more, and a read
     * that waited for it is refused.
     */
    default void retire() {}
}
