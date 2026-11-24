package org.fuin.porelo.planner.store.cache;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * A tracked project's board as the planner last held it.
 *
 * @param project the project it is the board of
 * @param filesCommit the commit the project's files were read from when the board was held
 * @param repositories the project's repositories, in their order
 * @param takenAt when the planner held this board
 * @param board what the board is written with
 * @param items the board's items, in the tracker's order
 * @param cursor where the tracker's look for changes stood when the board was held; {@link
 *     ChangeCursor#NONE} when no look was made
 * @param held the items held over the tracker's last whole list until it shows them
 * @param waits the issues the board's issues wait on that are no items of it, with their state
 * @param unlisted the open issues of the project's repositories that were no items of it, as each
 *     repository's list last gave them, so a repository the next read cannot list keeps them
 */
public record BoardCopy(
        Project project,
        String filesCommit,
        List<String> repositories,
        Instant takenAt,
        TrackerBoard board,
        List<TrackedItem> items,
        ChangeCursor cursor,
        List<OverlaidItem> held,
        List<TrackedWait> waits,
        List<TrackedUnlisted> unlisted) {

    /** Requires every part and copies the lists. */
    public BoardCopy {
        Objects.requireNonNull(project, "project");
        Objects.requireNonNull(filesCommit, "filesCommit");
        repositories = List.copyOf(repositories);
        Objects.requireNonNull(takenAt, "takenAt");
        Objects.requireNonNull(board, "board");
        items = List.copyOf(items);
        Objects.requireNonNull(cursor, "cursor");
        held = List.copyOf(held);
        waits = List.copyOf(waits);
        unlisted = List.copyOf(unlisted);
    }

    /**
     * A copy of a board that knows no open issue outside it.
     *
     * @param project the project it is the board of
     * @param filesCommit the commit the project's files were read from when the board was held
     * @param repositories the project's repositories, in their order
     * @param takenAt when the planner held this board
     * @param board what the board is written with
     * @param items the board's items, in the tracker's order
     * @param cursor where the tracker's look for changes stood when the board was held
     * @param held the items held over the tracker's last whole list until it shows them
     * @param waits the issues the board's issues wait on that are no items of it, with their state
     */
    public BoardCopy(
            final Project project,
            final String filesCommit,
            final List<String> repositories,
            final Instant takenAt,
            final TrackerBoard board,
            final List<TrackedItem> items,
            final ChangeCursor cursor,
            final List<OverlaidItem> held,
            final List<TrackedWait> waits) {
        this(
                project,
                filesCommit,
                repositories,
                takenAt,
                board,
                items,
                cursor,
                held,
                waits,
                List.of());
    }

    /**
     * A copy of a board whose issues wait on nothing outside it.
     *
     * @param project the project it is the board of
     * @param filesCommit the commit the project's files were read from when the board was held
     * @param repositories the project's repositories, in their order
     * @param takenAt when the planner held this board
     * @param board what the board is written with
     * @param items the board's items, in the tracker's order
     * @param cursor where the tracker's look for changes stood when the board was held
     * @param held the items held over the tracker's last whole list until it shows them
     */
    public BoardCopy(
            final Project project,
            final String filesCommit,
            final List<String> repositories,
            final Instant takenAt,
            final TrackerBoard board,
            final List<TrackedItem> items,
            final ChangeCursor cursor,
            final List<OverlaidItem> held) {
        this(project, filesCommit, repositories, takenAt, board, items, cursor, held, List.of());
    }

    /**
     * A tracked project, by its tracker and its place there; two projects never share one.
     *
     * @param tracker the tracker's host, such as {@code github.com}
     * @param owner the account or organization that holds the project there
     * @param number the project's number there
     */
    public record Project(String tracker, String owner, int number) {

        /** Requires names a file may be named after. */
        public Project {
            name("tracker", tracker);
            name("owner", owner);
            if (number < 1) {
                throw new IllegalArgumentException("A project's number is at least 1");
            }
        }

        /** The name of the project's file. */
        String file() {
            return tracker + "-" + owner + "-" + number + ".board";
        }

        private static void name(final String what, final String name) {
            Objects.requireNonNull(name, what);
            if (!name.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,99}") || name.contains("..")) {
                throw new IllegalArgumentException("A project's " + what + " is no plain name");
            }
        }
    }
}
