package org.fuin.porelo.planner.store;

/** Opens the store of a project a tracker keeps. */
@FunctionalInterface
public interface TrackedStores {

    /** Gives the project as its files name it now, read again when they changed. */
    @FunctionalInterface
    interface Source {

        /**
         * Answers the project as the own repository's head names it.
         *
         * @return the project, the one given before while its files did not change
         * @throws StoreException if the files cannot be read, never answered by an older project
         */
        TrackedProject current();
    }

    /**
     * Opens the project's store.
     *
     * @param project the project, as its files name it at opening
     * @param again gives the project again at each read of the board, so a changed workflow or work
     *     rule is seen without opening the project anew
     * @return its store
     * @throws StoreException if the tracker is of a kind no store here keeps, or cannot be read
     */
    PlanningStore open(TrackedProject project, Source again);
}
