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
}
