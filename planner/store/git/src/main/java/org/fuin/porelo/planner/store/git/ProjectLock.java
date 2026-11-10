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

    /**
     * Waits for the project at most a while and holds it.
     *
     * @param most how long to wait at most
     * @return the hold, or empty when the project stayed held that long
     */
    default java.util.Optional<Held> acquire(final java.time.Duration most) {
        return java.util.Optional.of(acquire());
    }

    /** A held project. */
    interface Held extends AutoCloseable {
        @Override
        void close();
    }
}
