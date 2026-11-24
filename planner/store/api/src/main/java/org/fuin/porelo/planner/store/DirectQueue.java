package org.fuin.porelo.planner.store;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Writes a group by calling the store's own methods in order, with nothing kept on disk; it stands
 * in until commands are queued and run on their own. Consecutive writes are joined into one save,
 * so a move is made with today's one call and today's all or none; any other command is one call.
 */
public final class DirectQueue implements CommandQueue {

    private final PlanningStore store;

    /**
     * Makes the queue over one project's store.
     *
     * @param store the store
     */
    public DirectQueue(final PlanningStore store) {
        this.store = store;
    }

    @Override
    public CommandOutcome submit(final CommandGroup group) {
        final List<Command> commands = group.commands();
        final List<Command> done = new ArrayList<>();
        final Map<String, CommandOutcome.Created> created = new LinkedHashMap<>();
        @Nullable Board board = null;
        for (int index = 0; index < commands.size(); ) {
            final Command command = commands.get(index);
            final int run = run(commands, index);
            final Board after;
            try {
                after =
                        run > 1
                                ? saved(commands.subList(index, index + run))
                                : written(command, created);
            } catch (final RuntimeException | Error refused) {
                // An Error is kept as the cause: thrown on, it would lose which commands stand.
                return new CommandOutcome.Refused(
                        command, refused, done, commands.subList(index + 1, commands.size()));
            }
            if (after != null) {
                board = after;
            }
            done.addAll(commands.subList(index, index + run));
            index += run;
        }
        return new CommandOutcome.Done(board != null ? board : held(), created);
    }

    /**
     * The board the store holds, or null when it cannot answer: every command is written by then,
     * so a failed read must not hide that.
     */
    private @Nullable Board held() {
        try {
            return store.board();
        } catch (final RuntimeException | Error unread) {
            return null;
        }
    }

    /**
     * How many writes from the one given make one save, as a move is saved today: the writes that
     * follow it, each of another target and judged on the same versions of the items they share.
     */
    private static int run(final List<Command> commands, final int from) {
        if (!(commands.get(from) instanceof Command.Write)) {
            return 1;
        }
        final Set<String> targets = new HashSet<>();
        final Map<String, Version> judged = new HashMap<>();
        int length = 0;
        for (int at = from; at < commands.size(); at++) {
            if (!(commands.get(at) instanceof Command.Write write)
                    || !targets.add(write.target())
                    || !agrees(judged, write.basis())) {
                break;
            }
            judged.putAll(write.basis().items());
            length++;
        }
        return length;
    }

    private static boolean agrees(final Map<String, Version> judged, final Basis basis) {
        return basis.items().entrySet().stream()
                .allMatch(e -> e.getValue().equals(judged.getOrDefault(e.getKey(), e.getValue())));
    }

    /**
     * Saves a run of writes in one call, as a move is saved today: their bases and writes joined,
     * the read set judged once by the first one's recheck.
     */
    private Board saved(final List<Command> run) {
        final Map<String, Version> basis = new LinkedHashMap<>();
        final Map<String, String> issues = new LinkedHashMap<>();
        final Map<String, String> stories = new LinkedHashMap<>();
        final Map<String, Worker> workers = new LinkedHashMap<>();
        final Map<String, String> priorities = new LinkedHashMap<>();
        final Map<String, Writes.Reason> issueReasons = new LinkedHashMap<>();
        final Map<String, Writes.Reason> storyReasons = new LinkedHashMap<>();
        for (final Command command : run) {
            final Command.Write write = (Command.Write) command;
            basis.putAll(write.basis().items());
            issues.putAll(write.writes().issues());
            stories.putAll(write.writes().stories());
            workers.putAll(write.writes().workers());
            priorities.putAll(write.writes().priorities());
            issueReasons.putAll(write.writes().issueReasons());
            storyReasons.putAll(write.writes().storyReasons());
        }
        return store.save(
                new Basis(basis),
                new Writes(issues, stories, workers, priorities, issueReasons, storyReasons),
                run.getFirst().recheck());
    }

    /** Writes one command; answers the board after it, or null for a comment, which has none. */
    private @Nullable Board written(
            final Command command, final Map<String, CommandOutcome.Created> created) {
        final Written.Done done = one(store, command);
        final CommandOutcome.Created made = done.created();
        if (made != null) {
            created.put(command.id(), made);
        }
        return done.board();
    }

    /**
     * Writes one command alone through the store's own call for it.
     *
     * @param store the store
     * @param command the command
     * @return the board after it, null for a comment, which has none, and what a create made
     */
    static Written.Done one(final PlanningStore store, final Command command) {
        return switch (command) {
            case Command.Write write ->
                    new Written.Done(
                            store.save(write.basis(), write.writes(), write.recheck()), null);
            case Command.SetFields set ->
                    new Written.Done(
                            store.setFields(set.basis(), set.issue(), set.fields(), set.recheck()),
                            null);
            case Command.SetText set ->
                    new Written.Done(
                            store.setText(set.basis(), set.item(), set.edit(), set.recheck()),
                            null);
            case Command.SetBlockers set ->
                    new Written.Done(
                            store.setBlockedBy(
                                    set.basis(), set.issue(), set.blockedBy(), set.recheck()),
                            null);
            case Command.SetStoryIssues set ->
                    new Written.Done(
                            store.setStoryIssues(
                                    set.basis(), set.story(), set.issues(), set.recheck()),
                            null);
            case Command.CorrectLabels labels ->
                    new Written.Done(
                            store.correctLabels(labels.repository(), labels.calls()), null);
            case Command.Comment comment -> {
                store.comment(comment.issue(), comment.text());
                yield new Written.Done(null, null);
            }
            case Command.CreateIssue create -> {
                final NewIssue.Added added =
                        store.createIssue(create.basis(), create.issue(), create.recheck());
                yield new Written.Done(
                        added.board(), new CommandOutcome.Created(added.id(), List.of()));
            }
            case Command.CreateIdea create -> {
                final NewIdea.Added added =
                        store.createIdea(create.basis(), create.idea(), create.recheck());
                yield new Written.Done(
                        added.board(), new CommandOutcome.Created(added.story(), added.issues()));
            }
            case Command.CreateStory create -> {
                final NewIdea.Added added =
                        store.createStoryFromIssues(
                                create.basis(), create.story(), create.recheck());
                yield new Written.Done(
                        added.board(), new CommandOutcome.Created(added.story(), added.issues()));
            }
            case Command.AddUnlisted add ->
                    new Written.Done(
                            store.addUnlistedIssue(add.basis(), add.issue(), add.recheck()),
                            new CommandOutcome.Created(add.issue(), List.of()));
        };
    }
}
