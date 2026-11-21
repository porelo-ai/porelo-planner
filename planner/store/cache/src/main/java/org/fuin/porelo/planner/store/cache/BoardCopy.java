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
 * @param held the planner's own saves that the tracker's list may not show yet
 */
public record BoardCopy(
        Project project,
        String filesCommit,
        List<String> repositories,
        Instant takenAt,
        TrackerBoard board,
        List<TrackedItem> items,
        ChangeCursor cursor,
        List<TrackedItem> held) {

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
