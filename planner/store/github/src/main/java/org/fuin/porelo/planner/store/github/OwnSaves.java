package org.fuin.porelo.planner.store.github;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.fuin.porelo.forge.github.GitHubGraphQl;
import org.fuin.porelo.planner.store.Version;
import org.fuin.porelo.planner.store.cache.ChangeCursor;
import org.fuin.porelo.planner.store.cache.OverlaidItem;
import org.jspecify.annotations.Nullable;

/**
 * The items the board holds over GitHub's last whole list: each item the planner saved or read by
 * id since, as that read gave it, with the versions GitHub's lists may still show of it.
 *
 * <p>GitHub's lists show a change only a moment after a read by id does. So a read by id is
 * current: it updates its item's entry and never releases one. Only a whole read's list releases,
 * by the item's version alone, never by times or the look's pages:
 *
 * <ul>
 *   <li>the list shows a version the entry keeps as earlier: the list lags, and the entry stays;
 *   <li>the list shows the version read by id: the list caught up, and the entry leaves;
 *   <li>the list shows any other version: someone changed the item since, and the list wins.
 * </ul>
 *
 * <p>An item the list does not carry, or that GitHub says is gone, has the version {@link #ABSENT},
 * so an item just made stays while the list lacks it, and one gone stays gone.
 *
 * <p>A read by id begun before the list that released its item is older than that list, so it
 * neither takes the item up again nor lays its answer over the list's.
 */
final class OwnSaves {

    /** The version of an item a read does not carry, or GitHub says is gone from the board. */
    static final Version ABSENT = new Version("absent");

    /**
     * The most reads by id the looks make of one entry no page of their list carries; the next
     * whole read still takes it after.
     */
    static final int MOST_READS_BY_ID = 3;

    /**
     * One item held over the list.
     *
     * @param item the item as last read by id; when gone, as last seen, which names its issue
     * @param gone whether the last read by id found the item no longer on the board
     * @param version its version as last read by id, {@link #ABSENT} when gone
     * @param earlier the versions a list may still show of it, none of them its version
     * @param read when the read by id that gave it began, as {@link #begin()} counts
     */
    private record Entry(
            GitHubGraphQl.ProjectItem item,
            boolean gone,
            Version version,
            Set<Version> earlier,
            long read) {}

    /**
     * An item a whole list released.
     *
     * @param issue its issue's node id, or null when it names none
     * @param listed when the read of that list began, as {@link #begin()} counts
     */
    private record Released(@Nullable String issue, long listed) {}

    private final Map<String, Entry> entries = new LinkedHashMap<>();

    /** The items whole lists released, kept while the board lists them or one read after. */
    private final Map<String, Released> released = new HashMap<>();

    /** How often a look read each entry by id that no page of its list carries. */
    private final Map<String, Integer> looked = new HashMap<>();

    /** Counts the reads by id begun, so an older read never replaces a newer one's answer. */
    private long ticks;

    /**
     * Marks a read by id as begun, before GitHub is asked.
     *
     * @return the read's place among the reads begun
     */
    synchronized long begin() {
        return ++ticks;
    }

    /**
     * Takes what a read by id found: each item whose version moved becomes its entry's newest form,
     * the version it replaces kept as one a list may still show; an item whose version did not move
     * holds nothing. An item a read begun later answered already is left alone; one a list read
     * after this read began released, {@link #readSince(long)} names, so the read leaves it out.
     *
     * @param before the board the read was laid onto
     * @param after the board with the read laid on
     * @param itemIds the items read, those GitHub no longer holds on the board among them
     * @param began when the read began, from {@link #begin()}
     */
    synchronized void readById(
            final Snapshot before,
            final Snapshot after,
            final Collection<String> itemIds,
            final long began) {
        for (final String id : itemIds) {
            final Entry entry = entries.get(id);
            if (entry != null && entry.read() > began) {
                // A read begun after this one answered the item already.
                continue;
            }
            final GitHubGraphQl.ProjectItem now = after.raw().get(id);
            final GitHubGraphQl.ProjectItem was = before.raw().get(id);
            final Version is = version(now, after);
            final Version had = entry != null ? entry.version() : version(was, before);
            if (is.equals(had)) {
                continue;
            }
            final GitHubGraphQl.ProjectItem shown =
                    now != null ? now : entry != null ? entry.item() : was;
            if (shown == null || shown.issue() == null) {
                // No planning item: nothing to lay over a list or to name.
                continue;
            }
            final Set<Version> earlier =
                    new LinkedHashSet<>(entry == null ? Set.of() : entry.earlier());
            earlier.add(had);
            earlier.remove(is);
            entries.put(id, new Entry(shown, now == null, is, earlier, began));
            released.remove(id);
        }
    }

