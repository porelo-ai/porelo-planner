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
     * Reads the project as {@link #read(ProjectConfig)} does; a read that holds no lock a save
     * waits for may also wait out a short rate limit GitHub names, so it is not cut off by it.
     *
     * @param config what to read with
     * @param rateWaits whether the read may wait for GitHub's rate limit
     * @return the snapshot
     */
    default Snapshot read(final ProjectConfig config, final boolean rateWaits) {
        return read(config);
    }

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
     * Reads from GitHub what {@link #reread(ProjectConfig, Snapshot, Touched.Ids, java.util.Map)}
     * would, without laying it onto a board, so it can be laid onto the newest board held.
     *
     * @param config what to read with, as the project's files name it now
     * @param held the snapshot the read is made against
     * @param touched what to read again
     * @param archived whether each item a look's list showed is archived, by the items' ids
     * @return what was read; a reader that cannot read by id answers the board read again whole
     */
    default Reread fetch(
            final ProjectConfig config,
            final Snapshot held,
            final Touched.Ids touched,
            final java.util.Map<String, Boolean> archived) {
        return Reread.whole(reread(config, held, touched, archived));
    }

    /**
     * Lays what a fetch read onto a board, without asking GitHub.
     *
     * @param newest the board to lay it onto, the newest held
     * @param delta what was read
     * @return the board with it
     */
    default Snapshot apply(final Snapshot newest, final Reread delta) {
        return java.util.Objects.requireNonNull(
                delta.whole(), "a reader that fetches by id applies");
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
     * What a start reads now of the issues it offers: their own blockers, and the state of those
     * that are no items of the board.
     *
     * @param blockers each issue's blockers as GitHub names them now, by the issue's node id; an
     *     issue not read now is absent
     * @param unread the node ids of the issues asked for whose blockers could not be read whole
     * @param waits the state of the blockers that are no items of the board, by their node ids; one
     *     whose state was not read is not known done
     */
    record StartRead(
            java.util.Map<
                            String,
                            java.util.List<org.fuin.porelo.forge.github.GitHubGraphQl.IssueRef>>
                    blockers,
            java.util.Set<String> unread,
            java.util.Map<String, Snapshot.Waited> waits) {

        /** Copies the collections. */
        public StartRead {
            blockers = java.util.Map.copyOf(blockers);
            unread = java.util.Set.copyOf(unread);
            waits = java.util.Map.copyOf(waits);
        }
    }

    /**
     * Reads now, in one small call for up to a hundred issues, the blockers of the issues named and
     * the state of those that are no items of the board, as a start reads them before it offers
     * work on them: a blocker added or reopened since the board was read is seen.
     *
     * @param config what to read with, as the project's files name it now
     * @param held the snapshot that names the waits
     * @param issueIds the node ids of the issues whose blockers are read
     * @return what was read; a reader that cannot read them answers the snapshot's waits alone
     */
    default StartRead waitsOf(
            final ProjectConfig config, final Snapshot held, final java.util.Set<String> issueIds) {
        return new StartRead(java.util.Map.of(), java.util.Set.of(), held.waits());
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
