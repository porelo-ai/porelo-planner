package org.fuin.porelo.planner.store.github;

import org.jspecify.annotations.Nullable;

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

    /**
     * Reads again only the issues a look named and maps the board anew, each item read archived as
     * the look's list showed it: a read of an issue by id does not tell whether its item is.
     *
     * @param config what to read with, as the project's files name it now
     * @param held the snapshot the look's answer is folded into
     * @param touched what to read again
     * @param archived whether each item the look's list showed is archived, by the items' ids
     * @return the snapshot
     */
    default Snapshot reread(
            final ProjectConfig config,
            final Snapshot held,
            final Touched.Ids touched,
            final java.util.Map<String, Boolean> archived) {
        return reread(config, held, touched);
    }

    /**
     * Reads again the state of the issues the board's issues wait on that are no items of it, and
     * maps the board anew from the snapshot held; a reader that cannot reads the whole project.
     *
     * @param config what to read with, as the project's files name it now
     * @param held the snapshot whose waits are read again
     * @return the snapshot, the one held when no wait changed
     */
    default Snapshot rewait(final ProjectConfig config, final Snapshot held) {
        return read(config);
    }

    /**
     * Reads now the state of the issues the issues named wait on that are no items of the board, as
     * a start reads them before it offers work on them.
     *
     * @param config what to read with, as the project's files name it now
     * @param held the snapshot that names the waits
     * @param issueIds the node ids of the issues whose waits are read
     * @return the waits read, by the node ids of the issues waited on; a reader that cannot read
     *     them answers the snapshot's
     */
    default java.util.Map<String, Snapshot.Waited> waitsOf(
            final ProjectConfig config, final Snapshot held, final java.util.Set<String> issueIds) {
        return held.waits();
    }

    /**
     * Answers the board's copy to keep on disk, in the copy's own terms.
     *
     * @param held the snapshot
     * @param cursor where the tracker's look for changes stands
     * @param heldItems the planner's own saves the tracker's list may not show yet
     * @return the copy, or null for a snapshot this reader cannot give again from one
     */
    default org.fuin.porelo.planner.store.cache.@Nullable BoardCopy toCopy(
            final Snapshot held,
            final org.fuin.porelo.planner.store.cache.ChangeCursor cursor,
            final java.util.List<org.fuin.porelo.planner.store.cache.TrackedItem> heldItems) {
        return null;
    }

    /**
     * Answers the board's copy to keep on disk, each item with its own last change as the tracker's
     * list last showed it.
     *
     * @param held the snapshot
     * @param cursor where the tracker's look for changes stands
     * @param heldItems the planner's own saves the tracker's list may not show yet
     * @param listedAt each item's own last change as the list showed it, by the items' ids
     * @return the copy, or null for a snapshot this reader cannot give again from one
     */
    default org.fuin.porelo.planner.store.cache.@Nullable BoardCopy toCopy(
            final Snapshot held,
            final org.fuin.porelo.planner.store.cache.ChangeCursor cursor,
            final java.util.List<org.fuin.porelo.planner.store.cache.TrackedItem> heldItems,
            final java.util.Map<String, java.time.Instant> listedAt) {
        return toCopy(held, cursor, heldItems);
    }

    /**
     * Answers the snapshot a copy holds, as a read of the project would have given it.
     *
     * @param copy the copy
     * @param config what the project is read with, as its files name it now
     * @return the snapshot, or null when the copy is not of this project and its files, or this
     *     reader keeps none
     */
    default @Nullable Snapshot fromCopy(
            final org.fuin.porelo.planner.store.cache.BoardCopy copy, final ProjectConfig config) {
        return null;
    }
}
