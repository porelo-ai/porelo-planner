package org.fuin.porelo.planner.core;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.fuin.porelo.planner.store.Basis;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.Story;
import org.fuin.porelo.planner.store.Version;

/**
 * The new versions of what a write changed, read from the board its save left, in the form a
 * write's versions seen take: so a caller sends its next write's versions from the answer of the
 * one before and reads nothing between the two.
 */
public final class WrittenVersions {

    private WrittenVersions() {}

    /**
     * The versions of the items written, and of each story that holds a written issue before the
     * save or after it and is no item written itself, only when the caller had seen that story as
     * it stood on the board the write was judged on: a story's version changes with its issues' own
     * writes and with an issue leaving or joining it, and a version of a change the caller neither
     * made nor saw would let its next write pass the check against content it never read. An item
     * the board after the save does not hold as one item is left out.
     *
     * @param before the board the write was judged on, read inside the write's lock
     * @param after the board the save left, as the save returned it
     * @param written the ids of the items the write changed: its targets and any item it made
     * @param seen the versions the caller saw, by item id
     * @return each item's version by its id, sorted by id
     */
    public static Map<String, String> of(
            final Board before,
            final Board after,
            final Collection<String> written,
            final Basis seen) {
        final Set<String> ids = new LinkedHashSet<>(written);
        for (final String story : holding(before, after, written)) {
            final Version named = seen.of(story);
            if (named != null
                    && before.story(story).map(Story::version).filter(named::equals).isPresent()) {
                ids.add(story);
            }
        }
        final Map<String, String> versions = new TreeMap<>();
        for (final String id : ids) {
            after.story(id)
                    .map(Story::version)
                    .or(() -> after.issue(id).map(Issue::version))
                    .ifPresent(version -> versions.put(id, version.value()));
        }
        return Collections.unmodifiableMap(versions);
    }

    /**
     * The stories that hold a written issue on either board and are no item written themselves.
     *
     * @param before the board before the write
     * @param after the board after it
     * @param written the ids of the items the write changed
     * @return the holding stories' ids
     */
    public static Set<String> holding(
            final Board before, final Board after, final Collection<String> written) {
        final Set<String> ids = new LinkedHashSet<>();
        holding(before, written, ids);
        holding(after, written, ids);
        ids.removeAll(written);
        return ids;
    }

    private static void holding(
            final Board board, final Collection<String> written, final Set<String> ids) {
        for (final Story story : board.stories()) {
            if (story.issues().stream().anyMatch(written::contains)) {
                ids.add(story.id());
            }
        }
    }
}
