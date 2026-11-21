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

    /**
     * Answers how far a whole read under way has come.
     *
     * @return the progress, or empty while none runs
     */
    default java.util.Optional<org.fuin.porelo.planner.store.ReadProgress> progress() {
        return java.util.Optional.empty();
    }

    /**
     * Reads again only the issues a save touched and maps the board anew from the snapshot held; a
     * reader that cannot reads the whole project.
     *
     * @param config what to read with, as the project's files name it now
     * @param held the snapshot the save was made against
     * @param touched what the save touched
     * @return the snapshot
     */
    default Snapshot reread(
            final ProjectConfig config, final Snapshot held, final Touched.Ids touched) {
        return read(config);
    }
}
