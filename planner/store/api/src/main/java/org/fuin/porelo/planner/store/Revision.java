package org.fuin.porelo.planner.store;

/**
 * The commits a board was read from, opaque to every caller: two boards read from the same commits
 * have equal revisions, and nothing else may be concluded from it.
 *
 * @param value the store's own text for the commits
 */
public record Revision(String value) {

    /** Refuses an empty revision. */
    public Revision {
        if (value.isBlank()) {
            throw new IllegalArgumentException("A revision must not be empty");
        }
    }
}
