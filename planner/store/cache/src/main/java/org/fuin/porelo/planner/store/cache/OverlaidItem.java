package org.fuin.porelo.planner.store.cache;

import java.util.List;
import java.util.Objects;

/**
 * An item the board holds over the tracker's last whole list, as the planner last read it by id or
 * saved it, with the versions the list may still show of it until it catches up.
 *
 * @param id the item's own id at the tracker
 * @param item the item as last read by id; when it is gone, the item as last seen, which names its
 *     issue
 * @param gone whether the last read by id found the item no longer on the board
 * @param version the item's version as last read by id
 * @param earlier the versions the tracker's list may still show of it, each one older than the
 *     version read
 */
public record OverlaidItem(
        String id, TrackedItem item, boolean gone, String version, List<String> earlier) {

    /** Requires every part and copies the versions. */
    public OverlaidItem {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(item, "item");
        Objects.requireNonNull(version, "version");
        earlier = List.copyOf(earlier);
    }
}
