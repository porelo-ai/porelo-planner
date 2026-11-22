package org.fuin.porelo.planner.store.github;

/**
 * Tells cheaply whether the GitHub project changed since the snapshot, so an unchanged one is not
 * read again.
 */
@FunctionalInterface
public interface ChangeProbe {

    /** A probe that never knows, so every refresh reads the whole project. */
    ChangeProbe ALWAYS_CHANGED = current -> false;

    /**
     * Answers whether nothing changed since the snapshot was read.
     *
     * @param current the snapshot the store holds
     * @return true only when GitHub says nothing changed
     */
    boolean unchanged(Snapshot current);

    /**
     * Answers the pace GitHub asks for; a probe that knows none never holds.
     *
     * @return the pace
     */
    default Pace pace() {
        return Pace.STEADY;
    }

    /**
     * Forgets what the probe learned of GitHub's answers, after the store read the project whole
     * outside it, so the next look starts anew rather than taking tags from before that read.
     */
    default void forget() {
        // A probe that keeps nothing has nothing to forget.
    }

    /**
     * Takes the next look's answers as the board's own, after the store wrote and read again only
     * what it wrote, so its own save is never taken for another's change and the project is not
     * read whole for it.
     */
    default void adoptNext() {
        // A probe that keeps nothing has nothing to take.
    }

    /**
     * Answers whether an issue the board's issues wait on may have changed in a repository the
     * project does not plan, so the waits are read again; a probe that cannot tell answers true
     * whenever the board waits on any.
     *
     * @param current the snapshot the store holds
     * @return whether the waits are to be read again
     */
    default boolean waitsChanged(final Snapshot current) {
        return !current.waits().isEmpty();
    }

    /** Takes the answers of the last {@link #waitsChanged} as read, once the waits were read. */
    default void waitsRead() {
        // A probe that keeps nothing has nothing to take.
    }

    /**
     * Notes that the store read the project whole, so a repository found unreadable is asked again.
     */
    default void readWhole() {
        // A probe that keeps nothing has nothing to note.
    }

    /**
     * Takes every change a look sees for a while as the store's own save, which GitHub's lists show
     * only a moment after it was written.
     */
    default void adoptSave() {
        adoptNext();
    }
}
