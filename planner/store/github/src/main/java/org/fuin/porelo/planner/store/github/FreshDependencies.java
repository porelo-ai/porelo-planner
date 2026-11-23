package org.fuin.porelo.planner.store.github;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Dependencies;
import org.fuin.porelo.planner.store.Recheck;
import org.fuin.porelo.planner.store.Story;

/**
 * The dependencies a write's rules read, read afresh from GitHub inside the store's write lock: the
 * items a recheck needs are read again by id onto the board held, the blocker graph walked until no
 * further blocker is an item of the board; the ranks of one priority and an unlisted issue's links
 * are read only when a rule asks for them. A recheck that needs nothing reads nothing.
 */
final class FreshDependencies implements Dependencies {

    private final Snapshot held;

    private final BiFunction<Snapshot, Touched.Ids, Snapshot> reread;

    private final BoardWriter writer;

    private final Board board;

    /**
     * Reads what the recheck needs.
     *
     * @param held the snapshot the store holds
     * @param recheck what the rules need
     * @param reread reads the issues named by their node ids again onto a snapshot
     * @param writer reads an unlisted issue's links
     */
    FreshDependencies(
            final Snapshot held,
            final Recheck recheck,
            final BiFunction<Snapshot, Touched.Ids, Snapshot> reread,
            final BoardWriter writer) {
        this.held = held;
        this.reread = reread;
        this.writer = writer;
        this.board = recheck.needs().isEmpty() ? held.board() : walk(recheck).board();
    }

    @Override
    public Board board() {
        return board;
    }

    /**
     * Reads every item of the kind again, so an item that took the priority since the board was
     * read counts, and answers the raw ranks of those that hold the priority now.
     */
    @Override
    public List<Double> ranks(final boolean story, final String priority) {
        final Set<String> nodes = new LinkedHashSet<>();
        held.items()
                .values()
                .forEach(
                        item -> {
                            if (IssueMapping.isStory(item) == story) {
                                nodes.add(item.issueId());
                            }
                        });
        final Snapshot now = nodes.isEmpty() ? held : reread.apply(held, ids(nodes));
        final List<Double> ranks = new ArrayList<>();
        for (final ItemView item : now.items().values()) {
            if (IssueMapping.isStory(item) != story || !priority.equals(priorityOf(item))) {
                continue;
            }
            final String rank = item.fields().get(FieldNames.RANK);
            if (rank == null) {
                continue;
            }
            try {
                ranks.add(Double.parseDouble(rank));
            } catch (final NumberFormatException e) {
                // An unreadable rank contributes no value.
            }
        }
        return ranks;
    }

    @Override
    public UnlistedLinks unlisted(final String issue) {
        return writer.unlistedLinks(held, issue);
    }

    /** An item's priority, Medium for one it does not hold or the board does not know. */
    private static String priorityOf(final ItemView item) {
        final String priority = item.fields().getOrDefault(FieldNames.PRIORITY, "");
        return Story.PRIORITIES.contains(priority) ? priority : "Medium";
    }

    /**
     * Reads the items named again, then what they lead to: a story's issues and an issue's parent
     * once, and the blockers of every item read until none is left that the board holds.
     */
    private Snapshot walk(final Recheck recheck) {
        final Map<String, String> idOf = new LinkedHashMap<>();
        held.items().forEach((id, item) -> idOf.put(item.issueId(), id));
        final Set<String> wanted = new LinkedHashSet<>();
        for (final String id : recheck.from()) {
            final ItemView item = held.items().get(id);
            if (item != null) {
                wanted.add(item.issueId());
            }
            if (recheck.needs().contains(Recheck.Need.STORY_ISSUES)) {
                held.board()
                        .story(id)
                        .ifPresent(
                                story ->
                                        story.issues()
                                                .forEach(
                                                        issue -> {
                                                            final ItemView each =
                                                                    held.items().get(issue);
                                                            if (each != null) {
                                                                wanted.add(each.issueId());
                                                            }
                                                        }));
            }
        }
        Snapshot now = held;
        final Set<String> read = new LinkedHashSet<>();
        boolean first = true;
        while (!read.containsAll(wanted)) {
            final Set<String> next = new LinkedHashSet<>(wanted);
            next.removeAll(read);
            now = reread.apply(now, ids(next));
            read.addAll(next);
            for (final String node : next) {
                final String id = idOf.get(node);
                final ItemView item = id == null ? null : now.items().get(id);
                if (item == null) {
                    continue;
                }
                final boolean named = first && recheck.from().contains(id);
                if (named && recheck.needs().contains(Recheck.Need.STORY_ISSUES)) {
                    held(item.subIssueIds(), idOf, wanted);
                }
                final String parent = item.parentIssueId();
                if (named && parent != null && recheck.needs().contains(Recheck.Need.PARENT)) {
                    held(List.of(parent), idOf, wanted);
                }
                if (recheck.needs().contains(Recheck.Need.BLOCKERS)) {
                    held(item.blockedByIds(), idOf, wanted);
                }
            }
            first = false;
        }
        return now;
    }

    /** Adds the node ids among those given that name items of the board. */
    private static void held(
            final List<String> nodes, final Map<String, String> idOf, final Set<String> wanted) {
        for (final String node : nodes) {
            if (idOf.containsKey(node)) {
                wanted.add(node);
            }
        }
    }

    private static Touched.Ids ids(final Set<String> nodes) {
        return new Touched.Ids(nodes, Set.of(), Set.of(), Set.of());
    }
}
