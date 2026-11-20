package org.fuin.porelo.planner.store.github;

/** Reads the whole GitHub project and maps it to a board. */
@FunctionalInterface
public interface BoardReader {

    /**
     * Reads every item of the project, archived ones included, and maps them.
     *
     * @param config what to read and with which ids
     * @return the snapshot, its faults naming each item left out
     * @throws org.fuin.porelo.planner.store.StoreException if GitHub cannot be read
     */
    Snapshot read(ProjectConfig config);
}
