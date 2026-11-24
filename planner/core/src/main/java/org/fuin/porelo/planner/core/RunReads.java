package org.fuin.porelo.planner.core;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.Recheck;
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.planner.store.Story;
import org.fuin.porelo.planner.store.Version;
import org.jspecify.annotations.Nullable;

/**
 * The items a run's judgment reads, its story, the story's issues, their sub-issues and the parent
 * of each, and the recheck a task's save carries over them: such a save is refused only when one of
 * these items changed since the judgment, never when another item of the board did.
 */
final class RunReads {

    private RunReads() {}

    /**
     * Answers the items a run's judgment reads on a board.
     *
     * @param board the board
     * @param story the run's story
     * @return the story's id, its issues, their sub-issues and the parent of each, sorted
     */
    static Set<String> of(final Board board, final String story) {
        final Set<String> ids = new TreeSet<>();
        ids.add(story);
        final Set<String> issues =
                new LinkedHashSet<>(board.story(story).map(Story::issues).orElse(List.of()));
        ids.addAll(issues);
        for (final Issue issue : board.issues()) {
            final String parent = issue.parent();
            if (parent != null && issues.contains(parent)) {
                ids.add(issue.id());
            }
        }
        for (final String id : issues) {
            board.issue(id).map(Issue::parent).ifPresent(ids::add);
        }
        return ids;
    }

    /**
     * Answers the recheck of a task's save: the store reads every item the judgment read afresh, in
     * the same read as the save's targets, and the save is refused naming each item changed.
     *
     * @param judged the board the run was judged on
     * @param story the run's story
     * @return the recheck
     */
    static Recheck recheck(final Board judged, final String story) {
        return new Recheck(
                Set.of(Recheck.Need.STORY_ISSUES, Recheck.Need.PARENT),
                of(judged, story),
                dependencies -> unchanged(judged, dependencies.board(), story));
    }

    /**
     * Refuses when an item the judgment read differs on the board read afresh, or when the items it
     * would read there differ: a sub-issue attached since is named with them, so it is read again
     * before the run is judged once more.
     *
     * @param judged the board the run was judged on
     * @param now the board read afresh
     * @param story the run's story
     * @throws StoreException.Changed naming every such item
     */
    static void unchanged(final Board judged, final Board now, final String story) {
        final Set<String> ids = new TreeSet<>(of(judged, story));
        ids.addAll(of(now, story));
        final List<String> changed =
                ids.stream()
                        .filter(id -> !Objects.equals(version(judged, id), version(now, id)))
                        .toList();
        if (!changed.isEmpty()) {
            throw new StoreException.Changed(changed);
        }
    }

    private static @Nullable Version version(final Board board, final String id) {
        return board.issue(id)
                .map(Issue::version)
                .or(() -> board.story(id).map(Story::version))
                .orElse(null);
    }
}
