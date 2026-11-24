package org.fuin.porelo.planner.app;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.fuin.porelo.planner.store.Basis;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Command;
import org.fuin.porelo.planner.store.CommandGroup;
import org.fuin.porelo.planner.store.CommandOutcome;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.PlanningStore;
import org.fuin.porelo.planner.store.Recheck;
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.planner.store.Version;
import org.fuin.porelo.planner.store.Writes;
import org.jspecify.annotations.Nullable;

/**
 * A save of the window split into commands, one per item it writes, and the answer to what became
 * of them.
 */
final class CommandGroups {

    private CommandGroups() {}

    /**
     * Splits a save into one command per item: the issues first, the stories after them, each with
     * its own part of the writes and the version the caller saw of it alone. The first command
     * carries the save's recheck, so the read set is judged once, before the first write; each
     * later one is held by its own version.
     *
     * @param writes what the save writes
     * @param seen the versions the caller saw, by item id
     * @param recheck the rules checked again before the first write
     * @return the group
     */
    static CommandGroup of(final Writes writes, final Basis seen, final Recheck recheck) {
        final Set<String> targets = new LinkedHashSet<>();
        targets.addAll(writes.issues().keySet());
        targets.addAll(writes.workers().keySet());
        targets.addAll(writes.issueReasons().keySet());
        targets.addAll(writes.stories().keySet());
        targets.addAll(writes.priorities().keySet());
        targets.addAll(writes.storyReasons().keySet());
        final String group = UUID.randomUUID().toString();
        final List<Command> commands = new ArrayList<>();
        for (final String target : targets) {
            final Writes own =
                    new Writes(
                            only(writes.issues(), target),
                            only(writes.stories(), target),
                            only(writes.workers(), target),
                            only(writes.priorities(), target),
                            only(writes.issueReasons(), target),
                            only(writes.storyReasons(), target));
            final Version version = seen.of(target);
            commands.add(
                    new Command.Write(
                            group + "/" + commands.size(),
                            target,
                            own,
                            version == null ? Basis.none() : new Basis(Map.of(target, version)),
                            commands.isEmpty() ? recheck : Recheck.none()));
        }
        return new CommandGroup(group, commands);
    }

    /**
     * Answers the board after a group, or throws what refused it: a refusal before any command was
     * written as the store gave it, so the answer is the one a single save gives; a refusal after
     * one was written as {@link StoreException.PartlySaved}; a group held unsent as busy.
     *
     * @param outcome what became of the group
     * @param store the project's store, read for the board a written group does not answer and for
     *     the repositories a refusal names
     * @return the board after the group
     */
    static Board saved(final CommandOutcome outcome, final PlanningStore store) {
        return switch (outcome) {
            case CommandOutcome.Done done -> {
                final @Nullable Board board = done.board();
                yield board != null ? board : store.board();
            }
            case CommandOutcome.Refused refused -> {
                if (refused.done().isEmpty()) {
                    throw rethrown(refused.cause());
                }
                throw partly(refused, store.board());
            }
            case CommandOutcome.Held held -> throw new StoreException.Busy(own(store.board()));
        };
    }

    /**
     * The cause as it was thrown when it is unchecked, an {@link Error} too; any other kept as the
     * cause of an unchecked one.
     */
    private static RuntimeException rethrown(final Throwable cause) {
        if (cause instanceof RuntimeException unchecked) {
            return unchecked;
        }
        if (cause instanceof Error error) {
            throw error;
        }
        return new IllegalStateException(cause.getMessage(), cause);
    }

    /** A group refused after one of its commands was written. */
    private static StoreException.PartlySaved partly(
            final CommandOutcome.Refused refused, final Board board) {
        final Set<String> pushed = new LinkedHashSet<>();
        refused.done().forEach(command -> pushed.add(repository(board, target(command))));
        final String failed = target(refused.refused());
        final StoreException.PartlySaved partly =
                new StoreException.PartlySaved(
                        repository(board, failed),
                        List.copyOf(pushed),
                        refused.done().stream()
                                        .map(CommandGroups::target)
                                        .collect(Collectors.joining(", "))
                                + " saved; "
                                + failed
                                + " not saved: "
                                + refused.cause().getMessage());
        partly.initCause(refused.cause());
        return partly;
    }

    private static String target(final Command command) {
        return command instanceof Command.Write write ? write.target() : command.id();
    }

    /** The repository an item stands in: an issue's own, a story's the project's own. */
    private static String repository(final Board board, final String id) {
        return board.issue(id).map(Issue::repository).orElseGet(() -> own(board));
    }

    private static String own(final Board board) {
        return board.repositories().isEmpty() ? "" : board.repositories().getFirst().name();
    }

    private static <T> Map<String, T> only(final Map<String, T> map, final String id) {
        final Map<String, T> one = new LinkedHashMap<>();
        final T value = map.get(id);
        if (value != null) {
            one.put(id, value);
        }
        return one;
    }
}
