package org.fuin.porelo.planner.store.github;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.fuin.porelo.planner.store.Board;

/**
 * One read of the GitHub project: the board the window sees, the items it was mapped from, and what
 * it was read with; a write is checked against it.
 *
 * @param board the board
 * @param items every item read, by its Porelo id
 * @param config what it was read with, the field and option ids included
 */
public record Snapshot(Board board, Map<String, ItemView> items, ProjectConfig config) {

    /** Copies the map, keeping its order. */
    public Snapshot {
        items = Collections.unmodifiableMap(new LinkedHashMap<>(items));
    }
}
