package org.fuin.porelo.planner.store.github;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.forge.github.GitHubGraphQl;
import org.fuin.porelo.planner.store.Board;
import org.jspecify.annotations.Nullable;

/**
 * One read of the GitHub project: the board the window sees, the items it was mapped from, and what
 * it was read with; a write is checked against it.
 *
 * @param board the board
 * @param items every item read, by its Porelo id
 * @param config what it was read with, the field and option ids included
 * @param raw every project item as GitHub gave it, by its node id in GitHub's order; empty when the
 *     snapshot was not read from GitHub's item list, so it is never read again in part
 * @param fieldFaults the faults of the project's fields and of its repositories' issue lists, which
 *     a read in part keeps
 * @param fieldNames the fields' names by their ids
 * @param restFieldIds the fields' numeric ids by their ids, which GitHub's REST item list takes to
 *     name the fields whose values it gives; empty when they were not read
 * @param waits the issues the items' issues are blocked by that are no items of the project, by
 *     their node ids, each with its state as last read
 * @param unlisted the open issues of the project's repositories as their lists last gave them,
 *     before those that are items of the project are left out; empty when none were read
 */
public record Snapshot(
        Board board,
        Map<String, ItemView> items,
        ProjectConfig config,
        Map<String, GitHubGraphQl.ProjectItem> raw,
        List<String> fieldFaults,
        Map<String, String> fieldNames,
        Map<String, Long> restFieldIds,
        Map<String, Waited> waits,
        List<Unlisted> unlisted) {

    /**
     * An issue an item's issue is blocked by that is no item of the project.
     *
     * @param repository its repository with its owner, as GitHub names it
     * @param number its number in its repository
     * @param closed whether GitHub has it closed, or null when the person's token could not read it
     */
    public record Waited(String repository, int number, @Nullable Boolean closed) {}

    /**
     * An open issue of one of the project's repositories, as the repository's list gave it.
     *
     * @param nodeId its node id
     * @param repository the project's repository whose list gave it
     * @param number its number in its repository
     * @param title its title
     * @param type the name of its issue type, or null when it has none
     */
    public record Unlisted(
            String nodeId, String repository, int number, String title, @Nullable String type) {}

    /** Copies the maps, keeping their order. */
    public Snapshot {
        items = Collections.unmodifiableMap(new LinkedHashMap<>(items));
        raw = Collections.unmodifiableMap(new LinkedHashMap<>(raw));
        fieldFaults = List.copyOf(fieldFaults);
        fieldNames = Collections.unmodifiableMap(new LinkedHashMap<>(fieldNames));
        restFieldIds = Collections.unmodifiableMap(new LinkedHashMap<>(restFieldIds));
        waits = Collections.unmodifiableMap(new LinkedHashMap<>(waits));
        unlisted = List.copyOf(unlisted);
    }

    /**
     * Makes a snapshot whose items' issues wait on no issue outside the project.
     *
     * @param board the board
     * @param items every item read, by its Porelo id
     * @param config what it was read with
     * @param raw every project item as GitHub gave it, by its node id in GitHub's order
     * @param fieldFaults the faults of the project's fields
     * @param fieldNames the fields' names by their ids
     * @param restFieldIds the fields' numeric ids by their ids
     */
    public Snapshot(
            final Board board,
            final Map<String, ItemView> items,
            final ProjectConfig config,
            final Map<String, GitHubGraphQl.ProjectItem> raw,
            final List<String> fieldFaults,
            final Map<String, String> fieldNames,
            final Map<String, Long> restFieldIds) {
        this(board, items, config, raw, fieldFaults, fieldNames, restFieldIds, Map.of(), List.of());
    }

    /**
     * Makes a snapshot read without the fields' numeric ids.
     *
     * @param board the board
     * @param items every item read, by its Porelo id
     * @param config what it was read with
     * @param raw every project item as GitHub gave it, by its node id in GitHub's order
     * @param fieldFaults the faults of the project's fields
     * @param fieldNames the fields' names by their ids
     */
    public Snapshot(
            final Board board,
            final Map<String, ItemView> items,
            final ProjectConfig config,
            final Map<String, GitHubGraphQl.ProjectItem> raw,
            final List<String> fieldFaults,
            final Map<String, String> fieldNames) {
        this(board, items, config, raw, fieldFaults, fieldNames, Map.of());
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
        this(board, items, config, Map.of(), List.of(), Map.of(), Map.of());
    }
}