    /**
     * Answers the issues' node ids of the entries a read begun after the one given answered, and of
     * the items a list read after it began released, whose form that read must leave alone.
     *
     * @param began when the read began, from {@link #begin()}
     * @return the issues' node ids
     */
    synchronized Set<String> readSince(final long began) {
        final Set<String> issues = new LinkedHashSet<>();
        for (final Entry entry : entries.values()) {
            final GitHubGraphQl.BoardIssue issue = entry.item().issue();
            if (entry.read() > began && issue != null) {
                issues.add(issue.id());
            }
        }
        for (final Released let : released.values()) {
            if (let.listed() > began && let.issue() != null) {
                issues.add(let.issue());
            }
        }
        return issues;
    }

    /**
     * Takes a whole read's list: releases each entry whose item the list shows at any version but
     * one it may still show while it lags. An empty list is taken as any other: the project holds
     * no items yet, or none the list shows yet.
     *
     * @param list the board as GitHub's whole list gave it, before any read by id was laid on
     * @param began when the read of the list began, from {@link #begin()}
     */
    synchronized void listed(final Snapshot list, final long began) {
        final Set<String> now = new HashSet<>();
        final Iterator<Map.Entry<String, Entry>> each = entries.entrySet().iterator();
        while (each.hasNext()) {
            final Map.Entry<String, Entry> held = each.next();
            final Entry entry = held.getValue();
            if (entry.earlier().contains(version(list.raw().get(held.getKey()), list))) {
                continue;
            }
            final GitHubGraphQl.BoardIssue issue = entry.item().issue();
            released.put(held.getKey(), new Released(issue == null ? null : issue.id(), began));
            now.add(held.getKey());
            each.remove();
        }
        // Bounded by the board: an item off the list keeps its mark for this one read only.
        released.keySet().removeIf(id -> !now.contains(id) && !list.raw().containsKey(id));
        looked.keySet().retainAll(entries.keySet());
    }

    /**
     * Answers what the entries lay over a board: each item in its form read by id, and each one
     * gone taken off.
     *
     * @param board the board
     * @return what to lay onto the board, or null when it holds every entry as it is
     */
    synchronized @Nullable Reread over(final Snapshot board) {
        final Map<String, GitHubGraphQl.ProjectItem> put = new LinkedHashMap<>();
        final Set<String> issues = new LinkedHashSet<>();
        final Map<String, Boolean> archived = new LinkedHashMap<>();
        boolean differs = false;
        for (final Map.Entry<String, Entry> each : entries.entrySet()) {
            final Entry entry = each.getValue();
            final GitHubGraphQl.BoardIssue issue = entry.item().issue();
            if (issue == null) {
                continue;
            }
            issues.add(issue.id());
            if (entry.gone()) {
                differs |=
                        board.raw().values().stream()
                                .anyMatch(
                                        item -> {
                                            final GitHubGraphQl.BoardIssue other = item.issue();
                                            return other != null && issue.id().equals(other.id());
                                        });
            } else {
                put.put(each.getKey(), entry.item());
                // The archived flag as read: a whole list lagging it would otherwise put it back.
                archived.put(each.getKey(), entry.item().archived());
                differs |= !entry.item().equals(board.raw().get(each.getKey()));
            }
        }
        return differs ? new Reread(board.config(), null, put, issues, archived, Map.of()) : null;
    }

