package org.fuin.porelo.planner.store.git;

/** Lets one refresh or save of a project touch its clones at a time, across processes too. */
@FunctionalInterface
public interface ProjectLock {

    /**
     * Waits for the project and holds it.
     *
     * @return the hold, released when closed
     */
    Held acquire();

    /** A held project. */
    interface Held extends AutoCloseable {
        @Override
        void close();
    }
}
