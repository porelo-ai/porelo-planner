package org.fuin.porelo.planner.store;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * How far a read of a project's board has come, so a window waiting for the board sees the planner
 * at work.
 *
 * @param since when the read began
 * @param read the items read so far
 * @param total the items the last read held, or null when none was read before
 * @param lastRead the tracker reference of the last item read, or null before the first
 * @param from the place read, named for a person, such as {@code GitHub}
 * @param kind whether the board is read whole or only looked at for changes
 * @param waitsFor what the read waits for now, such as {@code save}, or null while it reads
 */
public record ReadProgress(
        Instant since,
        int read,
        @Nullable Integer total,
        @Nullable String lastRead,
        String from,
        Kind kind,
        @Nullable String waitsFor) {

    /** What a read reads. */
    public enum Kind {
        /** The whole board, item by item. */
        WHOLE,
        /** The list of the board's items, for what changed since the board held. */
        LOOK
    }

    /** What a read waits for while a save of the planner holds the project. */
    public static final String SAVE = "save";

    /**
     * A whole read that waits for nothing.
     *
     * @param since when the read began
     * @param read the items read so far
     * @param total the items the last read held, or null when none was read before
     * @param lastRead the tracker reference of the last item read, or null before the first
     * @param from the place read, named for a person
     */
    public ReadProgress(
            final Instant since,
            final int read,
            final @Nullable Integer total,
            final @Nullable String lastRead,
            final String from) {
        this(since, read, total, lastRead, from, Kind.WHOLE, null);
    }

    /**
     * This read waiting for {@code what}, or reading again where it is null.
     *
     * @param what what it waits for, or null
     * @return the progress
     */
    public ReadProgress waiting(final @Nullable String what) {
        return new ReadProgress(since, read, total, lastRead, from, kind, what);
    }
}
