package org.fuin.porelo.planner.store.github;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import org.fuin.porelo.forge.github.GitHubGraphQl;
import org.fuin.porelo.planner.store.cache.ChangeCursor;
import org.fuin.porelo.planner.store.cache.TrackedItem;
import org.jspecify.annotations.Nullable;

/**
 * The planner's own saves, each as GitHub gave it back right after it was written, held until the
 * board's own list shows it: GitHub's lists show a save only a moment after it, and a look or a
 * whole read in that moment would otherwise put the board from before the save back.
 *
 * <p>Whether a list shows a save is judged by GitHub's own versions, never the planner's clock: a
 * whole read shows it when it lists the item with a later change of its issue, or with the same one
 * and the same content; a look shows it when the item's page of the REST list was answered anew
 * since the save was held, the look, which compares that page with the saves held, named no change
 * of it, and the list shows the item or its issue at a time no earlier than the save's. A changed
 * page alone never shows a save.
 *
 * <p>A save that changed only an issue's links - its parent, its sub-issues, its blockers or their
 * order - changes nothing the REST list compares; it is released by the next whole read, after the
 * item is read again by id, or by a look that names the item, which reads it again by id. A look
 * reads by id a save of an item no page of the list carries yet, such as one just made, so GitHub's
 * own later changes to it are seen; that save stays held until the list shows it. A save is dropped
 * otherwise only when GitHub says the item is gone or the write failed.
 */
final class HeldSaves {

    /**
     * One save held.
     *
     * @param item the item as GitHub gave it back
     * @param tag the tag of the item's page of the REST list when it was held, or null when no look
     *     had listed it
     * @param linksOnly whether the save changed only the issue's links, which the list's times and
     *     counts may not show
     */
    private record Held(TrackedItem item, @Nullable String tag, boolean linksOnly) {}

    private final Map<String, Held> held = new LinkedHashMap<>();

    /**
     * How often a look read each save again by id; a list that never carries it stops the reads.
     */
    private final Map<String, Integer> readById = new LinkedHashMap<>();

    /** The most reads by id one save gets from looks; a whole read still reads it after. */
    static final int MOST_READS_BY_ID = 3;

    /**
     * Takes the saves a board's copy kept, each with the tag its page had in the copy's cursor.
     *
     * @param items the saves
     * @param cursor the copy's cursor
     */
    synchronized void load(final List<TrackedItem> items, final ChangeCursor cursor) {
        held.clear();
        for (final TrackedItem item : items) {
            // Whether a kept save changed only links is not kept: the list's times stand for it.
            held.put(item.id(), new Held(item, pageTag(cursor, item.id()), false));
        }
    }

    /**
     * Answers the saves held, as the board's copy keeps them.
     *
     * @return the items, in the order they were held
     */
    synchronized List<TrackedItem> items() {
        return held.values().stream().map(Held::item).toList();
    }

    /** Whether any save is held. */
    synchronized boolean any() {
        return !held.isEmpty();
    }

    /**
     * Lays the saves held over the items a board holds, so a look compares GitHub's list with what
     * the planner saved.
     *
     * @param known the board's items by their ids
     * @return the items, the saves in their place or added
     */
    synchronized Map<String, TrackedItem> overlaid(final Map<String, TrackedItem> known) {
        final Map<String, TrackedItem> all = new LinkedHashMap<>(known);
        held.forEach((id, save) -> all.put(id, save.item()));
        return all;
    }

    /**
     * Holds every item of a board read back after a save that the save touched.
     *
     * @param fresh the board read back
     * @param touched what the save touched
     * @param cursor where the look stands now
     * @param before the board the save was made against
     */
    synchronized void hold(
            final Snapshot fresh,
            final Touched.Ids touched,
            final ChangeCursor cursor,
            final Snapshot before) {
        for (final GitHubGraphQl.ProjectItem item : fresh.raw().values()) {
            final TrackedItem tracked = BoardCopyMapping.tracked(item);
            if (matches(tracked, touched)) {
                final GitHubGraphQl.ProjectItem was = before.raw().get(item.id());
                final Held earlier = held.get(item.id());
                // A save on top of one held changed only links if both did.
                final boolean linksOnly =
                        was != null
                                && onlyLinks(BoardCopyMapping.tracked(was), tracked)
                                && (earlier == null || earlier.linksOnly());
                held.put(item.id(), new Held(tracked, pageTag(cursor, item.id()), linksOnly));
            }
        }
    }

