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
import org.fuin.porelo.planner.store.CommandQueue;
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
        final @Nullable Board board = done(outcome, store).board();
        return board != null ? board : store.board();
    }

    /**
     * Answers a group written whole, or throws what refused it as {@link #saved} does.
     *
     * @param outcome what became of the group
     * @param store the project's store, read for the repositories a refusal names
     * @return the group written whole
     */
    static CommandOutcome.Done done(final CommandOutcome outcome, final PlanningStore store) {
        return switch (outcome) {
            case CommandOutcome.Done done -> done;
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
     * What one command of the window wrote: the board after it when the queue answered one, and
     * what it made when it is a create. Nothing is read after the write here: a read that failed
     * then would answer a write that stands as failed, and the window's retry would write it again.
     *
     * @param board the board after it, or null when the queue answered none, as for a comment
     * @param created what a create made, or null for any other command
     */
    record Written(@Nullable Board board, CommandOutcome.@Nullable Created created) {

        /**
         * Answers the board after an edit judged on the versions the window saw, read now when the
         * queue answered none. A read that fails then answers an error although the edit stands;
         * the window's retry carries the same versions, which the edit moved, so it is refused as
         * changed and never written twice.
         *
         * @param store the project's store
         * @return the board after the edit
         */
        Board judged(final PlanningStore store) {
            return board != null ? board : store.board();
        }

        /**
         * Answers the board after a create, only as the queue answered it: a create's retry is
         * judged on no version of what it makes, so a read after it that failed would make it
         * twice.
         *
         * @return the board after the create
         * @throws StoreException.PartlySaved when the queue answered no board: the item stands and
         *     the window reads the board again rather than sending the create again
         */
        Board afterCreate() {
            if (board == null) {
                throw new StoreException.PartlySaved(
                        "",
                        List.of(),
                        made().item()
                                + " was added, and the board after it could not be read; read the"
                                + " board again.");
            }
            return board;
        }

        /**
         * Answers what the create made.
         *
         * @return what it made
         * @throws IllegalStateException when the queue answers nothing made
         */
        CommandOutcome.Created made() {
            if (created == null) {
                throw new IllegalStateException("The queue answered no item made by the create.");
            }
            return created;
        }
    }

    /**
     * Hands one command to a queue as a group of its own and answers what it wrote, or throws what
     * refused it, as the store gave it.
     *
     * @param queue the project's queue
     * @param store the project's store
     * @param command makes the command from the id it is given
     * @return the board the queue answered after it, never read here, and what it made
     */
    static Written one(
            final CommandQueue queue,
            final PlanningStore store,
            final java.util.function.Function<String, Command> command) {
        final String group = UUID.randomUUID().toString();
        final Command made = command.apply(group + "/0");
        final CommandOutcome.Done done =
                done(queue.submit(new CommandGroup(group, List.of(made))), store);
        return new Written(done.board(), done.created().get(made.id()));
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