    /**
     * Answers the entries of items no page of a look's list carries, to be read again by id, as the
     * list cannot show GitHub's later changes to them; one a save made while the look ran touched
     * is left for the next look, one the look read by id itself needs no second read, and each is
     * read so at most {@link #MOST_READS_BY_ID} times.
     *
     * @param cursor where the look stands now
     * @param during what the saves made while the look ran touched
     * @param began when the look's own reads by id began, from {@link #begin()}
     * @return the items' ids by their issues' node ids
     */
    synchronized Map<String, String> unlisted(
            final ChangeCursor cursor, final List<Touched.Ids> during, final long began) {
        final Set<String> carried = new HashSet<>();
        cursor.pages().forEach(page -> carried.addAll(page.itemIds()));
        final Map<String, String> unlisted = new LinkedHashMap<>();
        for (final Map.Entry<String, Entry> each : entries.entrySet()) {
            final Entry entry = each.getValue();
            final GitHubGraphQl.BoardIssue issue = entry.item().issue();
            if (entry.gone()
                    || issue == null
                    || carried.contains(each.getKey())
                    || entry.read() >= began
                    || during.stream().anyMatch(touched -> matches(entry.item(), touched))
                    || looked.getOrDefault(each.getKey(), 0) >= MOST_READS_BY_ID) {
                continue;
            }
            unlisted.put(issue.id(), each.getKey());
            looked.merge(each.getKey(), 1, Integer::sum);
        }
        return unlisted;
    }

    /**
     * Drops the entries of a write that failed: what GitHub holds of them is read anew.
     *
     * @param touched what the write touched
     */
    synchronized void forget(final Touched.Ids touched) {
        entries.values().removeIf(entry -> matches(entry.item(), touched));
        looked.keySet().retainAll(entries.keySet());
    }

    /** Drops every entry, as the board is dropped and read anew from GitHub. */
    synchronized void clear() {
        entries.clear();
        looked.clear();
        released.clear();
    }

    /** How many entries are held. */
    synchronized int size() {
        return entries.size();
    }

    /**
     * Takes the entries a board's copy kept, so a start keeps an item the list still lags; an entry
     * that cannot be taken refuses them all and leaves those held as they were.
     *
     * @param items the copy's entries
     */
    synchronized void load(final List<OverlaidItem> items) {
        final Map<String, Entry> loaded = new LinkedHashMap<>();
        for (final OverlaidItem item : items) {
            final Set<Version> earlier = new LinkedHashSet<>();
            item.earlier().forEach(value -> earlier.add(new Version(value)));
            loaded.put(
                    item.id(),
                    new Entry(
                            BoardCopyMapping.item(item.item()),
                            item.gone(),
                            new Version(item.version()),
                            earlier,
                            0L));
        }
        clear();
        entries.putAll(loaded);
    }

    /**
     * Answers the entries as the board's copy keeps them.
     *
     * @return the entries, in the order they were first held
     */
    synchronized List<OverlaidItem> copied() {
        final List<OverlaidItem> copied = new ArrayList<>();
        entries.forEach(
                (id, entry) ->
                        copied.add(
                                new OverlaidItem(
                                        id,
                                        BoardCopyMapping.tracked(entry.item()),
                                        entry.gone(),
                                        entry.version().value(),
                                        entry.earlier().stream().map(Version::value).toList())));
        return copied;
    }

    /**
     * An item's version as the board maps it, so an item of a list and one read by id compare; an
     * item the board leaves out, or none, is {@link #ABSENT}.
     *
     * @param item the item, or null when the read does not carry it
     * @param board the board it was read into, which names the fields
     * @return its version
     */
    static Version version(final GitHubGraphQl.@Nullable ProjectItem item, final Snapshot board) {
        if (item == null) {
            return ABSENT;
        }
        final ItemView view =
                ItemsBoardReader.view(
                        item,
                        board.config(),
                        board.fieldNames(),
                        new ArrayList<>(),
                        new HashSet<>());
        return view == null ? ABSENT : ItemVersions.of(view);
    }

    /** Whether a write touched an item, by any of the names GitHub's calls gave it. */
    private static boolean matches(
            final GitHubGraphQl.ProjectItem item, final Touched.Ids touched) {
        if (touched.items().contains(item.id())) {
            return true;
        }
        final GitHubGraphQl.BoardIssue issue = item.issue();
        if (issue == null) {
            return false;
        }
        final String name = issue.repository();
        final String repository = name.substring(name.indexOf('/') + 1).toLowerCase(Locale.ROOT);
        return touched.issues().contains(issue.id())
                || touched.numbered().contains(repository + "#" + issue.number())
                || touched.databaseIds().contains(issue.databaseId());
    }
}
