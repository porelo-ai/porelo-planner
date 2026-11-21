package org.fuin.porelo.planner.store.cache;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Where a tracker's look for changes stands, kept with a board's copy so the next look asks only
 * what changed since: the tracker's own time of the last look and, page by page, what the tracker
 * answered. The planner reads nothing in it but hands it back to the tracker's look; an empty
 * cursor means the look cannot narrow and the board is read whole.
 *
 * @param asOf the tracker's own time of the last look, or null when none was made
 * @param pages the pages of the board's item list as the last look read them, in order
 */
public record ChangeCursor(@Nullable Instant asOf, List<Page> pages) {

    /** A cursor of no look yet: the next look reads the board whole. */
    public static final ChangeCursor NONE = new ChangeCursor(null, List.of());

    /** Copies the pages. */
    public ChangeCursor {
        pages = List.copyOf(pages);
    }

    /**
     * One page of the board's item list as the tracker answered it.
     *
     * @param after the cursor the page was asked after, or null for the first page
     * @param etag the tracker's tag of the page's answer, or null when it gave none
     * @param full whether the page held as many items as were asked for
     * @param itemIds the ids of the page's items, in order
     */
    public record Page(
            @Nullable String after, @Nullable String etag, boolean full, List<String> itemIds) {

        /** Copies the ids and requires each. */
        public Page {
            itemIds = List.copyOf(itemIds);
            itemIds.forEach(id -> Objects.requireNonNull(id, "itemIds"));
        }
    }
}
