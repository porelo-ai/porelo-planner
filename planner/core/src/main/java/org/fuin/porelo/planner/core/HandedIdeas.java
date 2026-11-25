package org.fuin.porelo.planner.core;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.fuin.porelo.planner.store.Basis;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Command;
import org.fuin.porelo.planner.store.CommandGroup;
import org.fuin.porelo.planner.store.CommandOutcome;
import org.fuin.porelo.planner.store.CommandQueue;
import org.fuin.porelo.planner.store.Ids;
import org.fuin.porelo.planner.store.NewIdea;
import org.fuin.porelo.planner.store.PlanningStore;
import org.fuin.porelo.planner.store.Recheck;
import org.fuin.porelo.planner.store.StoreException;
import org.jspecify.annotations.Nullable;

/**
 * Saves an idea a task reported once per report, keyed by the task's name and the report's number:
 * the key is written as pending before the store saves and as done with the story's id after, so
 * neither a report read again nor a planner stopped mid-save makes a second story.
 */
public final class HandedIdeas {

    /** A report whose idea's save began and has not ended. */
    public static final String PENDING = "PENDING";

    /** A report whose idea is saved as its story. */
    public static final String DONE = "DONE";

    /** A report whose idea reached some repositories but not all. */
    public static final String PARTLY = "PARTLY";

    /** The most done reports kept; a new one drops the oldest. */
    public static final int MOST_DONE = 1000;

    private HandedIdeas() {}

    /**
     * A report whose idea was taken.
     *
     * @param status {@value #PENDING}, {@value #DONE} or {@value #PARTLY}
     * @param story the story's id once done, else null
     * @param pushed the repositories already pushed when partly saved, else empty
     * @param order the record's generation when it was written, the oldest the lowest
     */
    public record Handed(String status, @Nullable String story, List<String> pushed, long order) {

        /** Copies the repositories. */
        public Handed {
            pushed = List.copyOf(pushed);
        }
    }

    /**
     * The key of a report: the task's container name and the report's number.
     *
     * @param container the task's container name
     * @param seq the report's number, 1 or more
     * @return {@code <container>#<seq>}
     * @throws IllegalArgumentException when the name is no task's name or the number is below 1
     */
    public static String key(final String container, final long seq) {
        Ids.task(container);
        if (seq < 1) {
            throw new IllegalArgumentException("A report's number is 1 or more");
        }
        return container + "#" + seq;
    }

    /**
     * Whether a key is a discussion's offer, the key ideas were kept under before they were taken
     * from reports: a canonical lowercase UUID.
     *
     * @param key the key
     * @return whether it is one
     */
    public static boolean offerKey(final String key) {
        try {
            return UUID.fromString(key).toString().equals(key);
        } catch (final IllegalArgumentException notOne) {
            return false;
        }
    }

    /** Whether a text is a report's number as {@link #key} writes it: 1 up to a long's most. */
    private static boolean isNumber(final String text) {
        if (!text.matches("[1-9][0-9]{0,18}")) {
            return false;
        }
        try {
            Long.parseLong(text);
            return true;
        } catch (final NumberFormatException overflow) {
            return false;
        }
    }

    /**
     * Checks a report's idea as the record keeps it.
     *
     * @param key the report's key
     * @param handed what the record keeps of it
     * @throws IllegalArgumentException when a part fails its check
     */
    public static void validate(final String key, final Handed handed) {
        final int hash = key.lastIndexOf('#');
        if (hash < 0
                || !Ids.isTask(key.substring(0, hash))
                || !isNumber(key.substring(hash + 1))
                || handed.order() < 0) {
            throw new IllegalArgumentException("a handed idea fails its check");
        }
        final String story = handed.story();
        switch (handed.status()) {
            case DONE -> {
                // A tracker answers the new story's reference, the Git store an ST number.
                if (story == null
                        || !Ids.isReference(story) && !story.matches("ST[1-9][0-9]{0,4}")) {
                    throw new IllegalArgumentException("a handed idea fails its check");
                }
                Ids.story(story);
            }
            case PENDING, PARTLY -> {
                if (story != null) {
                    throw new IllegalArgumentException("a handed idea fails its check");
                }
            }
            default -> throw new IllegalArgumentException("a handed idea fails its check");
        }
        handed.pushed().forEach(Ids::name);
    }