    /**
     * Whether two forms of an item differ in their issue's links - parent, sub-issues, blockers -
     * and time alone, if at all.
     */
    private static boolean onlyLinks(final TrackedItem was, final TrackedItem is) {
        return withoutLinks(was).equals(withoutLinks(is));
    }

    private static TrackedItem withoutLinks(final TrackedItem item) {
        final TrackedItem.Issue issue = item.issue();
        final TrackedItem.Links none = new TrackedItem.Links(List.of(), false, null, 0);
        return new TrackedItem(
                item.id(),
                item.archived(),
                issue == null
                        ? null
                        : new TrackedItem.Issue(
                                issue.id(),
                                issue.key(),
                                issue.number(),
                                issue.title(),
                                issue.body(),
                                issue.state(),
                                issue.stateReason(),
                                java.time.Instant.EPOCH,
                                issue.repository(),
                                issue.labels(),
                                null,
                                none,
                                none,
                                issue.type(),
                                issue.closedAt()),
                item.values(),
                null,
                item.partial());
    }

    /**
     * Drops the saves of a write that failed: what GitHub holds of them is read anew.
     *
     * @param touched what the write touched
     */
    synchronized void forget(final Touched.Ids touched) {
        held.values().removeIf(save -> matches(save.item(), touched));
    }

    /** Drops every save held, as the board is read anew from GitHub. */
    synchronized void clear() {
        held.clear();
    }

    /**
     * Releases the saves a whole read of GitHub's list shows, and answers the others: those the
     * list lacks, or lists at an older version or otherwise at the same one.
     *
     * @param read the board read
     * @return the items' ids of the saves the list does not show yet, by their issues' node ids
     */
    synchronized Map<String, String> lagging(final Snapshot read) {
        final Map<String, String> lagging = new LinkedHashMap<>();
        final List<String> shown = new ArrayList<>();
        for (final Held save : held.values()) {
            final TrackedItem.Issue mine = save.item().issue();
            if (mine == null) {
                // A save without an issue cannot be read by id; the list stands for it.
                shown.add(save.item().id());
                continue;
            }
            final GitHubGraphQl.ProjectItem listed = read.raw().get(save.item().id());
            final TrackedItem.Issue theirs =
                    listed == null ? null : BoardCopyMapping.tracked(listed).issue();
            if (save.linksOnly()) {
                // The links the list shows may lag at any time: the item is read again by id,
                // and released then.
                lagging.put(mine.id(), save.item().id());
            } else if (listed == null
                    || theirs == null
                    || theirs.updatedAt().isBefore(mine.updatedAt())) {
                lagging.put(mine.id(), save.item().id());
            } else if (theirs.updatedAt().isAfter(mine.updatedAt())
                    || BoardCopyMapping.tracked(listed).equals(save.item())) {
                shown.add(save.item().id());
            } else {
                // The same version otherwise: a field's value moves no issue's time.
                lagging.put(mine.id(), save.item().id());
            }
        }
        shown.forEach(held::remove);
        return lagging;
    }

    /**
     * Takes the saves read again by id in their newer form; one GitHub no longer holds on the board
     * is dropped, and one that changed only links is released, as the read by id shows it.
     *
     * @param result the board read again
     * @param itemIds the saves read again
     * @param cursor where the look stands now
     */
    synchronized void refreshed(
            final Snapshot result, final Iterable<String> itemIds, final ChangeCursor cursor) {
        for (final String id : itemIds) {
            if (!held.containsKey(id)) {
                continue;
            }
            final GitHubGraphQl.ProjectItem item = result.raw().get(id);
            final Held save = Objects.requireNonNull(held.get(id));
            if (item == null || save.linksOnly()) {
                held.remove(id);
            } else {
                held.put(id, new Held(BoardCopyMapping.tracked(item), pageTag(cursor, id), false));
            }
        }
    }

