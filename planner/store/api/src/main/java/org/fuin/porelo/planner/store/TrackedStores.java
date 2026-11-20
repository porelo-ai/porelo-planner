package org.fuin.porelo.planner.store;

/** Opens the store of a project a tracker keeps. */
@FunctionalInterface
public interface TrackedStores {

    /**
     * Opens the project's store.
     *
     * @param project the project, as its files name it
     * @return its store
     * @throws StoreException if the tracker is of a kind no store here keeps, or cannot be read
     */
    PlanningStore open(TrackedProject project);
}
