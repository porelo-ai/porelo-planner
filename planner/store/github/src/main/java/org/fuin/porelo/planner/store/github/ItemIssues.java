package org.fuin.porelo.planner.store.github;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * Names the issue a project item holds, as a look for changes saw it, so an item new on the board
 * is read again by its issue: GitHub reads an issue by its node id, never an item. It also tells
 * what the last look's list showed of an item, which a read of the issue by id does not give.
 */
@FunctionalInterface
public interface ItemIssues {

    /**
     * What the list showed of an item on a page it answered anew.
     *
     * @param archived whether the item is archived in the project
     * @param updatedAt the item's own last change
     * @param issueUpdatedAt its issue's last change, or null for an item without an issue
     */
    record Listed(boolean archived, Instant updatedAt, @Nullable Instant issueUpdatedAt) {}

    /**
     * Answers the node id of the issue an item holds.
     *
     * @param itemId the item's node id
     * @return the issue's node id, or null when no look saw the item hold one
     */
    @Nullable String issueOf(String itemId);

    /**
     * Answers what the last look's list showed of an item.
     *
     * @param itemId the item's node id
     * @return what it showed, or null when the last look found the item's page as it was, or did
     *     not list the item
     */
    default @Nullable Listed listed(final String itemId) {
        return null;
    }
}
