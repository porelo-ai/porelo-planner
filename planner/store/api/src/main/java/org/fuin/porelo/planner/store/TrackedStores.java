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

    /**
     * Opens the project's store knowing the project's name as the window gives it, which a store
     * keeping its items on this computer finds them by; a tracker elsewhere does not need it.
     *
     * @param name the project's name
     * @param project the project, as its files name it at opening
     * @param again gives the project again at each read of the board
     * @return its store
     * @throws StoreException if the tracker is of a kind no store here keeps, or cannot be read
     */
    default PlanningStore open(
            final String name, final TrackedProject project, final Source again) {
        Ids.name(name);
        return open(project, again);
    }

    /**
     * Opens each project by its tracker's kind with the opener of that kind.
     *
     * @param openers the openers, by the kind each keeps
     * @return the openers together; a kind none keeps is refused as unreadable
     */
    static TrackedStores byKind(final java.util.Map<String, TrackedStores> openers) {
        final java.util.Map<String, TrackedStores> kept = java.util.Map.copyOf(openers);
        return new TrackedStores() {
            @Override
            public PlanningStore open(final TrackedProject project, final Source again) {
                return of(project).open(project, again);
            }

            @Override
            public PlanningStore open(
                    final String name, final TrackedProject project, final Source again) {
                return of(project).open(name, project, again);
            }

            private TrackedStores of(final TrackedProject project) {
                final TrackedStores opener = kept.get(project.kind());
                if (opener == null) {
                    throw new StoreException.Unreadable(
                            project.repositories().getFirst().name(),
                            "planner.yml names a tracker of a kind the planner does not keep");
                }
                return opener;
            }
        };
    }
}
