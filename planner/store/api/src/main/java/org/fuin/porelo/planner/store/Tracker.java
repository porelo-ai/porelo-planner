package org.fuin.porelo.planner.store;

import org.jspecify.annotations.Nullable;

/**
 * Where a store keeps a project's stories and issues, as the window is told it.
 *
 * @param kind what keeps them
 * @param items the directory a local store uses, its default included, as an absolute path; null
 *     for every other kind, and for a local store whose directories are refused
 * @param defaultItems the local store's default directory, as an absolute path; null when {@code
 *     items} is
 */
public record Tracker(Kind kind, @Nullable String items, @Nullable String defaultItems) {

    /** What keeps a project's stories and issues. */
    public enum Kind {
        /** GitHub's tracker. */
        GITHUB,
        /** The Git store, as Markdown files in the project's repository. */
        GIT,
        /** A directory of this computer, which the person may choose. */
        LOCAL
    }

    /** GitHub's tracker. */
    public static final Tracker GITHUB = new Tracker(Kind.GITHUB, null, null);

    /** The Git store. */
    public static final Tracker GIT = new Tracker(Kind.GIT, null, null);

    /** A local store whose directories are refused: no directory is told. */
    public static final Tracker LOCAL_REFUSED = new Tracker(Kind.LOCAL, null, null);

    /** Refuses a directory beside a kind that keeps none, or only one of the two. */
    public Tracker {
        if ((kind != Kind.LOCAL && items != null) || (items == null) != (defaultItems == null)) {
            throw new IllegalArgumentException("Only a local tracker names its directories");
        }
    }

    /**
     * A directory of this computer.
     *
     * @param items the directory in use, as an absolute path
     * @param defaultItems the project's default directory, as an absolute path
     * @return the tracker
     */
    public static Tracker local(final String items, final String defaultItems) {
        return new Tracker(Kind.LOCAL, items, defaultItems);
    }
}
