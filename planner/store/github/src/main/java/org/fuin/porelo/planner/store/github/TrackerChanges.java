package org.fuin.porelo.planner.store.github;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.fuin.porelo.planner.store.cache.ChangeCursor;
import org.fuin.porelo.planner.store.cache.TrackedItem;

/**
 * Asks the tracker what changed on a board since a board copy's last look, so a start from the copy
 * reads again only those items. The store folds the answer in: it reads the changed and the new
 * items again by id, drops the gone ones, and keeps the next cursor with the copy.
 */
@FunctionalInterface
public interface TrackerChanges {

    /** A tracker that cannot tell: every look reads the board whole, as before a copy. */
    TrackerChanges NONE = (config, token, cursor, known) -> Optional.empty();

    /**
     * Answers what changed since the cursor.
     *
     * @param config the project as its files describe it now
     * @param token the person's token, never logged
     * @param cursor where the last look stands
     * @param known the items the copy holds, by item id
     * @return what changed, or empty when the tracker cannot tell and the board is read whole
     */
    Optional<Changed> since(
            ProjectConfig config,
            String token,
            ChangeCursor cursor,
            Map<String, TrackedItem> known);

    /**
     * What changed since a look.
     *
     * @param changed the items the copy holds that changed, by item id
     * @param added the items the board holds that the copy does not, by item id
     * @param gone the items of the copy the tracker no longer has on the board, by item id
     * @param next the cursor of this look
     */
    record Changed(Set<String> changed, Set<String> added, Set<String> gone, ChangeCursor next) {

        /** Copies the sets. */
        public Changed {
            changed = Set.copyOf(changed);
            added = Set.copyOf(added);
            gone = Set.copyOf(gone);
        }
    }
}