    /**
     * Takes a look's answer: a save the look names is read again and stays held with its newer
     * form, or is released when it changed only links; one GitHub says is gone is dropped; one
     * whose page was answered anew since it was held, without a change named, and whose item or
     * issue the list shows at a time no earlier than the save's, shows on the list and is released.
     *
     * @param changed the look's answer
     * @param result the board the answer was folded into
     * @param during what the saves made while the look ran touched: the look compared GitHub's list
     *     with the board from before them, so it tells nothing of them, and they take the look's
     *     tags, which describe the list from before them
     * @param listed what the look's list showed of an item on a page it answered anew, or null
     */
    synchronized void looked(
            final TrackerChanges.Changed changed,
            final Snapshot result,
            final List<Touched.Ids> during,
            final Function<String, ItemIssues.@Nullable Listed> listed) {
        final ChangeCursor next = changed.next();
        final List<String> ids = List.copyOf(held.keySet());
        for (final String id : ids) {
            final Held save = Objects.requireNonNull(held.get(id));
            if (during.stream().anyMatch(touched -> matches(save.item(), touched))) {
                held.put(id, new Held(save.item(), pageTag(next, id), save.linksOnly()));
                continue;
            }
            if (changed.gone().contains(id)) {
                held.remove(id);
            } else if (changed.changed().contains(id) || changed.added().contains(id)) {
                refreshed(result, List.of(id), next);
            } else if (!save.linksOnly()) {
                final String tag = pageTag(next, id);
                final ItemIssues.Listed shown = listed.apply(id);
                if (tag != null
                        && !tag.equals(save.tag())
                        && shown != null
                        && showsSave(shown, save.item())) {
                    held.remove(id);
                }
            }
        }
    }

    /** Whether the list shows an item or its issue at a time no earlier than a save's. */
    private static boolean showsSave(final ItemIssues.Listed shown, final TrackedItem save) {
        final java.time.Instant itemTime = save.updatedAt();
        if (itemTime != null && !shown.updatedAt().isBefore(itemTime)) {
            return true;
        }
        final TrackedItem.Issue issue = save.issue();
        final java.time.Instant issueTime = shown.issueUpdatedAt();
        if (issue == null) {
            // A draft has no issue to read by id; the list stands for it.
            return true;
        }
        return issueTime != null && !issueTime.isBefore(issue.updatedAt());
    }

    /**
     * Answers the saves of items no page of a look's list carries yet, to be read again by id: the
     * list cannot show GitHub's own later changes to them. A save of only links is left out, since
     * a read by id releases it and a whole read whose list lags would then put its links back; a
     * save made while the look ran is left for the next look, which tells something of it.
     *
     * @param cursor where the look stands now
     * @param during what the saves made while the look ran touched
     * @return the items' ids of those saves, by their issues' node ids
     */
    synchronized Map<String, String> unshown(
            final ChangeCursor cursor, final List<Touched.Ids> during) {
        final Map<String, String> unshown = new LinkedHashMap<>();
        readById.keySet().retainAll(held.keySet());
        for (final Held save : held.values()) {
            final TrackedItem.Issue issue = save.item().issue();
            if (issue == null
                    || during.stream().anyMatch(touched -> matches(save.item(), touched))) {
                continue;
            }
            if (!save.linksOnly()
                    && pageTag(cursor, save.item().id()) == null
                    && readById.getOrDefault(save.item().id(), 0) < MOST_READS_BY_ID) {
                unshown.put(issue.id(), save.item().id());
                readById.merge(save.item().id(), 1, Integer::sum);
            }
        }
        return unshown;
    }

    /** The tag of the page of a cursor that lists an item, or null when none does. */
    static @Nullable String pageTag(final ChangeCursor cursor, final String itemId) {
        for (final ChangeCursor.Page page : cursor.pages()) {
            if (page.itemIds().contains(itemId)) {
                return page.etag();
            }
        }
        return null;
    }

    /** Whether a save touched an item, by any of the names GitHub's calls gave it. */
    static boolean matches(final TrackedItem item, final Touched.Ids touched) {
        if (touched.items().contains(item.id())) {
            return true;
        }
        final TrackedItem.Issue issue = item.issue();
        if (issue == null) {
            return false;
        }
        final String name = issue.repository();
        final String repository = name.substring(name.indexOf('/') + 1).toLowerCase(Locale.ROOT);
        return touched.issues().contains(issue.id())
                || touched.numbered().contains(repository + "#" + issue.number())
                || touched.databaseIds().contains(issue.key());
    }

    /** The issues' node ids of items a board holds, for a read of them by id. */
    static Set<String> issuesOf(final Snapshot board, final Iterable<String> itemIds) {
        final Set<String> issues = new java.util.LinkedHashSet<>();
        for (final String id : itemIds) {
            final GitHubGraphQl.ProjectItem item = board.raw().get(id);
            final GitHubGraphQl.BoardIssue issue = item == null ? null : item.issue();
            if (issue != null) {
                issues.add(issue.id());
            }
        }
        return issues;
    }
}
