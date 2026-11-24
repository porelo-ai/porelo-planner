package org.fuin.porelo.planner.store.github;

import java.util.Map;
import java.util.Set;
import org.fuin.porelo.forge.github.GitHubGraphQl;
import org.jspecify.annotations.Nullable;

/**
 * What a read by id after a save or a look found on GitHub, apart from the board it is laid onto,
 * so it can be laid onto the newest board held without asking GitHub again.
 *
 * @param read what the items were read with
 * @param whole a board read whole that replaces the board held, or null for a delta
 * @param put the items read, by their item ids, as GitHub gave them
 * @param issues the node ids of the issues read; each one's item on the board is replaced or gone
 * @param archived each item's archived flag as a look's list showed it, by the items' ids
 * @param waits the waits read for the issues read
 */
record Reread(
        ProjectConfig read,
        @Nullable Snapshot whole,
        Map<String, GitHubGraphQl.ProjectItem> put,
        Set<String> issues,
        Map<String, Boolean> archived,
        Map<String, Snapshot.Waited> waits) {

    /** Copies the parts. */
    Reread {
        put = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(put));
        issues = Set.copyOf(issues);
        archived = Map.copyOf(archived);
        waits = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(waits));
    }

    /**
     * A board read whole, which replaces the board held as it is.
     *
     * @param board the board
     * @return the reread
     */
    static Reread whole(final Snapshot board) {
        return new Reread(board.config(), board, Map.of(), Set.of(), Map.of(), Map.of());
    }
}
