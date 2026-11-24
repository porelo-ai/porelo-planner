package org.fuin.porelo.planner.store.github;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import org.fuin.porelo.planner.store.Basis;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Command;
import org.fuin.porelo.planner.store.CommandGroup;
import org.fuin.porelo.planner.store.CommandShown;
import org.fuin.porelo.planner.store.Recheck;
import org.fuin.porelo.planner.store.Worker;
import org.fuin.porelo.planner.store.Workflow;
import org.fuin.porelo.planner.store.Writes;
import org.fuin.porelo.planner.store.Written;
import org.jspecify.annotations.Nullable;

/**
 * What the store does with a command whose item moved since it was judged: done when the item shows
 * every change already, merged onto the item as it stands, or a conflict for the owner account.
 */
final class CommandWrites {

    /** What a window's move reads of its item beyond what it writes. */
    static final Set<Place> MOVE_READS = Set.of(Place.BLOCKERS, Place.PARENT, Place.SUB_ISSUES);

    private CommandWrites() {}

    /** What becomes of a moved command. */
    sealed interface Decision permits Done, Conflict, Rebased {}

    /** Nothing left to write. */
    record Done() implements Decision {}

    /**
     * Nothing written; the owner account decides.
     *
     * @param places the places, by their fixed names
     */
    record Conflict(List<String> places) implements Decision {}

    /**
     * What is left to write, checked against the item as it stands now.
     *
     * @param writes the writes left
     * @param basis the item's version now
     */
    record Rebased(Writes writes, Basis basis) implements Decision {}

    /**
     * The rule a group is judged by: every command's rule, on one read of all their needs.
     *
     * @param group the group
     * @return the joined rule
     */
    static Recheck union(final CommandGroup group) {
        final Set<Recheck.Need> needs = new LinkedHashSet<>();
        final Set<String> from = new LinkedHashSet<>();
        for (final Command command : group.commands()) {
            needs.addAll(command.recheck().needs());
            from.addAll(command.recheck().from());
        }
        return new Recheck(
                needs,
                from,
                dependencies ->
                        group.commands()
                                .forEach(command -> command.recheck().rule().accept(dependencies)));
    }

    /**
     * Decides a write whose item moved since it was judged.
     *
     * @param write the command
     * @param judged the item as it was judged, or null when it is not kept
     * @param now the item as GitHub holds it now
     * @param board the board read now
     * @param workflow the project's workflow
     * @return done, merged, or a conflict
     */
    static Decision decide(
            final Command.Write write,
            final @Nullable ItemView judged,
            final ItemView now,
            final Board board,
            final Workflow workflow) {
        if (CommandShown.shows(write, board)) {
            return new Done();
        }
        if (judged == null) {
            return new Conflict(List.of(Place.Kind.ITEM.name()));
        }
        final MergeTargets targets = targets(write, IssueMapping.isStory(now), workflow);
        return switch (CommandMerge.merge(targets, judged, now, false)) {
            case CommandMerge.Nothing nothing -> new Done();
            case CommandMerge.Conflict conflict -> new Conflict(names(conflict.places()));
            case CommandMerge.Write rebased ->
                    new Rebased(
                            writes(rebased.rebased(), write),
                            new Basis(Map.of(write.target(), ItemVersions.of(rebased.on()))));
        };
    }

    /** What a window's write writes of its item, in the merge's terms. */
    static MergeTargets targets(
            final Command.Write write, final boolean story, final Workflow workflow) {
        final String id = write.target();
        final Writes writes = write.writes();
        final String name = story ? writes.stories().get(id) : writes.issues().get(id);
        MergeTargets.State state = null;
        if (name != null) {
            final boolean closed = workflow.closed().contains(name);
            // The first closed state is done; any later one, such as Rejected, is not planned.
            final boolean notPlanned = closed && workflow.closed().indexOf(name) > 0;
            state = new MergeTargets.State(closed || story ? null : name, !closed, notPlanned);
        }
        final Worker worker = writes.workers().get(id);
        return new MergeTargets(
                id,
                story,
                state,
                story ? writes.storyReasons().get(id) : writes.issueReasons().get(id),
                worker == null ? null : worker == Worker.MANUAL ? "Manual" : "Agent",
                writes.priorities().get(id),
                null,
                null,
                Map.of(),
                Set.of(),
                MOVE_READS);
    }

    /** The writes of a merge's targets left, in the command's own words. */
    static Writes writes(final MergeTargets left, final Command.Write write) {
        final String id = write.target();
        final Writes writes = write.writes();
        final Map<String, String> issues = new LinkedHashMap<>();
        final Map<String, String> stories = new LinkedHashMap<>();
        final Map<String, Worker> workers = new LinkedHashMap<>();
        final Map<String, String> priorities = new LinkedHashMap<>();
        final Map<String, Writes.Reason> issueReasons = new LinkedHashMap<>();
        final Map<String, Writes.Reason> storyReasons = new LinkedHashMap<>();
        if (left.state() != null) {
            if (left.story()) {
                stories.put(id, writes.stories().get(id));
            } else {
                issues.put(id, writes.issues().get(id));
            }
        }
        final Writes.Reason reason = left.reason();
        if (reason != null) {
            (left.story() ? storyReasons : issueReasons).put(id, reason);
        }
        if (left.worker() != null) {
            workers.put(id, "Manual".equals(left.worker()) ? Worker.MANUAL : Worker.AGENT);
        }
        if (left.priority() != null) {
            priorities.put(id, writes.priorities().get(id));
        }
        return new Writes(issues, stories, workers, priorities, issueReasons, storyReasons);
    }

    /** The places by their fixed names; a name that is no plain word is left out of it. */
    static List<String> names(final SortedSet<Place> places) {
        final List<String> names = new ArrayList<>();
        for (final Place place : places) {
            final String named =
                    place.name() == null
                            ? place.kind().name()
                            : place.kind().name() + ":" + place.name();
            names.add(
                    Written.Conflict.PLACE.matcher(named).matches() ? named : place.kind().name());
            if (names.size() == Written.Conflict.MOST) {
                break;
            }
        }
        return names;
    }
}