    /**
     * Saves a report's idea under the record's lock.
     *
     * @param store the project's store
     * @param queue the project's command queue, which writes the idea
     * @param session the locked record
     * @param snapshot the record as read under the lock
     * @param key the report's key, checked
     * @param idea the idea
     * @param shape the faults of the idea's shape its reader found
     * @param taken what else the write that records the saved story changes, so the report is taken
     *     in the same write
     * @return the story's id
     * @throws WorkRefusal {@code IdeaRefused} for an idea its check refuses, or a report whose save
     *     is pending or partly pushed; others as the record or the store refuse
     */
    static String save(
            final PlanningStore store,
            final CommandQueue queue,
            final WorkRun.Session session,
            final WorkRun.Snapshot snapshot,
            final String key,
            final NewIdeas.Idea idea,
            final List<String> shape,
            final java.util.function.UnaryOperator<WorkRun.Snapshot> taken) {
        final Handed known = snapshot.ideas().get(key);
        if (known != null) {
            return known(known);
        }
        final Board board = store.board();
        final NewIdea checked = NewIdeas.check(board, idea, shape);
        // A run whose story is gone would be taken over by a new story saved under its ID; it goes
        // in the same write that records the save as pending, so no stop between them keeps it.
        final WorkRun.Snapshot pending =
                with(
                        WorkRun.withoutGoneRuns(snapshot, board),
                        key,
                        new Handed(PENDING, null, List.of(), next(snapshot)));
        session.preflight(pending);
        session.write(pending);
        // An idea changes no item held, so no other save refuses it and no board is read whole;
        // its own check runs once more where the store saves it.
        // The command's id is the group's own, so an idea held in the outbox never blocks a later
        // idea's save under the same id.
        final String group = UUID.randomUUID().toString();
        final Command.CreateIdea create =
                new Command.CreateIdea(
                        group + "/0",
                        checked,
                        Basis.none(),
                        new Recheck(
                                Set.of(),
                                Set.of(),
                                dependencies -> NewIdeas.check(dependencies.board(), idea, shape)));
        // A queue that fails to answer may have written the idea: the report stays pending, so a
        // report read again is refused rather than saved a second time.
        final CommandOutcome outcome = queue.submit(new CommandGroup(group, List.of(create)));
        if (outcome instanceof CommandOutcome.Done done) {
            final String story = Objects.requireNonNull(done.created().get(create.id())).item();
            session.write(
                    taken.apply(
                            with(pending, key, new Handed(DONE, story, List.of(), next(pending)))));
            return story;
        }
        final Throwable refused;
        final boolean unwritten;
        if (outcome instanceof CommandOutcome.Refused refusal) {
            refused = refusal.cause();
            // The idea's own check runs where the store saves it, before anything is written.
            unwritten = !refusal.mayHaveWritten() || refused instanceof WorkRefusal;
        } else {
            // A held group stays for the owner account to resend, so its report stays pending and
            // a report read again never makes the idea a second time.
            refused = new StoreException.Busy("");
            unwritten = false;
        }
        if (refused instanceof StoreException.PartlySaved partly) {
            session.write(
                    with(pending, key, new Handed(PARTLY, null, partly.pushed(), next(pending))));
        } else if (unwritten) {
            session.write(without(pending, key));
        }
        // A refusal that may have written keeps the report pending, so no read again makes a
        // second idea.
        throw WorkRun.passedOn(refused);
    }

    private static String known(final Handed known) {
        final String story = known.story();
        if (DONE.equals(known.status()) && story != null) {
            return story;
        }
        final String reason =
                PARTLY.equals(known.status())
                        ? "The idea of this report was saved only in part and is not saved again."
                        : "The save of this report's idea did not end and is not made again.";
        throw WorkRefusal.ideaRefused(reason, List.of("report: " + reason), known.pushed());
    }

    private static long next(final WorkRun.Snapshot snapshot) {
        return snapshot.generation() + 1;
    }

    private static WorkRun.Snapshot with(
            final WorkRun.Snapshot snapshot, final String key, final Handed handed) {
        final Map<String, Handed> ideas = new LinkedHashMap<>(snapshot.ideas());
        ideas.put(key, handed);
        while (ideas.values().stream().filter(h -> DONE.equals(h.status())).count() > MOST_DONE) {
            ideas.entrySet().stream()
                    .filter(entry -> DONE.equals(entry.getValue().status()))
                    .min(Comparator.comparingLong(entry -> entry.getValue().order()))
                    .map(Map.Entry::getKey)
                    .ifPresent(ideas::remove);
        }
        return snapshot.withIdeas(next(snapshot), ideas);
    }

    private static WorkRun.Snapshot without(final WorkRun.Snapshot snapshot, final String key) {
        final Map<String, Handed> ideas = new LinkedHashMap<>(snapshot.ideas());
        ideas.remove(key);
        return snapshot.withIdeas(next(snapshot), ideas);
    }
}
