package org.fuin.porelo.planner.store.github;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.forge.github.GitHubGraphQl;
import org.fuin.porelo.planner.store.Board;

/**
 * One read of the GitHub project: the board the window sees, the items it was mapped from, and what
 * it was read with; a write is checked against it.
 *
 * @param board the board
 * @param items every item read, by its Porelo id
 * @param config what it was read with, the field and option ids included
 * @param raw every project item as GitHub gave it, by its node id in GitHub's order; empty when the
 *     snapshot was not read from GitHub's item list, so it is never read again in part
 * @param fieldFaults the faults of the project's fields, which a read in part keeps
 * @param fieldNames the fields' names by their ids
 */
public record Snapshot(
        Board board,
        Map<String, ItemView> items,
        ProjectConfig config,
        Map<String, GitHubGraphQl.ProjectItem> raw,
        List<String> fieldFaults,
        Map<String, String> fieldNames) {

    /** Copies the maps, keeping their order. */
    public Snapshot {
        items = Collections.unmodifiableMap(new LinkedHashMap<>(items));
        raw = Collections.unmodifiableMap(new LinkedHashMap<>(raw));
        fieldFaults = List.copyOf(fieldFaults);
        fieldNames = Collections.unmodifiableMap(new LinkedHashMap<>(fieldNames));
    }

    /**
     * Makes a snapshot that is never read again in part.
     *
     * @param board the board
     * @param items every item read, by its Porelo id
     * @param config what it was read with
     */
    public Snapshot(
            final Board board, final Map<String, ItemView> items, final ProjectConfig config) {
        this(board, items, config, Map.of(), List.of(), Map.of());
    }
}
