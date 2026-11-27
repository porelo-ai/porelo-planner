package org.fuin.porelo.planner.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.fuin.porelo.planner.store.Basis;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Command;
import org.fuin.porelo.planner.store.CommandGroup;
import org.fuin.porelo.planner.store.CommandOutcome;
import org.fuin.porelo.planner.store.CommandQueue;
import org.fuin.porelo.planner.store.DirectQueue;
import org.fuin.porelo.planner.store.Ids;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.PlanningStore;
import org.fuin.porelo.planner.store.Recheck;
import org.fuin.porelo.planner.store.Role;
import org.fuin.porelo.planner.store.StageResult;
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.planner.store.Story;
import org.fuin.porelo.planner.store.Wait;
import org.fuin.porelo.planner.store.WorkRule;
import org.fuin.porelo.planner.store.Worker;
import org.fuin.porelo.planner.store.Writes;
import org.jspecify.annotations.Nullable;

/** Runs story work under one project record lock; only the window starts and stops tasks. */
public final class WorkRun implements RefusedEventResave {

    private static final Pattern COMMIT = Pattern.compile("[0-9a-f]{40}|[0-9a-f]{64}");
    private static final Pattern AT =
            Pattern.compile(
                    "[0-9]{4}-[0-9]{2}-[0-9]{2}[Tt][0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.[0-9]+)?(?:[Zz]|[+-][0-9]{2}:[0-9]{2})");
    private static final Writes NOTHING = new Writes(Map.of(), Map.of());
    private final PlanningStore store;
    private final Record record;
    private final Clock clock;
    private final java.util.function.Consumer<String> told;
    private final CommandQueue queue;

    /** The stories a resent report waits on, at most one waiter each. */
    private final Set<String> waiting = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Supplies the store, durable record and time source without depending on Spring. */
    public WorkRun(final PlanningStore store, final Record record, final Clock clock) {
        this(store, record, clock, why -> {});
    }

    /**
     * Supplies the store, durable record and time source, and where the run says in one line why an
     * issue's next step could not be taken, once the run that says so is saved.
     *
     * @param store the planning store
     * @param record the durable work record
     * @param clock the time source
     * @param told takes one line per issue whose next step is unavailable
     */
    public WorkRun(
            final PlanningStore store,
            final Record record,
            final Clock clock,
            final java.util.function.Consumer<String> told) {
        this(store, record, clock, told, new DirectQueue(store));
    }

    /**
     * Supplies everything above and the queue a run's commands are written through.
     *
     * @param store the planning store
     * @param record the durable work record
     * @param clock the time source
     * @param told takes one line per issue whose next step is unavailable, and one per save whose
     *     outcome could not be recorded
     * @param queue the project's command queue
     */
    public WorkRun(
            final PlanningStore store,
            final Record record,
            final Clock clock,
            final java.util.function.Consumer<String> told,
            final CommandQueue queue) {
        this.store = store;
        this.record = record;
        this.clock = clock;
        this.told = told;
        this.queue = queue;
    }

    /**
     * A project writer holds its lock across every record operation in the action; a save in flight
     * marks the record in a short step and records its outcome in another, holding no lock while
     * its commands are written.
     */
    public interface Record {
        /**
         * Reads the last complete written generation without waiting for a writer or repairing it.
         */
        Snapshot latest();

        /** Refuses competing writers rather than waiting on a different planner. */
        <T extends @Nullable Object> T locked(Action<T> action);

        /**
         * Marks a save in flight: applies {@code change} to the record as it now stands and writes
         * it, without waiting for a caller of {@link #locked}.
         *
         * @param id the save's own id
         * @param change what the mark changes
         * @return the mark, for its outcome
         */
        Mark mark(String id, java.util.function.UnaryOperator<Snapshot> change);

        /**
         * Records a save's outcome: applies {@code change} to the record as it now stands, writes
         * it and ends the mark; a write that fails leaves the mark standing.
         *
         * @param mark the save's mark
         * @param change what the outcome changes
         * @return the record as it now stands
         */
        Snapshot outcome(Mark mark, java.util.function.UnaryOperator<Snapshot> change);

        /**
         * Ends a mark with nothing written; the record keeps what the mark wrote, for the repair at
         * the next open.
         *
         * @param mark the save's mark
         */
        void abandon(Mark mark);

        /**
         * Waits, at most the record's own bound, until no mark of that id stands.
         *
         * @param id the save's id
         * @return whether no such mark stands any more
         */
        boolean awaitEnded(String id);
    }

    /** A save's mark on the record, between the step that marks and the one that settles it. */
    public interface Mark {
        /**
         * Answers the id the save gave its mark.
         *
         * @return the id
         */
        String id();
    }

    /** A unit of work performed entirely inside the record lock. */
    @FunctionalInterface
    public interface Action<T extends @Nullable Object> {
        /** Uses the locked record session. */
        T apply(Session session);
    }

    /** The locked record, with a capacity check before any store save. */
    public interface Session {
        /** Reads a bounded, validated snapshot inside the lock. */
        Snapshot read();

        /** Durably replaces the complete record before returning. */
        void write(Snapshot snapshot);

        /** Refuses a record that cannot be published, including space reserved for active ends. */
        void preflight(Snapshot snapshot);

        /** Also checks the issue carried by a reading decision before its state is saved. */
        default void preflightIssue(
                final Snapshot snapshot, final Board board, final String issue) {
            preflight(snapshot);
        }
    }

    /**
     * The current runs, the ideas taken from reports by task and number, the stage offers whose
     * results were handed in, the offers whose tasks' reports are read and the last report taken of
     * each task's name, with a monotonically increasing local generation.
     *
     * @param generation the record's generation
     * @param runs the runs, by story
     * @param ideas the ideas saved or being saved, by {@link HandedIdeas#key}
     * @param results the stages' results, by offer
     * @param followed the offers whose tasks' reports are read, by offer
     * @param reports the last report taken, by the task's container name
     */
    public record Snapshot(
            long generation,
            Map<String, Run> runs,
            Map<String, HandedIdeas.Handed> ideas,
            Map<String, HandedResults.Handed> results,
            Map<String, ReportTaking.Followed> followed,
            Map<String, ReportTaking.Mark> reports) {
        /** Prevents actions from mutating an already published generation. */
        public Snapshot {
            if (generation < 0) {
                throw WorkRefusal.invalidSettings();
            }
            runs = Map.copyOf(runs);
            ideas = Map.copyOf(ideas);
            results = Map.copyOf(results);
            followed = Map.copyOf(followed);
            reports = Map.copyOf(reports);
        }

        /** A snapshot that follows no offer's reports and has taken none. */
        public Snapshot(
                final long generation,
                final Map<String, Run> runs,
                final Map<String, HandedIdeas.Handed> ideas,
                final Map<String, HandedResults.Handed> results) {
            this(generation, runs, ideas, results, Map.of(), Map.of());
        }

        /**
         * This record at another generation with other runs, all else kept.
         *
         * @param generation the generation
         * @param runs the runs
         * @return the record
         */
        public Snapshot withRuns(final long generation, final Map<String, Run> runs) {
            return new Snapshot(generation, runs, ideas, results, followed, reports);
        }

        /**
         * This record at another generation with other ideas, all else kept.
         *
         * @param generation the generation
         * @param ideas the ideas
         * @return the record
         */
        public Snapshot withIdeas(
                final long generation, final Map<String, HandedIdeas.Handed> ideas) {
            return new Snapshot(generation, runs, ideas, results, followed, reports);
        }

        /**
         * This record at another generation with other results, all else kept.
         *
         * @param generation the generation
         * @param results the results
         * @return the record
         */
        public Snapshot withResults(
                final long generation, final Map<String, HandedResults.Handed> results) {
            return new Snapshot(generation, runs, ideas, results, followed, reports);
        }

        /**
         * This record at another generation with other followed offers and report marks.
         *
         * @param generation the generation
         * @param followed the followed offers
         * @param reports the report marks
         * @return the record
         */
        public Snapshot withReading(
                final long generation,
                final Map<String, ReportTaking.Followed> followed,
                final Map<String, ReportTaking.Mark> reports) {
            return new Snapshot(generation, runs, ideas, results, followed, reports);
        }

        /**
         * This record at another generation, all else kept.
         *
         * @param generation the generation
         * @return the record
         */
        public Snapshot withGeneration(final long generation) {
            return new Snapshot(generation, runs, ideas, results, followed, reports);
        }

        /** A snapshot of runs and handed-in ideas without a handed-in result. */
        public Snapshot(
                final long generation,
                final Map<String, Run> runs,
                final Map<String, HandedIdeas.Handed> ideas) {
            this(generation, runs, ideas, Map.of());
        }

        /** A snapshot of runs without a handed-in idea or result. */
        public Snapshot(final long generation, final Map<String, Run> runs) {
            this(generation, runs, Map.of());
        }
    }

    /** The window's lifecycle reports. */
    public enum Event {
        /** The window successfully started the offered task. */
        STARTED,
        /** Porelo supplied the agent's end record. */
        ENDED,
        /** The window stopped the bound task. */
        STOPPED,
        /** A build's work left the gate after its end, where Porelo reports it landed. */
        LANDED,
        /** Porelo refused the window's Start of the offered task, so no task was started. */
        REFUSED
    }

    /**
     * What Porelo refused a Start with, as the window reports it: Porelo's own name for the
     * refusal, its parameters and its words. The planner keeps every well-shaped name as given and
     * judges none.
     *
     * @param name the refusal's name, 1 to 64 letters and digits
     * @param parameters the refusal's parameters, at most 16, each key 1 to 64 characters and each
     *     value at most 500
     * @param detail Porelo's words, at most 1000 characters, line breaks allowed
     */
    public record StartRefusal(String name, Map<String, String> parameters, String detail) {
        /** Keeps the parameters in the order they were reported, immutable. */
        public StartRefusal {
            parameters = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(parameters));
        }
    }

    /**
     * Porelo's end record, kept unmapped at the boundary; {@code landed} is where the task's work
     * landed as Porelo reports it, empty while that work still waits at the gate, and {@code
     * outcome} the outcome the task declared, null when it declared none.
     */
    public record Ended(
            String at,
            boolean finished,
            String why,
            String answer,
            int unpushed,
            List<NextStep.Landing> landed,
            WorkEnds.@Nullable Declared outcome) {

        /**
         * Keeps the landings as they were reported.
         *
         * @param at when the task ended
         * @param finished whether the agent's run finished
         * @param why why it ended
         * @param answer the agent's last message
         * @param unpushed how many commits it left unpushed
         * @param landed where its work landed
         * @param outcome the outcome it declared, or null for none
         */
        // Declared in full, since a compact constructor's parameters carry no nullness that the
        // app's NullAway reads from the compiled class.
        public Ended(
                final String at,
                final boolean finished,
                final String why,
                final String answer,
                final int unpushed,
                final List<NextStep.Landing> landed,
                final WorkEnds.@Nullable Declared outcome) {
            this.at = at;
            this.finished = finished;
            this.why = why;
            this.answer = answer;
            this.unpushed = unpushed;
            this.landed = List.copyOf(landed);
            this.outcome = outcome;
        }

        /** An end that declared no outcome. */
        public Ended(
                final String at,
                final boolean finished,
                final String why,
                final String answer,
                final int unpushed,
                final List<NextStep.Landing> landed) {
            this(at, finished, why, answer, unpushed, landed, null);
        }

        /** An end whose work has not landed yet. */
        public Ended(
                final String at,
                final boolean finished,
                final String why,
                final String answer,
                final int unpushed) {
            this(at, finished, why, answer, unpushed, List.of());
        }
    }

    /**
     * A report carries no ownership or state; both come from the saved offer. Only ENDED carries
     * {@code ended}, only LANDED carries {@code landed}, never empty, and only REFUSED carries
     * {@code refusal} and an empty {@code container}, since Porelo started no container.
     */
    public record Report(
            String offer,
            Event event,
            String container,
            String machine,
            @Nullable Ended ended,
            List<NextStep.Landing> landed,
            @Nullable StartRefusal refusal) {

        /** Keeps the landings as they were reported. */
        public Report {
            landed = List.copyOf(landed);
        }

        /** A report that names no refusal. */
        public Report(
                final String offer,
                final Event event,
                final String container,
                final String machine,
                final @Nullable Ended ended,
                final List<NextStep.Landing> landed) {
            this(offer, event, container, machine, ended, landed, null);
        }

        /** A report that names no landing. */
        public Report(
                final String offer,
                final Event event,
                final String container,
                final String machine,
                final @Nullable Ended ended) {
            this(offer, event, container, machine, ended, List.of());
        }

        /** The report of a Start Porelo refused. */
        public static Report refused(
                final String offer, final String machine, final StartRefusal refusal) {
            return new Report(offer, Event.REFUSED, "", machine, null, List.of(), refusal);
        }
    }

    /** The immutable arguments and identity offered to the window. */
    public record Offered(String offer, WorkOrder.Task task) {}

    /** The work call saves its offers without entering work or changing the revision. */
    public record Order(String revision, List<Offered> tasks) {
        /** Keeps the returned offer set immutable. */
        public Order {
            tasks = List.copyOf(tasks);
        }
    }

    /** A report or reading choice returns the current run and only its valid follow-up offers. */
    public record Answer(String revision, Run work, List<Offered> tasks, Board board) {
        /** Keeps the returned offer set immutable. */
        public Answer {
            tasks = List.copyOf(tasks);
        }
    }

    /**
     * A save refusal kept without exception text, paths or credentials.
     *
     * @param name the refusal's name
     * @param subject what it is about: a repository, an id, or a forge's origin
     * @param pushed the repositories already pushed
     * @param right the right a forge named as missing, null for every other refusal
     * @param group the id of the refused save's command group, kept for a task's event refused
     *     before writing so the owner account can save it again; null otherwise
     */
    public record SaveFailure(
            String name,
            String subject,
            List<String> pushed,
            @Nullable String right,
            @Nullable String group) {
        /** Keeps evidence of repositories already pushed. */
        public SaveFailure {
            pushed = List.copyOf(pushed);
        }

        /**
         * A refusal that names no save's group.
         *
         * @param name the refusal's name
         * @param subject what it is about
         * @param pushed the repositories already pushed
         * @param right the right a forge named as missing, or null
         */
        public SaveFailure(
                final String name,
                final String subject,
                final List<String> pushed,
                final @Nullable String right) {
            this(name, subject, pushed, right, null);
        }

        /**
         * The same refusal, naming the save's group.
         *
         * @param id the group's id
         * @return the refusal
         */
        SaveFailure naming(final String id) {
            return new SaveFailure(name, subject, pushed, right, id);
        }

        /**
         * A refusal that names no right.
         *
         * @param name the refusal's name
         * @param subject what it is about
         * @param pushed the repositories already pushed
         */
        public SaveFailure(final String name, final String subject, final List<String> pushed) {
            this(name, subject, pushed, null, null);
        }

        /**
         * Keeps a store's refusal as it can be told again.
         *
         * @param refused what the save threw
         * @return the refusal kept
         */
        static SaveFailure of(final RuntimeException refused) {
            return new SaveFailure(
                    refused instanceof StoreException
                            ? refused.getClass().getSimpleName()
                            : "NotOffered",
                    refused instanceof StoreException failure ? failure.subject() : "",
                    refused instanceof StoreException.PartlySaved partly
                            ? partly.pushed()
                            : List.of(),
                    refused instanceof StoreException.NoForgeAccess access ? access.right() : null);
        }
    }

    /** An offer's binding, terminal disposition and replay identities. */
    public record Offer(
            String id,
            WorkOrder.Task arguments,
            NextStep.Binding binding,
            String status,
            @Nullable String task,
            @Nullable String containerId,
            @Nullable String machine,
            @Nullable String since,
            @Nullable Ended ended,
            String fingerprint,
            List<String> following,
            List<String> deferred,
            @Nullable SaveFailure refusal,
            boolean reviewAnyway,
            String reworkComment,
            NextStep.@Nullable Settings rework,
            @Nullable StartRefusal startRefusal) {
        /** Starts with no deferred operator override and no refusal of its Start. */
        public Offer(
                final String id,
                final WorkOrder.Task arguments,
                final NextStep.Binding binding,
                final String status,
                final @Nullable String task,
                final @Nullable String containerId,
                final @Nullable String machine,
                final @Nullable String since,
                final @Nullable Ended ended,
                final String fingerprint,
                final List<String> following,
                final List<String> deferred,
                final @Nullable SaveFailure refusal) {
            this(
                    id,
                    arguments,
                    binding,
                    status,
                    task,
                    containerId,
                    machine,
                    since,
                    ended,
                    fingerprint,
                    following,
                    deferred,
                    refusal,
                    false,
                    "",
                    null,
                    null);
        }

        /** An offer no Start of was refused. */
        public Offer(
                final String id,
                final WorkOrder.Task arguments,
                final NextStep.Binding binding,
                final String status,
                final @Nullable String task,
                final @Nullable String containerId,
                final @Nullable String machine,
                final @Nullable String since,
                final @Nullable Ended ended,
                final String fingerprint,
                final List<String> following,
                final List<String> deferred,
                final @Nullable SaveFailure refusal,
                final boolean reviewAnyway,
                final String reworkComment,
                final NextStep.@Nullable Settings rework) {
            this(
                    id,
                    arguments,
                    binding,
                    status,
                    task,
                    containerId,
                    machine,
                    since,
                    ended,
                    fingerprint,
                    following,
                    deferred,
                    refusal,
                    reviewAnyway,
                    reworkComment,
                    rework,
                    null);
        }

        /** Keeps replay dispositions and deferred ownership immutable. */
        public Offer {
            following = List.copyOf(following);
            deferred = List.copyOf(deferred);
        }
    }

    /**
     * The save a run's pending mark names: only that save clears the mark, and the repair at open
     * halts a mark whose save did not end.
     *
     * @param id the save's own id; empty for a mark written before saves were named
     * @param items the issues and stories the save writes
     */
    public record PendingSave(String id, List<String> items) {
        /** Copies the items. */
        public PendingSave {
            items = List.copyOf(items);
        }

        /**
         * Checks a mark as the record keeps it: a save's id, or empty only for a mark written
         * before saves were named, which names no items.
         *
         * @param mark the mark
         * @throws IllegalArgumentException when a part fails its check
         */
        public static void checked(final PendingSave mark) {
            final String id = Objects.requireNonNull(mark.id(), "a mark names its save");
            if (id.isEmpty()
                    ? !mark.items().isEmpty()
                    : !UUID.fromString(id).toString().equals(id)) {
                throw new IllegalArgumentException("a mark fails its check");
            }
            // An issue's id takes a story's too, ST and a number, and a tracker reference.
            mark.items().forEach(Ids::issue);
        }

        /** The mark of one save of the given writes, under its command group's id. */
        static PendingSave of(final String id, final Writes writes) {
            final List<String> items = new ArrayList<>(writes.issues().keySet());
            items.addAll(writes.stories().keySet());
            return new PendingSave(id, items);
        }
    }

    /**
     * One story's accepted order, task bindings and retained reading state, where each issue's work
     * waits, by the issue's id, and the offers a read of a kept place or a freed issue's made, by
     * their ids: those follow no end, so no reply to a task's event names them. It also names the
     * issues of the story its Start held back from the building work by a blocker or a wait not
     * done, each until its own building work is offered.
     */
    public record Run(
            String id,
            String story,
            String to,
            String mode,
            String comment,
            String coordinator,
            int readingRounds,
            List<WorkProposal.IssueRounds> issueReadingRounds,
            List<WorkProposal.Repository> repositories,
            String since,
            List<Offer> offers,
            Map<String, NextStep.Progress> issues,
            boolean stopped,
            boolean halted,
            @Nullable PendingSave pending,
            Map<String, IssuePlace> places,
            List<String> placeOffers,
            List<String> heldBack) {
        /** Makes each published run a snapshot rather than a shared mutable object. */
        public Run {
            issueReadingRounds = List.copyOf(issueReadingRounds);
            repositories = List.copyOf(repositories);
            offers = List.copyOf(offers);
            issues = Map.copyOf(issues);
            places = Map.copyOf(places);
            placeOffers = List.copyOf(placeOffers);
            heldBack = List.copyOf(heldBack);
        }

        /** A run that holds no issue back. */
        public Run(
                final String id,
                final String story,
                final String to,
                final String mode,
                final String comment,
                final String coordinator,
                final int readingRounds,
                final List<WorkProposal.IssueRounds> issueReadingRounds,
                final List<WorkProposal.Repository> repositories,
                final String since,
                final List<Offer> offers,
                final Map<String, NextStep.Progress> issues,
                final boolean stopped,
                final boolean halted,
                final @Nullable PendingSave pending,
                final Map<String, IssuePlace> places,
                final List<String> placeOffers) {
            this(
                    id,
                    story,
                    to,
                    mode,
                    comment,
                    coordinator,
                    readingRounds,
                    issueReadingRounds,
                    repositories,
                    since,
                    offers,
                    issues,
                    stopped,
                    halted,
                    pending,
                    places,
                    placeOffers,
                    List.of());
        }

        /** A run no read of a kept place offered anything in. */
        public Run(
                final String id,
                final String story,
                final String to,
                final String mode,
                final String comment,
                final String coordinator,
                final int readingRounds,
                final List<WorkProposal.IssueRounds> issueReadingRounds,
                final List<WorkProposal.Repository> repositories,
                final String since,
                final List<Offer> offers,
                final Map<String, NextStep.Progress> issues,
                final boolean stopped,
                final boolean halted,
                final @Nullable PendingSave pending,
                final Map<String, IssuePlace> places) {
            this(
                    id,
                    story,
                    to,
                    mode,
                    comment,
                    coordinator,
                    readingRounds,
                    issueReadingRounds,
                    repositories,
                    since,
                    offers,
                    issues,
                    stopped,
                    halted,
                    pending,
                    places,
                    List.of());
        }

        /** A run keeping no issue's place. */
        public Run(
                final String id,
                final String story,
                final String to,
                final String mode,
                final String comment,
                final String coordinator,
                final int readingRounds,
                final List<WorkProposal.IssueRounds> issueReadingRounds,
                final List<WorkProposal.Repository> repositories,
                final String since,
                final List<Offer> offers,
                final Map<String, NextStep.Progress> issues,
                final boolean stopped,
                final boolean halted,
                final @Nullable PendingSave pending) {
            this(
                    id,
                    story,
                    to,
                    mode,
                    comment,
                    coordinator,
                    readingRounds,
                    issueReadingRounds,
                    repositories,
                    since,
                    offers,
                    issues,
                    stopped,
                    halted,
                    pending,
                    Map.of(),
                    List.of());
        }
    }

    /**
     * What a Start names of the run standing: the run whose unstarted offers it replaces, and the
     * reason of one issue it answers with a new run.
     *
     * @param replaces the run whose offers the Start replaces, or null for none
     * @param passOver the reason passed over, or null for none
     */
    public record Start(@Nullable String replaces, @Nullable PassOver passOver) {
        /** A Start naming no run. */
        public static final Start NONE = new Start(null, null);
    }

    /**
     * One issue's reason a Start answers with a new run in place of the run named, as the person
     * saw it.
     *
     * @param issue the issue
     * @param reason its reason
     * @param run the run the person saw
     */
    public record PassOver(String issue, String reason, String run) {}

    /**
     * The reasons a Start from a raised row may pass over; a blocked task said it cannot go on
     * unless something changes, so BLOCKED is none of them.
     */
    public static final Set<String> PASSABLE =
            Set.of("NEXT_STEP_UNAVAILABLE", "SAVE_REFUSED", "STOPPED", "FAILED");

    /**
     * The reason an issue asks the person when what was read at its kept place, or a later author's
     * end, names another task or place than the one kept; nothing kept changes and nothing moves.
     */
    public static final String PLACE_CHANGED = "PLACE_CHANGED";

    /** Returns a proposal using recorded activity and refusing lost runs. */
    public WorkProposal propose(final String story, final String to) {
        return propose(story, to, null);
    }

    /**
     * Returns a proposal for the story's issues {@code issues}, or its own when null, using
     * recorded activity and refusing lost runs; a halted run is proposed for, since only its own
     * pass-over may start it again, which the order checks.
     */
    public WorkProposal propose(
            final String story, final String to, final @Nullable List<String> issues) {
        Ids.story(story);
        return record.locked(
                session -> {
                    final Snapshot snapshot = read(session);
                    // A proposal judged on a run whose save is in flight would be tentative.
                    refuseInFlight(snapshot, Set.of(), Set.of(story), null);
                    final Board board = store.board();
                    allowed(board, snapshot, story, to, true);
                    return WorkProposal.propose(
                            board,
                            story,
                            to,
                            running(snapshot, raised(snapshot, story, null)),
                            issues);
                });
    }

    /** Orders work naming no run, retaining completed readings and their allowances. */
    public Order order(final String storyId, final String to, final WorkOrder.Decision decision) {
        return order(storyId, to, decision, Start.NONE);
    }

    /**
     * Replaces the story's run with a new one, retaining completed readings and their allowances.
     *
     * @param storyId the story
     * @param to the working state
     * @param decision the person's choices
     * @param start the run it replaces and the reason it passes over, as the person saw them
     * @return the offers the window starts
     * @throws WorkRefusal {@code RunChanged} when the run or reason named is not the one standing,
     *     or when unstarted offers stand and the Start does not name their run
     */
    public Order order(
            final String storyId,
            final String to,
            final WorkOrder.Decision decision,
            final Start start) {
        Ids.story(storyId);
        final PassOver passOver = start.passOver();
        if (passOver != null) {
            Ids.issue(passOver.issue());
            if (!PASSABLE.contains(passOver.reason())) {
                throw WorkRefusal.invalidSettings("A Start passes over no such reason.");
            }
        }
        return record.locked(
                session -> {
                    final Snapshot snapshot = read(session);
                    refuseInFlight(snapshot, Set.of(), Set.of(storyId), null);
                    final Board board = store.board();
                    Edits.unchanged(
                            board,
                            decision.seen(),
                            passOver == null ? Set.of(storyId) : Set.of(storyId, passOver.issue()));
                    final Run earlier = snapshot.runs().get(storyId);
                    standing(storyId, earlier, start);
                    passedOver(storyId, earlier, passOver);
                    unpushedLeft(board, storyId, to, earlier, passOver);
                    final WorkProposal.RunningWork again =
                            running(
                                    snapshot,
                                    passOver == null
                                            ? Set.of()
                                            : raised(snapshot, storyId, passOver));
                    allowed(
                            board,
                            snapshot,
                            storyId,
                            to,
                            passOver != null && passOver.reason().equals("SAVE_REFUSED"));
                    if (earlier != null
                            && !earlier.stopped()
                            && !earlier.halted()
                            && followUpWaits(earlier)) {
                        throw WorkRefusal.notAllowed(
                                WorkProposal.story(board, storyId).state(),
                                to,
                                "Work an earlier end offered waits to start.");
                    }
                    replaced(storyId, earlier, start.replaces());
                    final WorkOrder order = WorkOrder.accept(board, storyId, to, decision, again);
                    final WorkProposal proposal =
                            WorkProposal.propose(board, storyId, to, again, decision.issues());
                    waitsNow(board, storyId, to, proposal);
                    final WorkRule rule =
                            WorkProposal.rule(
                                    board,
                                    WorkProposal.story(board, storyId),
                                    to,
                                    decision.issues());
                    final Run old = snapshot.runs().get(storyId);
                    final int rounds =
                            decision.readingRounds() == null
                                    ? 2
                                    : decision.readingRounds().intValue();
                    final List<WorkProposal.IssueRounds> overrides =
                            decision.issueReadingRounds() == null
                                    ? List.of()
                                    : decision.issueReadingRounds().stream()
                                            .map(
                                                    l ->
                                                            new WorkProposal.IssueRounds(
                                                                    l.issue(),
                                                                    l.rounds().intValue()))
                                            .toList();
                    final Map<String, NextStep.Progress> progress =
                            new LinkedHashMap<>(old == null ? Map.of() : old.issues());
                    final List<Offer> offers = new ArrayList<>();
                    if (rule.blocked() != null) {
                        for (final WorkOrder.Task task : order.tasks()) {
                            for (final String id : task.issues()) {
                                final int allowance =
                                        overrides.stream()
                                                .filter(l -> l.issue().equals(id))
                                                .mapToInt(WorkProposal.IssueRounds::rounds)
                                                .findFirst()
                                                .orElse(rounds);
                                final NextStep.Progress previous = progress.get(id);
                                progress.put(
                                        id,
                                        previous == null
                                                ? NextStep.Progress.start(allowance)
                                                : new NextStep.Progress(
                                                        new ReadingCycle.Rounds(
                                                                previous.rounds().rounds(),
                                                                previous.rounds().roundsMost(),
                                                                false),
                                                        previous.findings(),
                                                        false,
                                                        "NONE",
                                                        previous.answer()));
                                carriedBuild(old, id)
                                        .filter(carried -> !offers.contains(carried))
                                        .ifPresent(offers::add);
                                offers.add(
                                        offer(
                                                handReader(
                                                        board,
                                                        storyId,
                                                        to,
                                                        rule,
                                                        old,
                                                        id,
                                                        new NextStep.Settings(
                                                                task.mode(),
                                                                task.agent(),
                                                                task.model()),
                                                        task.effort(),
                                                        decision.comment() == null
                                                                ? ""
                                                                : decision.comment()),
                                                storyId));
                            }
                        }
                    }
                    for (final WorkOrder.Task task :
                            rule.blocked() == null ? order.tasks() : List.<WorkOrder.Task>of()) {
                        final List<String> owned =
                                task.issues().stream()
                                        .filter(
                                                id ->
                                                        task.repository()
                                                                .equals(
                                                                        board.issue(id)
                                                                                .orElseThrow()
                                                                                .repository()))
                                        .filter(
                                                id ->
                                                        !task.lead()
                                                                || order.tasks().stream()
                                                                        .noneMatch(
                                                                                other ->
                                                                                        !other
                                                                                                        .lead()
                                                                                                && other.issues()
                                                                                                        .contains(
                                                                                                                id)))
                                        .toList();
                        if (owned.isEmpty() && !task.issues().isEmpty()) {
                            continue;
                        }
                        for (final String id : owned) {
                            final int allowance =
                                    overrides.stream()
                                            .filter(l -> l.issue().equals(id))
                                            .mapToInt(WorkProposal.IssueRounds::rounds)
                                            .findFirst()
                                            .orElse(rounds);
                            final NextStep.Progress previous = progress.get(id);
                            progress.put(
                                    id,
                                    previous == null
                                            ? NextStep.Progress.start(allowance)
                                            : new NextStep.Progress(
                                                    new ReadingCycle.Rounds(
                                                            previous.rounds().rounds(),
                                                            previous.rounds().roundsMost(),
                                                            false),
                                                    previous.findings(),
                                                    false,
                                                    "NONE",
                                                    previous.answer()));
                        }
                        final String helpers =
                                String.join(
                                        "\n",
                                        order.tasks().stream()
                                                .filter(
                                                        t ->
                                                                !t.lead()
                                                                        && t.repository()
                                                                                .equals(
                                                                                        task
                                                                                                .repository()))
                                                .flatMap(
                                                        t ->
                                                                t.issues().stream()
                                                                        .map(
                                                                                id ->
                                                                                        t
                                                                                                        .repository()
                                                                                                + "/"
                                                                                                + id
                                                                                                + "/helper"))
                                                .toList());
                        final String prompt =
                                WorkOrder.prompt(
                                        rule,
                                        board.story(storyId).orElseThrow(),
                                        WorkOrder.issueText(
                                                board,
                                                board.story(storyId).orElseThrow(),
                                                to,
                                                owned),
                                        task.lead() ? helpers : "",
                                        NextStep.fence(
                                                decision.comment() == null
                                                        ? ""
                                                        : decision.comment()));
                        if (prompt.getBytes(StandardCharsets.UTF_8).length > WorkEnds.MOST_ANSWER) {
                            throw WorkRefusal.notAllowed(
                                    board.story(storyId).orElseThrow().state(),
                                    to,
                                    "A task prompt exceeds 65536 UTF-8 bytes.");
                        }
                        final WorkOrder.Task scoped =
                                StageOrder.staged(
                                        board,
                                        rule,
                                        to,
                                        new WorkOrder.Task(
                                                        task.repository(),
                                                        owned,
                                                        task.role(),
                                                        task.agent(),
                                                        task.model(),
                                                        task.effort(),
                                                        task.mode(),
                                                        prompt,
                                                        task.readLanded(),
                                                        task.basis(),
                                                        task.used())
                                                .standing(task.lead(), task.again(), task.place()));
                        offers.add(
                                offer(
                                        new NextStep.Planned(scoped, to, rule, task.basis()),
                                        storyId));
                    }
                    final List<String> covered =
                            offers.stream().flatMap(o -> o.binding().issues().stream()).toList();
                    if (order.tasks().stream()
                            .flatMap(t -> t.issues().stream())
                            .anyMatch(id -> !covered.contains(id))) {
                        throw WorkRefusal.notAllowed(
                                board.story(storyId).orElseThrow().state(),
                                to,
                                "Every issue needs a participant in its own repository.");
                    }
                    // An issue its author builds afresh, at no place, as from Backlog after a hand
                    // move, keeps no place of the old run; a stop that place made cannot stand,
                    // since a Start is refused while one does and none can be passed over.
                    final Set<String> fresh = new HashSet<>();
                    // A build carried void from the old run for its readers is no new build.
                    final Set<String> earlierOffers = new HashSet<>();
                    if (old != null) {
                        old.offers().forEach(o -> earlierOffers.add(o.id()));
                    }
                    offers.stream()
                            .filter(
                                    o ->
                                            author(o)
                                                    && o.arguments().place() == null
                                                    && !earlierOffers.contains(o.id()))
                            .forEach(o -> fresh.addAll(o.binding().issues()));
                    final Map<String, IssuePlace> places = carriedPlaces(old, offers, fresh);
                    final List<String> heldBack =
                            decision.issues() == null
                                    ? heldBack(board, to, proposal)
                                    : stillHeld(earlier, to, decision.issues());
                    final Run run =
                            new Run(
                                    UUID.randomUUID().toString(),
                                    storyId,
                                    to,
                                    decision.mode(),
                                    decision.comment() == null ? "" : decision.comment(),
                                    proposal.coordinator(),
                                    rounds,
                                    overrides,
                                    // As accepted: the person's choices, the rule's reasons.
                                    order.repositories(),
                                    clock.instant().toString(),
                                    offers,
                                    progress,
                                    false,
                                    false,
                                    null,
                                    places,
                                    List.of(),
                                    heldBack);
                    final Snapshot next = replace(snapshot, run);
                    session.preflight(next);
                    session.write(next);
                    return new Order(
                            board.revision().value(),
                            offered(
                                    run,
                                    offers.stream()
                                            .filter(o -> o.status().equals("OFFERED"))
                                            .map(Offer::id)
                                            .toList()));
                });
    }

    /**
     * Accepts one checked report, replaying an identical report without another save or judgment.
     */
    public Answer report(final String task, final Report report) {
        checkedReport(task, report);
        if (report.event() == Event.LANDED) {
            return landed(task, report);
        }
        boolean retried = false;
        @Nullable String waited = null;
        while (true) {
            final Step step = record.locked(session -> reportStep(session, task, report));
            switch (step) {
                case Answered answered -> {
                    return answered.answer();
                }
                case Waiting wait -> {
                    waitFor(wait.story(), wait.id(), waited);
                    waited = wait.id();
                }
                case Flying flying -> {
                    try {
                        return answer(land(flying.flight()), flying.following());
                    } catch (final StoreException.Changed moved) {
                        // Nothing was written, and the event stands with the refusal; the store
                        // holds the items named read anew, so the event is judged once more on
                        // them by the next step, as its identical resend would be. A store that
                        // lost its board would read it whole there, so it is refused.
                        if (retried || !flying.retriable() || store.held().isEmpty()) {
                            throw moved;
                        }
                        retried = true;
                    }
                }
            }
        }
    }

    /**
     * What one step of a report under the record's lock leads to: an answer, a save whose commands
     * are written once the lock is let go, or a wait for the story's save in flight.
     */
    private sealed interface Step permits Answered, Flying, Waiting {}

    /**
     * A report answered under the lock.
     *
     * @param answer the answer
     */
    private record Answered(Answer answer) implements Step {}

    /**
     * A report whose save flies.
     *
     * @param flight the save
     * @param following the offers that follow it
     * @param retriable whether a {@code Changed} refusal is judged once more
     */
    private record Flying(Flight flight, List<String> following, boolean retriable)
            implements Step {}

    /**
     * A report that met its story's save in flight.
     *
     * @param story the story
     * @param id the id of the save in flight
     */
    private record Waiting(String story, String id) implements Step {}

    /** Plans a report as one step under the record's lock. */
    private Step reportStep(final Session session, final String task, final Report report) {
        final Snapshot snapshot = read(session);
        final Run run =
                snapshot.runs().values().stream()
                        .filter(
                                r ->
                                        r.offers().stream()
                                                .anyMatch(o -> o.id().equals(report.offer())))
                        .findFirst()
                        .orElseThrow(() -> WorkRefusal.taskNotAnswered(task, report.offer()));
        final PendingSave inFlight = run.pending();
        if (inFlight != null) {
            // The run stands tentative while its save is in flight: nothing is judged or
            // answered from it until that save is settled.
            return new Waiting(run.story(), inFlight.id());
        }
        final Offer known =
                run.offers().stream()
                        .filter(o -> o.id().equals(report.offer()))
                        .findFirst()
                        .orElseThrow();
        if (known.status().equals("VOID")) {
            throw WorkRefusal.taskNotAnswered(task, report.offer());
        }
        if (report.event() == Event.REFUSED) {
            if (known.status().equals("STARTED")) {
                throw conflict(task, report.event(), "Porelo started the offered task.");
            }
            if (!known.status().equals("OFFERED") || known.startRefusal() != null) {
                // An offer that ended or was refused already keeps what it has; the report is a
                // resend, answered as one.
                return new Answered(answer(run, known.following()));
            }
            final Board board = store.board();
            if (!NextStep.owns(board, known.binding()) || asksAlready(run, known)) {
                // An offer whose issues were moved away by hand, or whose issues all ask the
                // person already, keeps nothing of the refusal: the report is answered as a
                // resend, so nothing is raised that no Start of it could clear or that hides the
                // refusal which made the issue ask.
                return new Answered(answer(run, known.following()));
            }
            final Judged judged = judge(snapshot, run, known, task, report, board);
            return step(saved(session, snapshot, judged, board), judged.following(), true);
        }
        if (known.task() != null
                && (!task.equals(known.task())
                        || !report.container().equals(known.containerId())
                        || known.machine() != null && !report.machine().equals(known.machine()))) {
            throw conflict(task, report.event(), "The report contradicts the task binding.");
        }
        if (report.event() == Event.STARTED && known.task() != null) {
            // Only the start itself is judged again; a start resent after the task's end is
            // answered with what stands, so the end is never undone.
            if (known.status().equals("STARTED") && unsaved(run, known)) {
                return again(session, snapshot, run, known, task, report);
            }
            replayFailure(known);
            final String waits = waitsForAgain(run, known, task);
            if (waits != null) {
                // The person started the author's task again by hand at the place its work waits
                // at: the run works there, its end stands.
                return runAgain(session, snapshot, run, known, waits, clock.instant().toString());
            }
            return new Answered(answer(run, known.following()));
        }
        if (report.event() != Event.STARTED && !known.status().equals("STARTED")) {
            final boolean same =
                    known.status().equals(report.event().name())
                            && known.fingerprint().equals(fingerprint(report));
            if (same && unsaved(run, known)) {
                return again(session, snapshot, run, known, task, report);
            }
            if (same) {
                // The author's own end resent, never the end of a run started by hand since.
                replayFailure(known);
                return new Answered(answer(run, known.following()));
            }
            final String rerun = runningAgain(run, known);
            if (rerun != null) {
                // A run started by hand ends whatever it pushed; the place reads that push.
                return runAgain(session, snapshot, run, known, rerun, null);
            }
            throw conflict(
                    task, report.event(), "The task has not started or already ended differently.");
        }
        if (report.event() == Event.STARTED && (run.stopped() || run.halted())) {
            throw WorkRefusal.taskNotAnswered(task, report.offer());
        }
        if (report.event() == Event.STARTED && authorsOwn(run, known, task)) {
            throw conflict(
                    task,
                    report.event(),
                    "A task that reads another's work starts under a task of its own, never the"
                            + " one whose work it reads.");
        }
        final Board board = store.board();
        final Judged judged = judge(snapshot, run, known, task, report, board);
        return step(saved(session, snapshot, judged, board), judged.following(), true);
    }

    /**
     * The issue whose work waits at a place kept for the task given, whose last author run is the
     * ended offer given, and which no run started by hand works at yet; null when there is none or
     * the run is stopped or halted.
     */
    private @Nullable String waitsForAgain(final Run run, final Offer known, final String task) {
        if (run.stopped() || run.halted()) {
            return null;
        }
        final List<String> issues =
                run.places().entrySet().stream()
                        .filter(
                                e ->
                                        e.getValue().again() == null
                                                && kept(e.getValue(), known, task))
                        .map(Map.Entry::getKey)
                        .toList();
        if (issues.isEmpty()) {
            return null;
        }
        final Board board = store.board();
        return issues.stream().filter(i -> stillWaits(board, run, i)).findFirst().orElse(null);
    }

    /** The issue whose kept place a run started by hand in the ended offer's task works at. */
    private static @Nullable String runningAgain(final Run run, final Offer known) {
        final String task = known.task();
        return task == null
                ? null
                : run.places().entrySet().stream()
                        .filter(
                                e ->
                                        e.getValue().again() != null
                                                && kept(e.getValue(), known, task))
                        .map(Map.Entry::getKey)
                        .findFirst()
                        .orElse(null);
    }

    /** Whether a place is kept for the task's author whose last run there is the offer given. */
    private static boolean kept(final IssuePlace place, final Offer offer, final String task) {
        return !place.foreign()
                && place.task().equals(task)
                && place.authorRuns().getLast().equals(offer.id());
    }

    /**
     * Records that a run started by hand works at an issue's kept place since the time given, or
     * that it ended, and answers what stands of the offer.
     */
    private Step runAgain(
            final Session session,
            final Snapshot snapshot,
            final Run run,
            final Offer known,
            final String issue,
            final @Nullable String since) {
        final Draft draft = new Draft(run);
        final IssuePlace kept = Objects.requireNonNull(run.places().get(issue), "a kept place");
        draft.places.put(issue, kept.runAgain(since));
        final Run next = draft.freeze();
        session.write(replace(snapshot, next));
        return new Answered(answer(next, known.following()));
    }

    /** The step a planned save leads to. */
    private Step step(final Planned planned, final List<String> following, final boolean retry) {
        return switch (planned) {
            case Written written -> new Answered(answer(written.run(), following));
            case Flight flight -> new Flying(flight, following, retry);
        };
    }

    /**
     * Waits for the story's save in flight to be settled, at most the record's bound; refused as
     * busy when it is not, when it stands still after a wait, or when another report of the story
     * already waits.
     */
    private void waitFor(final String story, final String id, final @Nullable String waited) {
        if (id.equals(waited) || !waiting.add(story)) {
            throw new StoreException.Busy("");
        }
        try {
            if (!record.awaitEnded(id)) {
                throw new StoreException.Busy("");
            }
        } finally {
            waiting.remove(story);
        }
    }

    /** The store's refusals that come before it writes anything. */
    private static final Set<String> BEFORE_WRITING = StoreException.BEFORE_WRITING;

    /**
     * Whether an offer's event stands recorded with a refusal the store gave before it wrote
     * anything, so the task's next identical report saves what its judgment planned once more.
     */
    private static boolean unsaved(final Run run, final Offer known) {
        final SaveFailure refusal = known.refusal();
        return !run.halted() && refusal != null && BEFORE_WRITING.contains(refusal.name());
    }

    @Override
    public boolean resavable(final String story, final String group) {
        Ids.story(story);
        final Run run = record.latest().runs().get(story);
        return run != null && run.pending() == null && kept(run, group) != null;
    }

    @Override
    public Board resave(final String story, final String group) {
        return resave(story, group, id -> true);
    }

    @Override
    public Board resave(final String story, final String group, final Predicate<String> stands) {
        Ids.story(story);
        @Nullable String waited = null;
        while (true) {
            // The kept event is found by its group anew at each step, so one a repeated report or
            // an earlier resave saved meanwhile is refused, never saved a second time.
            final Step step =
                    record.locked(
                            session -> {
                                final Run run = read(session).runs().get(story);
                                if (run == null) {
                                    throw WorkRefusal.noSuchStory(story);
                                }
                                final PendingSave inFlight = run.pending();
                                if (inFlight != null) {
                                    return new Waiting(story, inFlight.id());
                                }
                                final KeptEvent kept = kept(run, group);
                                if (kept == null) {
                                    throw WorkRefusal.runChanged(
                                            story,
                                            "The run keeps no event refused before writing under"
                                                    + " this save.");
                                }
                                return reportStep(session, kept.task(), kept.report());
                            });
            switch (step) {
                case Answered answered -> {
                    return store.board();
                }
                case Waiting wait -> {
                    waitFor(wait.story(), wait.id(), waited);
                    waited = wait.id();
                }
                case Flying flying -> {
                    try {
                        land(flying.flight());
                    } catch (final RuntimeException refused) {
                        throw settledAfter(
                                refused,
                                () ->
                                        keptUnder(
                                                story,
                                                flying.flight().group().id(),
                                                group,
                                                stands));
                    }
                    return store.board();
                }
            }
        }
    }

    /**
     * Names a kept event's refusal under the group it was resaved from again, when its resave's
     * group was refused before writing but the outbox keeps no such group, so the owner account can
     * send it again from the group that still stands.
     */
    private void keptUnder(
            final String story,
            final String resaved,
            final String group,
            final Predicate<String> stands) {
        if (stands.test(resaved)) {
            return;
        }
        record.locked(
                session -> {
                    final Snapshot snapshot = read(session);
                    final Run run = snapshot.runs().get(story);
                    if (run == null || run.pending() != null) {
                        return false;
                    }
                    final Draft draft = new Draft(run);
                    boolean named = false;
                    for (final Offer offer : run.offers()) {
                        final SaveFailure refusal = offer.refusal();
                        if (refusal != null
                                && resaved.equals(refusal.group())
                                && unsaved(run, offer)) {
                            draft.set(refused(offer, refusal.naming(group)));
                            named = true;
                        }
                    }
                    if (named) {
                        session.write(replace(snapshot, draft.freeze()));
                    }
                    return named;
                });
    }

    /** An offer with its kept refusal replaced. */
    private static Offer refused(final Offer o, final SaveFailure refusal) {
        return new Offer(
                o.id(),
                o.arguments(),
                o.binding(),
                o.status(),
                o.task(),
                o.containerId(),
                o.machine(),
                o.since(),
                o.ended(),
                o.fingerprint(),
                o.following(),
                o.deferred(),
                refusal,
                o.reviewAnyway(),
                o.reworkComment(),
                o.rework(),
                o.startRefusal());
    }

    /**
     * A task's event kept with a refusal the store gave before writing, as its identical report.
     *
     * @param task the task bound to the offer
     * @param report the report the event was judged on
     */
    private record KeptEvent(String task, Report report) {}

    /**
     * The event a run keeps refused before writing under the group named: found on the offer whose
     * kept refusal names the group, which the refused save's mark was named by too; null for a
     * halted run, a group no refusal names, or a refusal that may have written.
     */
    private static @Nullable KeptEvent kept(final Run run, final String group) {
        for (final Offer offer : run.offers()) {
            final SaveFailure refusal = offer.refusal();
            final String task = offer.task();
            final String container = offer.containerId();
            // As for the task's repeated report, a halted run keeps no event to save again.
            if (refusal == null
                    || !group.equals(refusal.group())
                    || !unsaved(run, offer)
                    || task == null
                    || container == null) {
                continue;
            }
            final @Nullable Event event =
                    switch (offer.status()) {
                        case "STARTED" -> Event.STARTED;
                        case "ENDED" -> Event.ENDED;
                        case "STOPPED" -> Event.STOPPED;
                        default -> null;
                    };
            if (event == null) {
                return null;
            }
            return new KeptEvent(
                    task,
                    new Report(
                            offer.id(),
                            event,
                            container,
                            Objects.requireNonNullElse(offer.machine(), "unknown"),
                            offer.ended()));
        }
        return null;
    }

    /**
     * Judges an event the store refused before writing once more, on the board read now, from the
     * offer as it stood before the event, and saves it; a state the board already holds writes
     * nothing, and a save that stands clears the refusal.
     */
    private Step again(
            final Session session,
            final Snapshot snapshot,
            final Run run,
            final Offer known,
            final String task,
            final Report report) {
        final boolean start = report.event() == Event.STARTED;
        if (start && run.stopped()) {
            throw WorkRefusal.taskNotAnswered(task, report.offer());
        }
        final Offer before =
                new Offer(
                        known.id(),
                        known.arguments(),
                        known.binding(),
                        start ? "OFFERED" : "STARTED",
                        start ? null : known.task(),
                        start ? null : known.containerId(),
                        start ? null : known.machine(),
                        known.since(),
                        null,
                        "",
                        List.of(),
                        List.of(),
                        null,
                        known.reviewAnyway(),
                        known.reworkComment(),
                        known.rework(),
                        known.startRefusal());
        final Draft draft = new Draft(run);
        draft.set(before);
        known.binding()
                .issues()
                .forEach(
                        id ->
                                draft.progress.computeIfPresent(
                                        id,
                                        (key, p) ->
                                                p.reason().equals("SAVE_REFUSED")
                                                        ? new NextStep.Progress(
                                                                p.rounds(),
                                                                p.findings(),
                                                                false,
                                                                "NONE",
                                                                p.answer())
                                                        : p));
        final Run reverted = draft.freeze();
        final Map<String, Run> runs = new LinkedHashMap<>(snapshot.runs());
        runs.put(run.story(), reverted);
        final Snapshot base = snapshot.withRuns(snapshot.generation(), runs);
        final Board board = store.board();
        final Judged judged = judge(base, reverted, before, task, report, board);
        return step(saved(session, snapshot, judged, board), judged.following(), false);
    }

    /** An event's judgment on one board: the record and run to save and the store's writes. */
    private record Judged(
            Snapshot recorded, Draft draft, Writes writes, String offer, List<String> following) {}

    /**
     * Saves a task's start or end against the versions of the items its judgment read on the board,
     * so only a change of one of them refuses it; a refusal before writing keeps the event with the
     * refusal under Needs you.
     */
    private Planned saved(
            final Session session, final Snapshot before, final Judged judged, final Board board) {
        return save(
                session,
                false,
                before,
                judged.recorded(),
                judged.draft(),
                judged.writes(),
                judged.offer(),
                board,
                null,
                Basis.of(board),
                RunReads.recheck(board, judged.draft().story));
    }

    /** Judges a task's start or end on one board, refusing an event that board no longer allows. */
    private Judged judge(
            final Snapshot snapshot,
            final Run run,
            final Offer known,
            final String task,
            final Report report,
            final Board board) {
        final Draft draft = new Draft(run);
        Snapshot recorded = snapshot;
        Writes writes = NOTHING;
        Offer accepted;
        final List<String> following = new ArrayList<>();
        final StartRefusal refused = report.refusal();
        if (refused != null) {
            return refused(snapshot, draft, known, task, refused, run);
        }
        if (report.event() == Event.STARTED) {
            if (!NextStep.owns(board, known.binding())) {
                throw conflict(task, report.event(), "The offered ownership has changed.");
            }
            for (final Run other : snapshot.runs().values()) {
                if (!other.story().equals(run.story())) {
                    final List<String> owners =
                            other.offers().stream()
                                    .filter(o -> o.status().equals("STARTED"))
                                    .filter(
                                            o ->
                                                    o.binding().issues().stream()
                                                            .anyMatch(
                                                                    known.binding().issues()
                                                                            ::contains))
                                    .map(o -> String.valueOf(o.task()))
                                    .toList();
                    if (!owners.isEmpty()) {
                        throw WorkRefusal.workRunning(other.story(), owners);
                    }
                }
            }
            if (snapshot.runs().values().stream()
                    .flatMap(r -> r.offers().stream())
                    .anyMatch(o -> task.equals(o.task()) && !resumes(run, known, task, o))) {
                throw conflict(task, report.event(), "The task is already bound.");
            }
            writes = NextStep.started(board, known.binding());
            if (known.arguments().atAnothersWork()) {
                reviewing(draft, known);
            }
            if (known.startRefusal() != null) {
                // The Start went through after Porelo refused one: nothing stands against it.
                known.binding()
                        .issues()
                        .forEach(
                                id ->
                                        draft.progress.computeIfPresent(
                                                id,
                                                (key, p) ->
                                                        p.reason().equals(START_REFUSED)
                                                                        || p.reason()
                                                                                .equals(
                                                                                        PLACE_CHANGED)
                                                                ? new NextStep.Progress(
                                                                        p.rounds(),
                                                                        p.findings(),
                                                                        false,
                                                                        "NONE",
                                                                        p.answer())
                                                                : p));
            }
            accepted =
                    new Offer(
                            known.id(),
                            known.arguments(),
                            known.binding(),
                            "STARTED",
                            task,
                            report.container(),
                            report.machine(),
                            clock.instant().toString(),
                            null,
                            "",
                            List.of(),
                            List.of(),
                            null);
        } else {
            accepted =
                    new Offer(
                            known.id(),
                            known.arguments(),
                            known.binding(),
                            report.event().name(),
                            task,
                            report.container(),
                            report.machine(),
                            known.since(),
                            report.ended(),
                            fingerprint(report),
                            List.of(),
                            List.of(),
                            null);
            draft.set(accepted);
            if (run.stopped() || run.halted() || report.event() == Event.STOPPED) {
                known.binding()
                        .issues()
                        .forEach(
                                id ->
                                        draft.progress.computeIfPresent(
                                                id,
                                                (key, value) ->
                                                        value.attention(
                                                                run.halted()
                                                                        ? "SAVE_REFUSED"
                                                                        : "STOPPED")));
            } else {
                final Ended ended = report.ended();
                if (ended == null) {
                    throw WorkRefusal.invalidSettings();
                }
                // At another task's work the place is read for what the task found there; its
                // answer holds no findings.
                final boolean reviewer = known.arguments().atAnothersWork();
                known.binding()
                        .issues()
                        .forEach(
                                id -> {
                                    final NextStep.Progress p = draft.progress.get(id);
                                    if (p != null && known.binding().role().answersWithFindings()) {
                                        draft.progress.put(
                                                id,
                                                new NextStep.Progress(
                                                        p.rounds(),
                                                        p.findings(),
                                                        p.needsYou(),
                                                        p.reason(),
                                                        ended.answer()));
                                    }
                                });
                List<String> deferred = List.of();
                if (reviewer) {
                    ended(draft, known, ended);
                } else if (staged(known.binding())) {
                    // The store moved the issues when it saved the result; without
                    // one, they stay where the stage works on them.
                    recorded = endedStage(snapshot, draft, known, task);
                } else {
                    final WorkEnds.Judgment judgment =
                            WorkEnds.judge(
                                    known.binding().reading(),
                                    WorkEnds.Outcome.of(
                                            ended.finished(), ended.unpushed(), ended.outcome()),
                                    ended.answer());
                    final boolean authored = judgment instanceof WorkEnds.Done && author(known);
                    final boolean atGate = authored && ended.landed().isEmpty();
                    // An issue whose kept push still waits takes no step from another's end,
                    // landed or not.
                    final Map<String, String> elsewhere =
                            authored ? elsewhere(draft, known, task) : Map.of();
                    final NextStep.Result next =
                            next(
                                    board,
                                    without(known.binding(), elsewhere.keySet()),
                                    judgment,
                                    draft.progress,
                                    draft.comment,
                                    new NextStep.Build(task, ended.landed()));
                    draft.progress.putAll(next.progress());
                    draft.unavailable.putAll(next.unavailable());
                    writes = next.writes();
                    deferred = next.deferred();
                    elsewhere.forEach(
                            (id, why) -> {
                                draft.progress.put(id, draft.progress(id).attention(PLACE_CHANGED));
                                draft.unavailable.put(id, why);
                            });
                    if (atGate) {
                        keepPlaces(draft, known, task, writes, deferred, elsewhere.keySet());
                    } else if (authored) {
                        // Its work landed: no place of its issues waits for it any more.
                        known.binding().issues().stream()
                                .filter(id -> !elsewhere.containsKey(id))
                                .forEach(draft.places::remove);
                    }
                    for (final NextStep.Planned planned : next.tasks()) {
                        final Offer offer = offer(planned, run.story());
                        draft.offers.add(offer);
                        following.add(offer.id());
                    }
                }
                accepted =
                        new Offer(
                                accepted.id(),
                                accepted.arguments(),
                                accepted.binding(),
                                accepted.status(),
                                accepted.task(),
                                accepted.containerId(),
                                accepted.machine(),
                                accepted.since(),
                                accepted.ended(),
                                accepted.fingerprint(),
                                following,
                                deferred,
                                null);
            }
        }
        draft.set(accepted);
        writes = reconcile(board, draft, writes, following);
        draft.setFollowing(accepted.id(), following);
        draft.compact(accepted.id());
        return new Judged(recorded, draft, writes, accepted.id(), following);
    }

    /**
     * Judges Porelo's refusal of a Start: it stands on the offer, which stays offered, and each of
     * its issues asks the person under {@link #START_REFUSED}; a rework at its kept place refused
     * as the task's push not being the place's says the place changed instead. Nothing is written
     * to the store.
     */
    private Judged refused(
            final Snapshot snapshot,
            final Draft draft,
            final Offer known,
            final String task,
            final StartRefusal refusal,
            final Run run) {
        final boolean place = refusal.name().equals("NotTheTasksPush") && atKeptPlace(run, known);
        draft.set(
                new Offer(
                        known.id(),
                        known.arguments(),
                        known.binding(),
                        known.status(),
                        known.task(),
                        known.containerId(),
                        known.machine(),
                        known.since(),
                        known.ended(),
                        known.fingerprint(),
                        known.following(),
                        known.deferred(),
                        known.refusal(),
                        known.reviewAnyway(),
                        known.reworkComment(),
                        known.rework(),
                        refusal));
        for (final String id : known.binding().issues()) {
            final NextStep.Progress before = draft.progress.get(id);
            if (before == null || before.needsYou()) {
                // The person is already asked for another reason, which stands; the refusal
                // stands on the offer.
                continue;
            }
            draft.progress.put(id, before.attention(place ? PLACE_CHANGED : START_REFUSED));
            draft.unavailable.put(
                    id,
                    "the start of task "
                            + task
                            + " was refused as "
                            + refusal.name()
                            + (place ? ", so its place changed" : ""));
        }
        return new Judged(snapshot, draft, NOTHING, known.id(), known.following());
    }

    /**
     * Four eyes: whether a task whose role reports findings, gives points or judges would start
     * under the task of the author whose work it reads: a task whose landed work it reads, the name
     * of the push it reads or the task its issue's place keeps.
     */
    private static boolean authorsOwn(final Run run, final Offer offer, final String task) {
        if (!offer.binding().role().needsOtherEyes()) {
            return false;
        }
        if (offer.arguments().readLanded().contains(task)) {
            return true;
        }
        final RunPlace place = offer.arguments().place();
        if (place != null && place.gatePush() != null && place.gatePush().name().equals(task)) {
            return true;
        }
        for (final String issue : offer.binding().issues()) {
            final IssuePlace kept = run.places().get(issue);
            if (kept != null && kept.task().equals(task)) {
                return true;
            }
        }
        return false;
    }

    /** Whether an offer has issues and each asks the person already, for whatever reason. */
    private static boolean asksAlready(final Run run, final Offer offer) {
        return !offer.binding().issues().isEmpty()
                && offer.binding().issues().stream()
                        .allMatch(
                                id -> {
                                    final NextStep.Progress progress = run.issues().get(id);
                                    return progress != null && progress.needsYou();
                                });
    }

    /**
     * Whether an offer is its author's rework at the place each of its issues keeps, as a read of a
     * kept push or a gate push's end offers it.
     */
    private static boolean atKeptPlace(final Run run, final Offer offer) {
        final RunPlace place = offer.arguments().place();
        if (!author(offer)
                || !offer.arguments().again()
                || place == null
                || offer.binding().issues().isEmpty()) {
            return false;
        }
        for (final String issue : offer.binding().issues()) {
            final IssuePlace kept = run.places().get(issue);
            if (kept == null || !kept.same(place, offer.binding().repository())) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether a start binds the name of an earlier offer because it resumes that offer's task: the
     * new offer is its author's rework at the place each of its issues keeps, under that place's
     * task, and the earlier offer is an author run used there that ended, or was carried void into
     * the run by a Start.
     */
    private static boolean resumes(
            final Run run, final Offer known, final String task, final Offer earlier) {
        final RunPlace place = known.arguments().place();
        if (!author(known)
                || !known.arguments().again()
                || place == null
                || !(earlier.status().equals("ENDED") || earlier.status().equals("VOID"))
                || !run.offers().contains(earlier)
                || known.binding().issues().isEmpty()) {
            return false;
        }
        for (final String issue : known.binding().issues()) {
            final IssuePlace kept = run.places().get(issue);
            if (kept == null
                    || !kept.task().equals(task)
                    || !kept.same(place, known.binding().repository())
                    || !kept.authorRuns().contains(earlier.id())) {
                return false;
            }
        }
        return true;
    }

    /** Whether an offer reads a build's landed work and reports findings about it. */
    private static boolean landedReader(final Offer offer) {
        return offer.binding().role().answersWithFindings();
    }

    /** Whether an offer is a reading, whose answer holds findings; a reviewer's holds none. */
    private static boolean reads(final Offer offer) {
        return offer.binding().role().answersWithFindings();
    }

    /**
     * Takes a reviewer's end: it wrote its points at its place, so a finished end moves nothing and
     * offers nothing, the next read of the place deciding; what it checked out is no lost work. An
     * end that did not finish asks the person why and leaves its head unreviewed.
     */
    private static void ended(final Draft draft, final Offer reviewer, final Ended ended) {
        final WorkEnds.Outcome outcome = WorkEnds.Outcome.of(ended.finished(), 0, ended.outcome());
        if (outcome == WorkEnds.Outcome.DONE) {
            return;
        }
        for (final String issue : reviewer.binding().issues()) {
            draft.progress.computeIfPresent(issue, (key, value) -> value.attention(outcome.name()));
            // Its head was not reviewed, so it is offered again once the person lets the issue go
            // on.
            draft.places.computeIfPresent(issue, (key, kept) -> kept.reviewing(null));
        }
    }

    /**
     * Keeps the head a started reviewer reviews at each place of its issues it reviews: the last
     * head read there.
     */
    private static void reviewing(final Draft draft, final Offer reviewer) {
        for (final String issue : reviewer.binding().issues()) {
            final IssuePlace kept = draft.places.get(issue);
            if (kept == null) {
                continue;
            }
            final IssuePlace.Review last = kept.last();
            final String head = last == null ? null : last.head();
            if (head != null && kept.place().equals(reviewer.arguments().place())) {
                draft.places.put(issue, kept.reviewing(head));
            }
        }
    }

    /** Whether an offer runs an author that pushes its work: a builder, never a stage or reader. */
    private static boolean author(final Offer offer) {
        return offer.binding().role().pushesWork();
    }

    /**
     * The issues of an author's end whose kept place is another task's or place's push that still
     * waits, each with the one line why; they keep that place and take no step from this end.
     */
    private static Map<String, String> elsewhere(
            final Draft draft, final Offer known, final String task) {
        final RunPlace own = RunPlace.gate(null, task);
        final Map<String, String> elsewhere = new LinkedHashMap<>();
        for (final String id : known.binding().issues()) {
            final IssuePlace kept = draft.places.get(id);
            if (kept != null
                    && !(kept.task().equals(task) && kept.same(own, known.binding().repository()))
                    && waits(draft.offers, kept)) {
                elsewhere.put(
                        id,
                        "its work waits at "
                                + said(kept.place())
                                + " of task "
                                + kept.task()
                                + ", so the end of task "
                                + task
                                + " takes no step for it and keeps no other place");
            }
        }
        return elsewhere;
    }

    /** Whether an issue asks the person because its place changed, so nothing moves it. */
    private static boolean placeChanged(final NextStep.@Nullable Progress progress) {
        return progress != null && progress.needsYou() && progress.reason().equals(PLACE_CHANGED);
    }

    /** The binding without the issues given, which its end leaves where they stand. */
    private static NextStep.Binding without(
            final NextStep.Binding binding, final Set<String> left) {
        if (left.isEmpty()) {
            return binding;
        }
        return new NextStep.Binding(
                binding.story(),
                binding.repository(),
                binding.issues().stream().filter(id -> !left.contains(id)).toList(),
                binding.lead(),
                binding.state(),
                binding.mode(),
                binding.rule());
    }

    /**
     * Keeps where an author's ended work waits at the gate, for each issue its end moved or left to
     * follow its children: its own task's push. The same place counts the run once more; a place
     * whose push no longer waits is replaced; an issue kept elsewhere keeps its place.
     */
    private static void keepPlaces(
            final Draft draft,
            final Offer known,
            final String task,
            final Writes writes,
            final List<String> deferred,
            final Set<String> elsewhere) {
        final IssuePlace here = IssuePlace.atGate(task, known.id());
        for (final String id : known.binding().issues()) {
            if (elsewhere.contains(id)
                    || !writes.issues().containsKey(id) && !deferred.contains(id)) {
                continue;
            }
            final IssuePlace kept = draft.places.get(id);
            draft.places.put(
                    id,
                    kept != null
                                    && kept.task().equals(task)
                                    && kept.same(here.place(), known.binding().repository())
                            ? kept.usedBy(known.id())
                            : here);
        }
    }

    /**
     * Whether a kept place's work still waits there: its last author run ended with its work not
     * landed, and no answer read there since took the work away. A run the record no longer holds
     * is taken as waiting.
     */
    private static boolean waits(final List<Offer> offers, final IssuePlace kept) {
        final Offer last = bound(offers, kept);
        if (last == null) {
            return true;
        }
        final Ended ended = last.ended();
        return ended != null && ended.landed().isEmpty() && kept.open();
    }

    /**
     * Whether a kept place is still read: as {@link #waits}, but a landing does not end it, since
     * the gate's forwarding to the task's own pull request is reported as one. Porelo's answer read
     * at the place says whether the work goes on at that pull request or ended there.
     */
    private static boolean stillRead(final List<Offer> offers, final IssuePlace kept) {
        final Offer last = bound(offers, kept);
        return last == null || last.ended() != null && kept.open();
    }

    /** The offer of a kept place's last author run, or null when the run holds none. */
    private static @Nullable Offer bound(final List<Offer> offers, final IssuePlace kept) {
        final String id = kept.authorRuns().getLast();
        return offers.stream().filter(o -> o.id().equals(id)).findFirst().orElse(null);
    }

    /** A place in words. */
    private static String said(final RunPlace place) {
        final RunPlace.GatePush push = place.gatePush();
        if (push != null) {
            return "the gate push "
                    + push.name()
                    + (push.repository() == null ? "" : " of repository " + push.repository());
        }
        final String address = place.pullRequest();
        return address != null ? "the pull request " + address : "the task's own pull request";
    }

    /** What a read of a kept place did. */
    public enum PlaceOutcome {
        /**
         * The read changed what is kept of the issue, a new version of its answer or the end of the
         * stop an unreadable read made, and it asks for no step.
         */
        KEPT,
        /** The read is of the kept place and keeps or changes nothing. */
        UNCHANGED,
        /**
         * The read names another task or place: the issue asks the person, nothing kept changed.
         */
        PLACE_CHANGED,
        /** The run's save is in flight, so nothing is judged now; the next read tries again. */
        WAITING,
        /** Points stand open at the place: the issue went back to rework, its author offered. */
        REWORK,
        /** The issue now asks the person, for a reason the read gave, and nothing moved. */
        NEEDS_YOU,
        /** A new head stands at the place: a task that reads it is offered there, nothing moved. */
        READING_OFFERED,
        /** The work is done at the place: its issue moved on to Review. */
        DONE
    }

    /**
     * The reason an issue asks the person when its push was rejected with nothing open, so no
     * rework can say what to change.
     */
    public static final String REJECTED_WITHOUT_POINTS = "REJECTED_WITHOUT_POINTS";

    /** The reason an issue asks the person when its pull request was closed unmerged. */
    public static final String PLACE_CLOSED = "PLACE_CLOSED";

    /**
     * The reason an issue asks the person when its place merged another head than the last one read
     * there, or one when none was read.
     */
    public static final String MERGED_OTHER_HEAD = "MERGED_OTHER_HEAD";

    /** The reason an issue asks the person when its author runs at its place are used up. */
    public static final String REVIEW_ROUNDS_USED = "REVIEW_ROUNDS_USED";

    /**
     * The reason an issue asks the person when what is open at its place cannot be read, wholly or
     * in part; it is read again on the next read.
     */
    public static final String PLACE_UNREADABLE = "PLACE_UNREADABLE";

    /**
     * The reason an issue asks the person when Porelo refused the Start of its offered task; the
     * offer stays offered, and the refusal stands on it.
     */
    public static final String START_REFUSED = "START_REFUSED";

    /**
     * Where an issue's work waits, as a read of Porelo needs it.
     *
     * @param story the story
     * @param issue the issue
     * @param place the kept place
     * @param container the container of the place's last author run, or null when the run holds
     *     none
     * @param since when that run was started, or null when the run holds none
     */
    public record Waits(
            String story,
            String issue,
            IssuePlace place,
            @Nullable String container,
            @Nullable String since) {}

    /**
     * A task Porelo started for an offer of a run.
     *
     * @param task Porelo's name of the task
     * @param since when it started, or its run's start where the task's is not known; null where
     *     neither is known
     * @param atWork whether it is still at work
     */
    public record Started(String task, @Nullable String since, boolean atWork) {}

    /**
     * The tasks started for the offers of every run, read without waiting for a save in flight.
     *
     * @return each task, in its run's order
     */
    public List<Started> started() {
        final List<Started> started = new ArrayList<>();
        final Map<String, Run> runs = record.latest().runs();
        // Only a place a run started by hand works at is judged on the board.
        final Board board =
                runs.values().stream()
                                .anyMatch(
                                        r ->
                                                r.places().values().stream()
                                                        .anyMatch(p -> p.again() != null))
                        ? store.board()
                        : null;
        for (final Run run : runs.values()) {
            for (final Offer offer : run.offers()) {
                final String task = offer.task();
                if (task != null) {
                    started.add(
                            new Started(
                                    task,
                                    offer.since() == null ? run.since() : offer.since(),
                                    atWork(run, offer, board)));
                }
            }
        }
        return started;
    }

    /**
     * Whether an offer of a run is at work: started, or the ended author's offer whose task the
     * person started again by hand at the place its work still waits at, until that run ends.
     *
     * @param run the run
     * @param offer an offer of the run
     * @param board the board the issue's place is judged on, or null for a bound unchecked against
     *     it
     * @return true while it works
     */
    public static boolean atWork(final Run run, final Offer offer, final @Nullable Board board) {
        if (offer.status().equals("STARTED")) {
            return true;
        }
        final String task = offer.task();
        return task != null
                && run.places().entrySet().stream()
                        .anyMatch(
                                e ->
                                        kept(e.getValue(), offer, task)
                                                && againAt(run, e.getKey(), board));
    }

    /**
     * Whether the person's run started by hand in the task of an issue's kept place works there:
     * the run is neither stopped nor halted, and the issue's work still waits where its author's
     * end left it.
     *
     * @param run the run
     * @param issue the issue
     * @param board the board the issue's place is judged on, or null for a bound unchecked against
     *     it
     * @return true while that run works
     */
    public static boolean againAt(final Run run, final String issue, final @Nullable Board board) {
        final IssuePlace kept = run.places().get(issue);
        return kept != null
                && kept.again() != null
                && !run.stopped()
                && !run.halted()
                && (board == null ? stillRead(run.offers(), kept) : stillWaits(board, run, issue));
    }

    /**
     * The issues whose work still waits at a kept place, read without waiting for a save in flight:
     * never one moved on from where its author's end left it, nor one an offer of its run binds.
     *
     * @return each such issue with its place
     */
    public List<Waits> waiting() {
        final List<Waits> waiting = new ArrayList<>();
        final Map<String, Run> runs = record.latest().runs();
        if (runs.values().stream().allMatch(r -> r.places().isEmpty())) {
            return waiting;
        }
        final Board board = store.board();
        for (final Run run : runs.values()) {
            if (run.stopped() || run.halted()) {
                continue;
            }
            run.places()
                    .forEach(
                            (issue, kept) -> {
                                final Offer bound = bound(run.offers(), kept);
                                if (stillWaits(board, run, issue)) {
                                    waiting.add(
                                            new Waits(
                                                    run.story(),
                                                    issue,
                                                    kept,
                                                    bound == null ? null : bound.containerId(),
                                                    bound == null ? null : bound.since()));
                                }
                            });
        }
        return waiting;
    }

    /**
     * Whether an issue's work still waits at the place its run keeps: kept there, not yet past its
     * place, bound by no offer of its run, and standing where its author left it.
     */
    private static boolean stillWaits(final Board board, final Run run, final String issue) {
        final IssuePlace kept = run.places().get(issue);
        if (kept == null || !stillRead(run.offers(), kept) || bindsIssueAtWork(run, issue)) {
            return false;
        }
        final Offer bound = bound(run.offers(), kept);
        return bound == null || standsWhereLeft(board, bound, issue);
    }

    /**
     * Ends the stop of each issue that asks the person because what is open at its place could not
     * be read, once its work no longer waits there: moved on by hand, bound by an offer of its run,
     * or its place given up. A stop for any other reason stands; a stopped or halted run and one
     * whose save is in flight are left as they are.
     *
     * @return the stories whose runs changed, none when nothing did
     */
    public Set<String> placesLeft() {
        return record.locked(
                session -> {
                    final Snapshot snapshot = read(session);
                    if (snapshot.runs().values().stream()
                            .noneMatch(
                                    r ->
                                            r.issues().values().stream()
                                                    .anyMatch(WorkRun::unreadStop))) {
                        return Set.of();
                    }
                    final Board board = store.board();
                    Snapshot next = snapshot;
                    final Set<String> stories = new LinkedHashSet<>();
                    for (final Run run : snapshot.runs().values()) {
                        if (run.pending() != null || run.stopped() || run.halted()) {
                            continue;
                        }
                        final Draft draft = new Draft(run);
                        for (final Map.Entry<String, NextStep.Progress> entry :
                                run.issues().entrySet()) {
                            final NextStep.Progress p = entry.getValue();
                            if (unreadStop(p) && !stillWaits(board, run, entry.getKey())) {
                                draft.progress.put(
                                        entry.getKey(),
                                        new NextStep.Progress(
                                                p.rounds(),
                                                p.findings(),
                                                false,
                                                "NONE",
                                                p.answer()));
                            }
                        }
                        if (!draft.progress.equals(run.issues())) {
                            next = replace(next, draft.freeze());
                            stories.add(run.story());
                        }
                    }
                    if (next != snapshot) {
                        session.write(next);
                    }
                    return Set.copyOf(stories);
                });
    }

    /** Whether an issue asks the person only because its place could not be read. */
    private static boolean unreadStop(final NextStep.Progress progress) {
        return progress.needsYou() && progress.reason().equals(PLACE_UNREADABLE);
    }

    /**
     * The offers of a run made without any request of the window's, which may wait for the window
     * to start them, unchecked against the board: an author's rework at its kept place or a
     * reviewer there, or the building work of an issue its Start held back, following no end; never
     * an order's own offers, nor an end's. What the window is given is {@link #placeStarts(Run,
     * Board)}; this answers at most as much, for a bound.
     *
     * @param run the run
     * @return those offers, none for a stopped or halted run
     */
    public static List<Offered> placeStarts(final Run run) {
        if (run.stopped() || run.halted()) {
            return List.of();
        }
        return run.offers().stream()
                .filter(
                        o ->
                                o.status().equals("OFFERED")
                                        && run.placeOffers().contains(o.id())
                                        && (o.arguments().place() != null
                                                        && (o.arguments().atAnothersWork()
                                                                || o.arguments().again()
                                                                        && (o.arguments()
                                                                                                .place()
                                                                                                .gatePush()
                                                                                        != null
                                                                                || o.arguments()
                                                                                                .place()
                                                                                                .ownPullRequest()
                                                                                        != null))
                                                || freedOffer(run, o)))
                .map(o -> new Offered(o.id(), o.arguments()))
                .toList();
    }

    /**
     * The offers of a run the window starts without the person, as {@link #placeStarts(Run)}
     * answers them, a freed issue's building work only while its issue is still free to be built on
     * the board as it stands: one whose blocker went back to rework, or that was moved or became a
     * person's since, waits, and is served again once it is free again.
     *
     * @param run the run
     * @param board the board as it stands
     * @return those offers, none for a stopped or halted run
     */
    public static List<Offered> placeStarts(final Run run, final Board board) {
        return placeStarts(run).stream()
                .filter(
                        o ->
                                o.task().place() != null
                                        || freeable(board, run, o.task().issues().getFirst()))
                .toList();
    }

    /**
     * Whether an offer of a run has the shape of a freed issue's building work: an author's builder
     * of one issue at no place, into the run's own working state under a rule that hands back no
     * result and reads nothing, and that is given nothing and reads nothing itself.
     *
     * @param run the run
     * @param offer the offer
     * @return true for that shape
     */
    public static boolean freedOffer(final Run run, final Offer offer) {
        final WorkOrder.Task task = offer.arguments();
        return task.again()
                && task.place() == null
                && !task.lead()
                && offer.binding().role().pushesWork()
                && offer.binding().state().equals(run.to())
                && task.issues().size() == 1
                && offer.binding().rule().result() == null
                && offer.binding().rule().blocked() == null
                && task.result() == null
                && task.given().isEmpty()
                && task.readLanded().isEmpty();
    }

    /** The run's freed offers not started yet. */
    private static List<Offer> freedWaiting(final Run run) {
        return run.offers().stream()
                .filter(
                        o ->
                                o.status().equals("OFFERED")
                                        && run.placeOffers().contains(o.id())
                                        && freedOffer(run, o))
                .toList();
    }

    /**
     * Offers the building work of each issue a run's Start held back by a blocker or a wait, once
     * nothing holds it back any more, so it starts without the person: one story's run at a time,
     * each judged on a fresh read of the record and the board. A run that is stopped, halted, has a
     * save in flight or works under any rule but the building one offers nothing; an issue is
     * offered only while it stands where that rule takes from, is an agent's, holds no warning, its
     * story none either, and no run offers or works it already. It leaves the issues held back once
     * offered, so it is offered once; an offer not started whose issue is no longer free is taken
     * back and its issue held back again, to be offered anew once it is free. An issue whose work
     * cannot be built is told and stays held back; a run that fails is told and left as it stood.
     *
     * @return the stories whose runs offered work, none when nothing was offered
     */
    public Set<String> freed() {
        final List<String> stories =
                record.latest().runs().values().stream()
                        .filter(
                                r ->
                                        (!r.heldBack().isEmpty() || !freedWaiting(r).isEmpty())
                                                && !r.stopped()
                                                && !r.halted()
                                                && r.pending() == null)
                        .map(Run::story)
                        .toList();
        final Set<String> offered = new LinkedHashSet<>();
        for (final String story : stories) {
            try {
                if (record.locked(session -> freed(session, story))) {
                    offered.add(story);
                }
            } catch (final RuntimeException failed) {
                told.accept(
                        "The issues held back in story "
                                + story
                                + " could not be offered: "
                                + NextStep.said(failed));
            }
        }
        return Set.copyOf(offered);
    }

    /** Offers the freed issues of one story's run, under the record's lock. */
    private boolean freed(final Session session, final String story) {
        final Snapshot snapshot = read(session);
        final Run run = snapshot.runs().get(story);
        if (run == null
                || run.heldBack().isEmpty() && freedWaiting(run).isEmpty()
                || run.stopped()
                || run.halted()
                || run.pending() != null) {
            return false;
        }
        final Board board = store.board();
        final Draft draft = new Draft(run);
        boolean changed = false;
        for (final Offer waiting : freedWaiting(run)) {
            final String id = waiting.binding().issues().getFirst();
            if (!freeable(board, run, id)) {
                // Taken back, so no later Start waits on it and its id starts nothing.
                draft.offers.removeIf(o -> o.id().equals(waiting.id()));
                draft.placeOffers.remove(waiting.id());
                if (!draft.heldBack.contains(id)) {
                    draft.heldBack.add(id);
                }
                changed = true;
            }
        }
        final WorkRule rule = building(board, run.to());
        for (final String id : run.heldBack()) {
            if (rule == null || !freeable(board, run, id) || offered(snapshot, id)) {
                continue;
            }
            final Offer next;
            try {
                next = freedTask(board, run, board.issue(id).orElseThrow(), rule);
            } catch (final WorkRefusal | IllegalArgumentException cannotBuild) {
                told.accept(
                        "Issue "
                                + id
                                + " held back in story "
                                + story
                                + " could not be offered: "
                                + NextStep.said(cannotBuild));
                continue;
            }
            listed(draft, next.arguments(), rule);
            draft.progress.putIfAbsent(
                    id,
                    NextStep.Progress.start(
                            run.issueReadingRounds().stream()
                                    .filter(l -> l.issue().equals(id))
                                    .mapToInt(WorkProposal.IssueRounds::rounds)
                                    .findFirst()
                                    .orElse(run.readingRounds())));
            draft.offers.add(next);
            draft.placeOffers.add(next.id());
            draft.heldBack.remove(id);
            changed = true;
        }
        if (!changed) {
            return false;
        }
        final Snapshot next = replace(snapshot, draft.freeze());
        session.preflight(next);
        session.write(next);
        return true;
    }

    /** The building work of a freed issue, in the run's mode where the rule allows it. */
    private static Offer freedTask(
            final Board board, final Run run, final Issue issue, final WorkRule rule) {
        final String story = run.story();
        final String id = issue.id();
        NextStep.Planned planned =
                NextStep.task(
                        board,
                        story,
                        issue,
                        run.to(),
                        rule,
                        null,
                        run.comment(),
                        "",
                        lastBuild(run, id));
        if (!planned.task().mode().equals(run.mode()) && rule.modes().contains(run.mode())) {
            // The issue is built in the run's mode where the rule allows it.
            planned =
                    NextStep.task(
                            board,
                            story,
                            issue,
                            run.to(),
                            rule,
                            new NextStep.Settings(
                                    run.mode(), planned.task().agent(), planned.task().model()),
                            run.comment(),
                            "",
                            lastBuild(run, id));
        }
        return offer(
                new NextStep.Planned(
                        planned.task().again(null),
                        planned.state(),
                        planned.rule(),
                        planned.basis()),
                story);
    }

    /**
     * Whether an issue the run's Start held back is free to be built on the board as it stands:
     * still of the run's story and open, where the run's building rule takes from, an agent's, with
     * no warning on it or its story and no blocker or wait not done.
     */
    private static boolean freeable(final Board board, final Run run, final String id) {
        final WorkRule rule = building(board, run.to());
        final Story story = board.story(run.story()).orElse(null);
        final Issue issue = board.issue(id).orElse(null);
        if (rule == null
                || story == null
                || issue == null
                || board.twice(id).isPresent()
                || board.holdsWork(run.story()).isPresent()) {
            return false;
        }
        final boolean own =
                story.issues().contains(id)
                        || issue.parent() != null && story.issues().contains(issue.parent());
        return own
                && !board.workflow().closed().contains(issue.state())
                && rule.from().contains(issue.state())
                && issue.worker() != Worker.MANUAL
                && !WorkProposal.free(board, run.to(), List.of(issue), null).isEmpty();
    }

    /** Whether any run offers or works the issue. */
    private static boolean offered(final Snapshot snapshot, final String id) {
        return snapshot.runs().values().stream()
                .flatMap(r -> r.offers().stream())
                .anyMatch(
                        o ->
                                (o.status().equals("OFFERED") || o.status().equals("STARTED"))
                                        && o.binding().issues().contains(id));
    }

    /**
     * Lists a freed issue in the run's repository it is built in, or in a new entry for that
     * repository with the task's settings, so the window finds it in the run.
     */
    private static void listed(final Draft draft, final WorkOrder.Task task, final WorkRule rule) {
        final String id = task.issues().getFirst();
        for (int at = 0; at < draft.repositories.size(); at++) {
            final WorkProposal.Repository entry = draft.repositories.get(at);
            if (entry.repository().equals(task.repository())) {
                if (!entry.issues().contains(id)) {
                    final List<String> issues = new ArrayList<>(entry.issues());
                    issues.add(id);
                    draft.repositories.set(
                            at,
                            new WorkProposal.Repository(
                                    entry.repository(), issues, entry.main(), entry.subAgents()));
                }
                return;
            }
        }
        draft.repositories.add(
                new WorkProposal.Repository(
                        task.repository(),
                        List.of(id),
                        new WorkProposal.Main(
                                "main",
                                rule.role(),
                                task.agent(),
                                task.model(),
                                rule.order(),
                                task.effort(),
                                task.basis()),
                        List.of()));
    }

    /**
     * Takes what was read of an issue's kept place from Porelo, never waiting for a save in flight.
     * A read naming another task or place than the one kept changes nothing kept and moves nothing:
     * the issue asks the person, under {@link #PLACE_CHANGED}, with one line naming both. A
     * complete answer at the kept place is kept once per version and judged on every read: points
     * or findings open there with nothing held and no run of the issue bound send the issue back to
     * rework and offer its author again at the place, under the same task's name, while its author
     * runs there are not used up ({@link #REVIEW_ROUNDS_USED}); a rejection with nothing open asks
     * the person ({@link #REJECTED_WITHOUT_POINTS}). An incomplete answer, or none, keeps nothing
     * and asks the person ({@link #PLACE_UNREADABLE}) until a complete one is read, naming Porelo's
     * reason by its name alone. A reason asked once is neither written nor told again, and no read
     * replaces another reason the issue asks for but an unreadable read's, except that an
     * unreadable read replaces the ask that no rework could be offered for the place's points. An
     * issue moved on from where its author's end left it, bound by an offer of its run, or whose
     * place a run started by hand still works at, takes nothing from a read. A read whose save the
     * store refused before writing, for items changed meanwhile, is judged once more.
     *
     * @param story the story whose run keeps the place
     * @param issue the issue
     * @param found what was read
     * @return what the read did
     * @throws WorkRefusal {@code NoSuchStory} for a story without a run, {@code RunChanged} for an
     *     issue whose run keeps no place
     */
    public PlaceOutcome placeRead(final String story, final String issue, final PlaceRead found) {
        Ids.story(story);
        Ids.issue(issue);
        Objects.requireNonNull(found, "found");
        boolean retried = false;
        while (true) {
            final String[] told = new String[1];
            final PlaceStep step =
                    record.locked(session -> placeStep(session, story, issue, found, told));
            final String why = told[0];
            if (why != null) {
                try {
                    this.told.accept("Issue " + issue + " of story " + story + ": " + why);
                } catch (final RuntimeException untold) {
                    // The written record stands; only its line is lost.
                }
            }
            final Flight flight = step.flight();
            if (flight == null) {
                return step.outcome();
            }
            try {
                land(flight);
                return step.outcome();
            } catch (final StoreException.Changed moved) {
                // Nothing was written and the run stands as it was; the store holds the items
                // named read anew, so the read is judged once more on them. A store that lost
                // its board leaves it to the next read.
                if (retried || store.held().isEmpty()) {
                    throw moved;
                }
                retried = true;
            }
        }
    }

    /**
     * A read's outcome and the save that still flies for it.
     *
     * @param outcome the outcome
     * @param flight the save written outside the record's lock, or null
     */
    private record PlaceStep(PlaceOutcome outcome, @Nullable Flight flight) {}

    /** Judges a read of a kept place under the record's lock. */
    private PlaceStep placeStep(
            final Session session,
            final String story,
            final String issue,
            final PlaceRead given,
            final String[] told) {
        final Snapshot snapshot = read(session);
        final Run run = snapshot.runs().get(story);
        if (run == null) {
            throw WorkRefusal.noSuchStory(story);
        }
        if (run.pending() != null) {
            return new PlaceStep(PlaceOutcome.WAITING, null);
        }
        final IssuePlace left = run.places().get(issue);
        if (run.stopped() || run.halted()) {
            // A stopped or halted run takes no step and asks nothing more.
            return new PlaceStep(PlaceOutcome.UNCHANGED, null);
        }
        final Offer bound = left == null ? null : bound(run.offers(), left);
        if (left == null || bound == null) {
            throw WorkRefusal.runChanged(story, "The issue keeps no place in the run.");
        }
        final Board board = store.board();
        if (!standsWhereLeft(board, bound, issue) || bindsIssueAtWork(run, issue)) {
            // Moved on by hand, or bound by a run of its own: its work no longer waits there.
            return new PlaceStep(PlaceOutcome.UNCHANGED, null);
        }
        if (left.again() != null) {
            // A run the person started by hand still works there: nothing is offered, asked or
            // told of work in progress until that run's end is reported.
            return new PlaceStep(PlaceOutcome.UNCHANGED, null);
        }
        final boolean switched =
                given instanceof PlaceRead.Forwarded forwarded
                        && forwardsToOwn(board, issue, left, bound, forwarded);
        // The author's work goes on at the pull request its gate's forwarding opened.
        final IssuePlace kept = switched ? left.forwarded() : left;
        final PlaceRead found =
                given instanceof PlaceRead.Forwarded forwarded
                        ? switched
                                ? new PlaceRead.Opened(
                                        kept.place(), forwarded.task(), forwarded.own())
                                : new PlaceRead.Opened(
                                        forwarded.gate(), forwarded.task(), forwarded.atGate())
                        : given;
        // A reviewer offered but not started yet leaves the place read, so its end is taken.
        final Offer waitingReviewer = waitingReviewer(run, issue);
        final Draft draft = new Draft(run);
        if (switched) {
            draft.places.put(issue, kept);
        }
        final NextStep.Progress progress = draft.progress(issue);
        final String differs = differs(kept, bound, found);
        if (differs != null) {
            if (progress.needsYou() && !progress.reason().equals(PLACE_UNREADABLE)) {
                // Asked once, or asked for another reason, which stands.
                return new PlaceStep(PlaceOutcome.UNCHANGED, null);
            }
            draft.progress.put(issue, progress.attention(PLACE_CHANGED));
            told[0] = differs;
            return new PlaceStep(PlaceOutcome.PLACE_CHANGED, written(session, snapshot, draft));
        }
        final ReviewOpen answer = found instanceof PlaceRead.Opened opened ? opened.answer() : null;
        final String unread =
                found instanceof PlaceRead.Unread missing
                        ? plain(missing.reason(), 200)
                        : answer != null && !answer.complete() ? incomplete(answer) : null;
        if (unread != null) {
            if (progress.needsYou()
                    && !(progress.reason().equals("NEXT_STEP_UNAVAILABLE")
                            && reworkAsked(board, issue, kept, bound))) {
                // Asked already, for this reason or another, which stands; a switch is kept. The
                // ask that no rework could be offered for the place's points gives way, since
                // the place it was judged at can no longer be read.
                return switched
                        ? new PlaceStep(PlaceOutcome.KEPT, written(session, snapshot, draft))
                        : new PlaceStep(PlaceOutcome.UNCHANGED, null);
            }
            draft.progress.put(issue, progress.attention(PLACE_UNREADABLE));
            told[0] =
                    "what is open at "
                            + said(kept.place())
                            + " of task "
                            + kept.task()
                            + " cannot be read, so nothing moves: "
                            + unread;
            return new PlaceStep(PlaceOutcome.NEEDS_YOU, written(session, snapshot, draft));
        }
        if (answer == null) {
            return new PlaceStep(PlaceOutcome.UNCHANGED, null);
        }
        boolean changed = switched;
        IssuePlace now = kept;
        if (kept.last() == null || !kept.last().version().equals(answer.version())) {
            now = kept.read(answer);
            draft.places.put(issue, now);
            changed = true;
        }
        NextStep.Progress before = progress;
        if (before.needsYou() && before.reason().equals(PLACE_UNREADABLE)) {
            // A complete answer read again ends the stop its unreadable read made.
            before =
                    new NextStep.Progress(
                            before.rounds(), before.findings(), false, "NONE", before.answer());
            draft.progress.put(issue, before);
            changed = true;
        }
        if (before.needsYou()
                && before.reason().equals("NEXT_STEP_UNAVAILABLE")
                && now != kept
                && reworkAsked(board, issue, kept, bound)) {
            // The ask that no rework could be offered for the points at the place stands only
            // while the place's answer still holds them: a new head or an approval there is
            // judged afresh, and asks again when its points still find no rework.
            before =
                    new NextStep.Progress(
                            before.rounds(), before.findings(), false, "NONE", before.answer());
            draft.progress.put(issue, before);
            changed = true;
        }
        final WorkRule reviewing = reviewerRule(board, issue);
        final String head = answer.head();
        if (reviewing != null
                && waitingReviewer == null
                && !before.needsYou()
                && answer.held() == 0
                && now.open()
                && (head == null || !head.equals(now.reviewed()))
                && board.issue(issue).map(i -> i.state().equals(goesOn(bound))).orElse(false)) {
            // A new head at the place is reviewed before its open points send it back; an open
            // place without a head names nothing a reviewer could read, so the person is asked.
            return review(
                    session,
                    snapshot,
                    board,
                    run,
                    draft,
                    issue,
                    now,
                    bound,
                    reviewing,
                    head == null
                            ? "Porelo gave no head there, so no reviewer can be offered"
                            : null);
        }
        final boolean stands =
                board.issue(issue).map(i -> i.state().equals(goesOn(bound))).orElse(false);
        // Under a reader's rule its reader reads the landed work first, so only a reviewed place's
        // end moves the issue on or asks how it ended.
        final String closed = reviewing == null ? null : closed(answer, now);
        if (waitingReviewer != null && (closed != null || finished(answer))) {
            // The work there ended, so no reviewer starts on it any more, whatever else the
            // issue asks.
            draft.set(withStatus(waitingReviewer, "VOID"));
            changed = true;
        }
        if (reviewing != null && !before.needsYou() && stands) {
            if (closed != null) {
                draft.progress.put(issue, before.attention(closed));
                told[0] =
                        closed.equals(PLACE_CLOSED)
                                ? said(now.place()) + " was closed unmerged"
                                : said(now.place())
                                        + " was "
                                        + answer.decision().name().toLowerCase(Locale.ROOT)
                                        + " at "
                                        + Objects.requireNonNullElse(answer.head(), "no head")
                                        + ", which is not the head reviewed there ("
                                        + Objects.requireNonNullElse(now.reviewed(), "none was")
                                        + ")";
                return new PlaceStep(PlaceOutcome.NEEDS_YOU, written(session, snapshot, draft));
            }
            if (finished(answer)) {
                return toReview(session, snapshot, board, run, draft, issue, bound);
            }
        }
        if (waitingReviewer != null) {
            // Its points wait for the reviewer offered there, which starts or is voided first.
            if (!changed) {
                return new PlaceStep(PlaceOutcome.UNCHANGED, null);
            }
            written(session, snapshot, draft);
            return new PlaceStep(PlaceOutcome.KEPT, null);
        }
        final boolean open = !answer.points().isEmpty() || answer.findings() > 0;
        final String stop;
        if (before.needsYou()
                || answer.held() > 0
                || !now.open()
                || !board.issue(issue).map(i -> i.state().equals(goesOn(bound))).orElse(false)) {
            // Asked already, held, gone from the gate, or still following its children.
            stop = null;
        } else if (!open) {
            stop =
                    answer.decision() == ReviewOpen.Decision.REJECTED
                            ? REJECTED_WITHOUT_POINTS
                            : null;
        } else if (now.authorRuns().size() - 1 >= before.rounds().roundsMost()
                || now.authorRuns().size() >= IssuePlace.MOST_RUNS) {
            stop = REVIEW_ROUNDS_USED;
        } else {
            return rework(session, snapshot, board, run, draft, issue, now, bound, answer);
        }
        if (stop != null) {
            draft.progress.put(issue, before.attention(stop));
            told[0] =
                    stop.equals(REJECTED_WITHOUT_POINTS)
                            ? "the push of task "
                                    + now.task()
                                    + " was rejected with nothing open, so no rework can say what"
                                    + " to change"
                            : "points stand open at "
                                    + said(now.place())
                                    + ", and its "
                                    + (now.authorRuns().size() - 1)
                                    + " reworks there used the rounds allowed";
            return new PlaceStep(PlaceOutcome.NEEDS_YOU, written(session, snapshot, draft));
        }
        if (!changed) {
            return new PlaceStep(PlaceOutcome.UNCHANGED, null);
        }
        written(session, snapshot, draft);
        return new PlaceStep(PlaceOutcome.KEPT, null);
    }

    /**
     * Whether the work at a place is done there: landed, merged with nothing held, or approved with
     * nothing open and nothing held, so its issue goes on to Review. Merged work with an item held
     * waits, as an approval does, until none is.
     */
    private static boolean finished(final ReviewOpen answer) {
        return switch (answer.decision()) {
            case LANDED -> true;
            case MERGED -> answer.held() == 0;
            case APPROVED ->
                    answer.points().isEmpty() && answer.findings() == 0 && answer.held() == 0;
            default -> false;
        };
    }

    /**
     * Why the work at a place ended other than as reviewed, or null when it did not: closed
     * unmerged, or merged, landed or approved with nothing open at another head than the one its
     * reviewer reviewed there, or with no head reviewed, which the person must judge.
     */
    private static @Nullable String closed(final ReviewOpen answer, final IssuePlace now) {
        if (answer.decision() == ReviewOpen.Decision.CLOSED) {
            return PLACE_CLOSED;
        }
        if (!finished(answer)) {
            return null;
        }
        final String head = answer.head();
        return head != null && head.equals(now.reviewed()) ? null : MERGED_OTHER_HEAD;
    }

    /**
     * Moves the issue on to the done state of the rule it stands in, Review, without offering
     * anything: only the person sets Finished or Rejected. A move that cannot be made asks the
     * person why.
     */
    private PlaceStep toReview(
            final Session session,
            final Snapshot snapshot,
            final Board board,
            final Run run,
            final Draft draft,
            final String issueId,
            final Offer bound) {
        final Issue issue = board.issue(issueId).orElseThrow();
        final WorkRule rule =
                WorkRules.check(board.workflow(), board.work()).rules().get(issue.state());
        String why = barred(board, run, bound, issue);
        if (why == null && rule == null) {
            why = "the state " + issue.state() + " has no sound rule naming where done work goes";
        }
        Writes writes = NOTHING;
        if (why == null && rule != null) {
            try {
                writes = NextStep.writes(board, run.story(), Map.of(issueId, rule.done()));
            } catch (final WorkRefusal | Moves.Refused | IllegalArgumentException cannotMove) {
                why = "it cannot be moved: " + NextStep.said(cannotMove);
            }
        }
        if (why != null) {
            draft.unavailable(
                    issueId, draft.progress(issueId), "its work is done at its place, but " + why);
        } else {
            // Its work is done there, so a later move back by the person starts afresh.
            draft.places.remove(issueId);
        }
        final Planned planned =
                save(
                        session,
                        true,
                        snapshot,
                        snapshot,
                        draft,
                        writes,
                        null,
                        board,
                        issueId,
                        Basis.of(board),
                        RunReads.recheck(board, run.story()));
        return new PlaceStep(
                why != null || draft.progress(issueId).needsYou()
                        ? PlaceOutcome.NEEDS_YOU
                        : PlaceOutcome.DONE,
                planned instanceof Flight flight ? flight : null);
    }

    /** Why an answer is incomplete, by Porelo's name for it alone, which a window may show. */
    private static String incomplete(final ReviewOpen answer) {
        final String reason = answer.reason();
        return reason != null && NAME.matcher(reason).matches()
                ? "Porelo's answer is incomplete (" + reason + ")"
                : "Porelo's answer is incomplete";
    }

    /** A name as Porelo's contract gives one, such as a reason an answer is incomplete. */
    private static final java.util.regex.Pattern NAME =
            java.util.regex.Pattern.compile("[A-Z][A-Za-z]{0,63}");

    /** Writes the run as the draft holds it, under the lock, with no store write. */
    private static @Nullable Flight written(
            final Session session, final Snapshot snapshot, final Draft draft) {
        final Snapshot next = replace(snapshot, draft.freeze());
        session.preflight(next);
        session.write(next);
        return null;
    }

    /**
     * Whether the issue stands where its author's last end left it: in the state its rule goes on
     * in, or, left to follow its children, in the state it was worked in.
     */
    private static boolean standsWhereLeft(
            final Board board, final Offer bound, final String issue) {
        return board.issue(issue)
                .map(
                        i ->
                                i.state().equals(goesOn(bound))
                                        || bound.deferred().contains(issue)
                                                && i.state().equals(bound.binding().state()))
                .orElse(false);
    }

    /** The state an author's rule goes on in, whose rule names where blocked work goes back to. */
    private static String goesOn(final Offer bound) {
        final WorkRule rule = bound.binding().rule();
        return rule.then() == null ? rule.done() : rule.then();
    }

    /**
     * Whether an offer of the run binds the issue other than a reviewer offered at its place and
     * not started yet, whose place is still read.
     */
    private static boolean bindsIssueAtWork(final Run run, final String issue) {
        final Offer reviewer = waitingReviewer(run, issue);
        return run.offers().stream()
                .anyMatch(
                        o ->
                                o != reviewer
                                        && (o.status().equals("OFFERED")
                                                || o.status().equals("STARTED"))
                                        && o.binding().issues().contains(issue));
    }

    /** The reviewer a read of the issue's place offered that has not started yet, or null. */
    private static @Nullable Offer waitingReviewer(final Run run, final String issue) {
        return run.offers().stream()
                .filter(
                        o ->
                                o.status().equals("OFFERED")
                                        && run.placeOffers().contains(o.id())
                                        && o.arguments().atAnothersWork()
                                        && o.binding().issues().contains(issue))
                .findFirst()
                .orElse(null);
    }

    /**
     * Where an issue's rework goes: the working state and its rule.
     *
     * @param state the working state the issue goes back to
     * @param rule that state's rule
     */
    private record Rework(String state, WorkRule rule) {}

    /**
     * Where the points at an issue's place send its author back to, or null when nowhere: the
     * blocked work its state's rule names, when that rule takes work from the state; or, when the
     * state names no blocked work, the rule that built it, in its own working state, while it is
     * still a rule whose role pushes work. That rule reworks the work it made, so its {@code from}
     * need not name the state the work waits in.
     */
    private static @Nullable Rework reworkOf(
            final Board board, final Issue issue, final Offer bound) {
        final Map<String, WorkRule> rules = WorkRules.check(board.workflow(), board.work()).rules();
        final String blocked = blocksTo(board, issue);
        if (blocked != null) {
            final WorkRule rule = rules.get(blocked);
            return rule == null || !rule.from().contains(issue.state())
                    ? null
                    : new Rework(blocked, rule);
        }
        final String built = bound.binding().state();
        final WorkRule rule = rules.get(built);
        // Only an author's building rule reworks: one that now reviews or saves a result has no
        // author to send back.
        return rule == null || !rule.role().pushesWork() ? null : new Rework(built, rule);
    }

    /** The blocked work the rule of the state an issue stands in names, or null for none. */
    private static @Nullable String blocksTo(final Board board, final Issue issue) {
        final WorkRule reading =
                WorkRules.check(board.workflow(), board.work()).rules().get(issue.state());
        return reading == null ? null : reading.blocked();
    }

    /**
     * Sends the issue back to the rule its state's blocked work goes to, or, when its state names
     * none, to the rule that built it, offering its author again at the kept place under the kept
     * task's name, with each open point named; a step that cannot be built asks the person why. The
     * issue moves to that rule's working state with the offer, so its Start finds it there. Saved
     * as the owner account's choices are, so a store refusal before writing leaves the run as it
     * stood; the offer follows no end, so no reply to a task's event names it, and a save that
     * halts changes no offer that ended.
     */
    private PlaceStep rework(
            final Session session,
            final Snapshot snapshot,
            final Board board,
            final Run run,
            final Draft draft,
            final String issueId,
            final IssuePlace place,
            final Offer bound,
            final ReviewOpen answer) {
        final Issue issue = board.issue(issueId).orElseThrow();
        final Rework to = reworkOf(board, issue, bound);
        final String target = to == null ? null : to.state();
        final WorkRule rule = to == null ? null : to.rule();
        final NextStep.Binding scope =
                new NextStep.Binding(
                        run.story(),
                        bound.binding().repository(),
                        List.of(issueId),
                        bound.binding().lead(),
                        bound.binding().state(),
                        bound.binding().mode(),
                        bound.binding().rule());
        final java.util.Optional<String> held =
                board.holdsWork(run.story()).or(() -> board.holdsWork(issueId));
        String why = null;
        if (held.isPresent()) {
            why = held.get();
        } else if (!NextStep.owns(board, scope)) {
            why = "it no longer belongs to story " + run.story();
        } else if (issue.worker() == Worker.MANUAL) {
            why = "a person works it";
        } else if (target == null || rule == null) {
            why =
                    "the rule "
                            + issue.state()
                            + " names no rework that takes work from it"
                            + (blocksTo(board, issue) == null
                                    ? ", nor has the rule "
                                            + bound.binding().state()
                                            + " that built it"
                                    : "");
        } else if (NextStep.waitsForChildren(board, issue, target)) {
            why = "its sub-issues have not reached " + target;
        }
        Writes writes = NOTHING;
        if (why == null && target != null && rule != null) {
            try {
                final String words = openPoints(place, answer);
                NextStep.Planned planned =
                        NextStep.task(
                                board,
                                run.story(),
                                issue,
                                target,
                                rule,
                                null,
                                run.comment(),
                                words,
                                lastBuild(run, issueId));
                final String mode = bound.binding().mode();
                if (!planned.task().mode().equals(mode) && rule.modes().contains(mode)) {
                    // The author goes on in its own mode where the rework allows it.
                    planned =
                            NextStep.task(
                                    board,
                                    run.story(),
                                    issue,
                                    target,
                                    rule,
                                    new NextStep.Settings(
                                            mode, planned.task().agent(), planned.task().model()),
                                    run.comment(),
                                    words,
                                    lastBuild(run, issueId));
                }
                final Offer next =
                        offer(
                                new NextStep.Planned(
                                        planned.task()
                                                .again(
                                                        place.place().ownPullRequest() != null
                                                                ? RunPlace.own()
                                                                : RunPlace.gate(
                                                                        null, place.task())),
                                        planned.state(),
                                        planned.rule(),
                                        planned.basis()),
                                run.story());
                draft.offers.add(next);
                draft.placeOffers.add(next.id());
                writes = NextStep.writes(board, run.story(), Map.of(issueId, target));
            } catch (final WorkRefusal | Moves.Refused | IllegalArgumentException cannotBuild) {
                why = "its rework cannot be offered: " + NextStep.said(cannotBuild);
            }
        }
        if (why != null) {
            draft.unavailable(
                    issueId,
                    draft.progress(issueId),
                    "points stand open at " + said(place.place()) + ", but " + why);
        }
        final Planned planned =
                save(
                        session,
                        true,
                        snapshot,
                        snapshot,
                        draft,
                        writes,
                        null,
                        board,
                        issueId,
                        Basis.of(board),
                        RunReads.recheck(board, run.story()));
        // A full record keeps no next step, so the offer is gone and the issue asks the person.
        return new PlaceStep(
                why != null || draft.progress(issueId).needsYou()
                        ? PlaceOutcome.NEEDS_YOU
                        : PlaceOutcome.REWORK,
                planned instanceof Flight flight ? flight : null);
    }

    /**
     * Whether an issue's ask is the one a place's points make when no rule reworks the issue: the
     * kept answer held points, and neither its state nor the rule that built it takes its rework.
     */
    private static boolean reworkAsked(
            final Board board, final String issueId, final IssuePlace kept, final Offer bound) {
        final IssuePlace.Review last = kept.last();
        final Issue issue = board.issue(issueId).orElse(null);
        if (last == null || issue == null || last.points().isEmpty() && last.findings() == 0) {
            return false;
        }
        return reworkOf(board, issue, bound) == null;
    }

    /** The reviewer rule of the state an issue stands in, or null when that state has none. */
    private static @Nullable WorkRule reviewerRule(final Board board, final String issueId) {
        final Issue issue = board.issue(issueId).orElse(null);
        if (issue == null) {
            return null;
        }
        final WorkRule rule =
                WorkRules.check(board.workflow(), board.work()).rules().get(issue.state());
        return rule == null || !rule.role().readsAnothersWork() ? null : rule;
    }

    /**
     * Why no run of the issue may be offered at its place, or null when one may: work held, the
     * issue no longer the story's, or a person working it.
     */
    private static @Nullable String barred(
            final Board board, final Run run, final Offer bound, final Issue issue) {
        final NextStep.Binding scope =
                new NextStep.Binding(
                        run.story(),
                        bound.binding().repository(),
                        List.of(issue.id()),
                        bound.binding().lead(),
                        bound.binding().state(),
                        bound.binding().mode(),
                        bound.binding().rule());
        final java.util.Optional<String> held =
                board.holdsWork(run.story()).or(() -> board.holdsWork(issue.id()));
        if (held.isPresent()) {
            return held.get();
        }
        if (!NextStep.owns(board, scope)) {
            return "it no longer belongs to story " + run.story();
        }
        if (issue.worker() == Worker.MANUAL) {
            return "a person works it";
        }
        return null;
    }

    /**
     * Offers a reviewer at the kept place, reading the task bound there, while the issue stands in
     * the reviewer rule's state; a reviewer that cannot be offered asks the person why. The place
     * keeps the head reviewed once the reviewer starts, so one voided before its start leaves the
     * head to be offered again; while it waits, the place is still read, but no read offers a
     * second reviewer or a rework, and an end read there voids it. A reason given asks the person
     * with it and offers no reviewer.
     */
    private PlaceStep review(
            final Session session,
            final Snapshot snapshot,
            final Board board,
            final Run run,
            final Draft draft,
            final String issueId,
            final IssuePlace place,
            final Offer bound,
            final WorkRule rule,
            final @Nullable String unreviewable) {
        final Issue issue = board.issue(issueId).orElseThrow();
        String why = unreviewable != null ? unreviewable : barred(board, run, bound, issue);
        if (why == null && NextStep.waitsForChildren(board, issue, issue.state())) {
            why = "its sub-issues have not reached " + issue.state();
        }
        if (why == null) {
            try {
                final Offer next =
                        offer(
                                NextStep.reviewer(
                                        board,
                                        run.story(),
                                        issue,
                                        issue.state(),
                                        rule,
                                        run.comment(),
                                        place.place(),
                                        place.task(),
                                        said(place.place())),
                                run.story());
                draft.offers.add(next);
                draft.placeOffers.add(next.id());
            } catch (final WorkRefusal | IllegalArgumentException cannotBuild) {
                why = "its reviewer cannot be offered: " + NextStep.said(cannotBuild);
            }
        }
        if (why != null) {
            draft.unavailable(
                    issueId,
                    draft.progress(issueId),
                    // Without a head no push is known, only that work stands there.
                    (unreviewable != null ? "work stands at " : "a new push stands at ")
                            + said(place.place())
                            + ", but "
                            + why);
        }
        final Planned planned =
                save(
                        session,
                        true,
                        snapshot,
                        snapshot,
                        draft,
                        NOTHING,
                        null,
                        board,
                        issueId,
                        Basis.of(board),
                        RunReads.recheck(board, run.story()));
        return new PlaceStep(
                why != null || draft.progress(issueId).needsYou()
                        ? PlaceOutcome.NEEDS_YOU
                        : PlaceOutcome.READING_OFFERED,
                planned instanceof Flight flight ? flight : null);
    }

    /**
     * The words a rework at a place is given: each point kept open there, each part one plain line
     * of bounded length, the count of the points cut, and the findings' count.
     */
    private static String openPoints(final IssuePlace place, final ReviewOpen answer) {
        final IssuePlace.Review kept = Objects.requireNonNull(place.last(), "a kept answer");
        final StringBuilder words =
                new StringBuilder("Answer each point open at ")
                        .append(said(place.place()))
                        .append(" of task ")
                        .append(place.task())
                        .append(", then push again:");
        for (final ReviewOpen.Point point : kept.points()) {
            final String path = point.path();
            final Integer line = point.line();
            words.append("\n- thread ")
                    .append(plain(point.thread(), 100))
                    .append(path == null ? " on the whole push" : " on " + plain(path, 200))
                    .append(path == null || line == null ? "" : ":" + line)
                    .append(", last by ")
                    .append(plain(point.lastBy(), 100));
        }
        if (kept.pointsTotal() > kept.points().size()) {
            words.append("\n- and ")
                    .append(kept.pointsTotal() - kept.points().size())
                    .append(" more");
        }
        if (answer.findings() > 0) {
            words.append("\n- ").append(answer.findings()).append(" findings no one answered");
        }
        return words.toString();
    }

    /** Text from outside as one plain line: control and format characters dropped, cut. */
    private static String plain(final String text, final int most) {
        final StringBuilder plain = new StringBuilder();
        text.codePoints()
                .filter(
                        c ->
                                !Character.isISOControl(c)
                                        && Character.getType(c) != Character.FORMAT
                                        && Character.getType(c) != Character.LINE_SEPARATOR
                                        && Character.getType(c) != Character.PARAGRAPH_SEPARATOR)
                .limit(most)
                .forEach(plain::appendCodePoint);
        return plain.toString();
    }

    /**
     * Why a read is not of the kept place, or null when it is. A task's record must be of the bound
     * task's container and workspace, at the place, a record naming no place, or a push in its own
     * repository, being at that task's own push; an answer must be asked for the bound task at the
     * place.
     */
    private static @Nullable String differs(
            final IssuePlace kept, final Offer bound, final PlaceRead found) {
        final String repository = bound.binding().repository();
        final String expected = "task " + kept.task() + " at " + said(kept.place());
        return switch (found) {
            case PlaceRead.Recorded recorded -> {
                final TaskRecord task = recorded.record();
                final RunPlace place = task.place();
                final Role.Workspace kind = task.workspace();
                // A place kept for a task's own work is matched by a run at its own work alone.
                final boolean other =
                        kind != null
                                && kind
                                        != (kept.foreign()
                                                ? Role.Workspace.FOREIGN
                                                : Role.Workspace.OWN);
                final String in = task.repository();
                // Porelo names the repository by its own name for it, which may differ from the
                // board's; the container, started for this very offer, binds the record to it,
                // and a push the record names in its own repository is its own push.
                final RunPlace.GatePush push = place == null ? null : place.gatePush();
                final RunPlace at =
                        place == null
                                ? RunPlace.gate(null, kept.task())
                                : push != null && in != null && in.equals(push.repository())
                                        ? RunPlace.gate(null, push.name())
                                        : place;
                yield task.task().equals(bound.containerId())
                                && !other
                                && (kept.same(at, repository) || kept.forwardedFrom(at, repository))
                        ? null
                        : "Porelo's record of container "
                                + task.task()
                                + (kind == null ? "" : " in the workspace " + kind.word())
                                + (in == null ? "" : " in repository " + in)
                                + " at "
                                + said(at)
                                + " is not of "
                                + expected
                                + (kept.foreign() ? " at another task's work" : " at its own work")
                                + " in repository "
                                + repository
                                + " in container "
                                + bound.containerId()
                                + ", where its work waits";
            }
            case PlaceRead.Unread unread ->
                    unread.task().equals(kept.task()) && kept.same(unread.place(), repository)
                            ? null
                            : "what is open was asked for task "
                                    + unread.task()
                                    + " at "
                                    + said(unread.place())
                                    + ", not for "
                                    + expected
                                    + ", where its work waits";
            case PlaceRead.Forwarded forwarded ->
                    differs(
                            kept,
                            bound,
                            new PlaceRead.Opened(
                                    forwarded.gate(), forwarded.task(), forwarded.atGate()));
            case PlaceRead.Opened opened ->
                    opened.task().equals(kept.task()) && kept.same(opened.place(), repository)
                            ? null
                            : "what is open was read for task "
                                    + opened.task()
                                    + " at "
                                    + said(opened.place())
                                    + ", not for "
                                    + expected
                                    + ", where its work waits";
        };
    }

    /**
     * Whether a read of a forwarded gate push moves an author's kept place to its task's own pull
     * request: the push kept is the one read, for its task, and no reviewer rule reads the work,
     * whose reviewer reviews at the gate.
     */
    private static boolean forwardsToOwn(
            final Board board,
            final String issue,
            final IssuePlace kept,
            final Offer bound,
            final PlaceRead.Forwarded forwarded) {
        return !kept.foreign()
                && kept.place().gatePush() != null
                && forwarded.task().equals(kept.task())
                && kept.same(forwarded.gate(), bound.binding().repository())
                && reviewerRule(board, issue) == null;
    }

    /** Whether an offer's task hands back its result instead of pushing its work. */
    private static boolean staged(final NextStep.Binding binding) {
        return binding.role().handsBackResult();
    }

    /**
     * Ends a stage's task: its issues moved with its saved result, or stay and ask the person,
     * saying why; an end before any hand-in is recorded as the offer's missing result.
     */
    private static Snapshot endedStage(
            final Snapshot snapshot, final Draft draft, final Offer known, final String task) {
        final HandedResults.Handed handed = snapshot.results().get(known.id());
        if (handed != null
                && (HandedResults.SAVED.equals(handed.status())
                        || HandedResults.WAITING.equals(handed.status())
                        || HandedResults.PENDING.equals(handed.status()))) {
            // A result waiting for the forge access is handed in again, its issues where they are;
            // one whose save is still in flight moves them with that save.
            return snapshot;
        }
        final String why = HandedResults.why(handed);
        for (final String id : known.binding().issues()) {
            final NextStep.Progress p = draft.progress.get(id);
            if (p != null) {
                draft.unavailable(id, p, why);
            }
        }
        return handed != null
                ? snapshot
                : HandedResults.with(
                        snapshot,
                        known.id(),
                        HandedResults.MISSING,
                        task,
                        known.binding().issues(),
                        List.of(),
                        "The task ended without handing in a result.");
    }

    /**
     * Records where a build's work landed and offers its readers exactly then; refused as a
     * conflict for an unknown offer, one that did not end done, or one that already landed, so a
     * resend offers no second reader.
     */
    private Answer landed(final String task, final Report report) {
        @Nullable String waited = null;
        while (true) {
            final Step step = record.locked(session -> landedStep(session, task, report));
            if (step instanceof Answered answered) {
                return answered.answer();
            }
            final Waiting wait = (Waiting) step;
            waitFor(wait.story(), wait.id(), waited);
            waited = wait.id();
        }
    }

    /** Plans a landing report as one step under the record's lock; it writes no store item. */
    private Step landedStep(final Session outer, final String task, final Report report) {
        final List<NextStep.Landing> landed = report.landed();
        final Function<Session, Step> step =
                session -> {
                    final Snapshot snapshot = read(session);
                    final Run run =
                            snapshot.runs().values().stream()
                                    .filter(
                                            r ->
                                                    r.offers().stream()
                                                            .anyMatch(
                                                                    o ->
                                                                            o.id().equals(
                                                                                            report
                                                                                                    .offer())))
                                    .findFirst()
                                    .orElseThrow(
                                            () ->
                                                    conflict(
                                                            task,
                                                            Event.LANDED,
                                                            "No offer answers this task."));
                    final PendingSave inFlight = run.pending();
                    if (inFlight != null) {
                        return new Waiting(run.story(), inFlight.id());
                    }
                    final Offer known =
                            run.offers().stream()
                                    .filter(o -> o.id().equals(report.offer()))
                                    .findFirst()
                                    .orElseThrow();
                    if (!task.equals(known.task())
                            || !report.container().equals(known.containerId())
                            || known.machine() != null
                                    && !report.machine().equals(known.machine())) {
                        throw conflict(
                                task, Event.LANDED, "The report contradicts the task binding.");
                    }
                    final Ended ended = known.ended();
                    if (ended == null) {
                        throw conflict(task, Event.LANDED, "The task has not ended.");
                    }
                    if (!ended.landed().isEmpty() || readerFollows(run, known)) {
                        throw conflict(task, Event.LANDED, "The task's work has already landed.");
                    }
                    if (!known.binding().role().pushesWork()
                            || !(WorkEnds.judge(
                                            false,
                                            WorkEnds.Outcome.of(
                                                    ended.finished(),
                                                    ended.unpushed(),
                                                    ended.outcome()),
                                            ended.answer())
                                    instanceof WorkEnds.Done)) {
                        throw conflict(task, Event.LANDED, "The task did not end done.");
                    }
                    if (run.stopped() || run.halted()) {
                        throw conflict(task, Event.LANDED, "The run no longer offers work.");
                    }
                    final Draft draft = new Draft(run);
                    final Board board = store.board();
                    final NextStep.Result next =
                            landedNext(
                                    board,
                                    without(
                                            known.binding(),
                                            known.binding().issues().stream()
                                                    .filter(
                                                            id ->
                                                                    placeChanged(
                                                                            draft.progress.get(id)))
                                                    .collect(java.util.stream.Collectors.toSet())),
                                    draft.progress,
                                    draft.comment,
                                    new NextStep.Build(task, landed));
                    draft.progress.putAll(next.progress());
                    draft.unavailable.putAll(next.unavailable());
                    final List<String> readers = new ArrayList<>();
                    for (final NextStep.Planned planned : next.tasks()) {
                        final Offer offer = offer(planned, run.story());
                        draft.offers.add(offer);
                        readers.add(offer.id());
                    }
                    final List<String> following = new ArrayList<>(known.following());
                    following.addAll(readers);
                    draft.set(
                            new Offer(
                                    known.id(),
                                    known.arguments(),
                                    known.binding(),
                                    known.status(),
                                    known.task(),
                                    known.containerId(),
                                    known.machine(),
                                    known.since(),
                                    new Ended(
                                            ended.at(),
                                            ended.finished(),
                                            ended.why(),
                                            ended.answer(),
                                            ended.unpushed(),
                                            landed,
                                            ended.outcome()),
                                    known.fingerprint(),
                                    following,
                                    known.deferred(),
                                    known.refusal(),
                                    known.reviewAnyway(),
                                    known.reworkComment(),
                                    known.rework(),
                                    known.startRefusal()));
                    draft.compact(known.id());
                    final Run saved =
                            written(
                                    save(
                                            session,
                                            snapshot,
                                            draft,
                                            NOTHING,
                                            known.id(),
                                            board,
                                            null,
                                            Basis.none()));
                    return new Answered(answer(saved, readers));
                };
        return step.apply(outer);
    }

    /**
     * A reader of the build's issues offered after it, as an older record holds one offered at the
     * end, or at its child's end for a parent, before an end could come without its landing.
     */
    private static boolean readerFollows(final Run run, final Offer build) {
        final List<Offer> after =
                run.offers().subList(run.offers().indexOf(build) + 1, run.offers().size());
        return after.stream()
                .anyMatch(
                        o ->
                                landedReader(o)
                                        && !o.status().equals("VOID")
                                        && o.binding().issues().stream()
                                                .anyMatch(build.binding().issues()::contains));
    }

    /**
     * Reads the run as last written, without waiting for a save in flight and without inferring
     * anything from a working state when its record was lost.
     */
    public @Nullable Run work(final String story) {
        Ids.story(story);
        return record.latest().runs().get(story);
    }

    /**
     * Repairs the record once, as a project opens: a run a save left pending, which only a save
     * that died midway leaves, is halted, and readers of an older record are named their builds. A
     * result left pending is refused as not ended. Neither readers nor writers repair, since a run
     * pending while they read is a save's own in flight.
     */
    public void repair() {
        record.locked(
                session -> {
                    repaired(session);
                    return read(session);
                });
    }

    /**
     * Halts each run a save left pending and refuses each result whose save did not end: at open no
     * save of this planner is in flight, so every mark is one a stopped planner left.
     */
    private static void repaired(final Session session) {
        final Snapshot read = session.read();
        final Map<String, Run> runs = new LinkedHashMap<>(read.runs());
        for (final Run run : read.runs().values()) {
            if (run.pending() != null) {
                final Draft draft = new Draft(run);
                draft.pending = null;
                draft.halted = true;
                draft.voidOffers();
                draft.progress.replaceAll((id, p) -> p.attention("SAVE_REFUSED"));
                runs.put(run.story(), draft.freeze());
            }
        }
        Snapshot snapshot = read.withRuns(read.generation(), runs);
        for (final Map.Entry<String, HandedResults.Handed> result : read.results().entrySet()) {
            final HandedResults.Handed known = result.getValue();
            if (HandedResults.PENDING.equals(known.status())) {
                snapshot =
                        HandedResults.with(
                                snapshot,
                                result.getKey(),
                                HandedResults.REFUSED,
                                known.task(),
                                known.issues(),
                                List.of(HandedResults.NOT_ENDED),
                                "");
            }
        }
        if (!snapshot.equals(read)) {
            session.write(
                    snapshot.withGeneration(
                            Math.max(snapshot.generation(), read.generation() + 1)));
        }
    }

    /**
     * Reads the board and the last written work generation without waiting for an ongoing save; the
     * two may come from either side of a save, so nothing written may be decided on them.
     */
    public <T> T view(final java.util.function.BiFunction<Board, Snapshot, T> answer) {
        final Snapshot snapshot = record.latest();
        return answer.apply(store.board(), snapshot);
    }

    /** Reads the last complete written generation for board decoration, without waiting. */
    public Snapshot snapshot() {
        return record.latest();
    }

    /** Returns recorded active task names, never names inferred from board states. */
    public List<String> active(final String story) {
        return running(record.latest(), Set.of()).tasks(story);
    }

    /** Records cancellation without clearing activity until the window reports a terminal event. */
    public @Nullable Run stop(final String story) {
        return stop(story, null);
    }

    /**
     * Records cancellation of the run named without clearing activity until the window reports a
     * terminal event.
     *
     * @param story the story
     * @param named the run the person saw, or null to stop whichever stands
     * @return the run, or null when none is recorded
     * @throws WorkRefusal {@code RunChanged} when the run named is not the one standing
     */
    public @Nullable Run stop(final String story, final @Nullable String named) {
        Ids.story(story);
        return record.locked(
                        session -> {
                            final Snapshot snapshot = read(session);
                            final Run run = snapshot.runs().get(story);
                            if (named != null && (run == null || !run.id().equals(named))) {
                                throw WorkRefusal.runChanged(
                                        story, "The run named is not the one standing.");
                            }
                            // A running story left off the board must still be stoppable.
                            if (run == null) {
                                WorkProposal.story(store.board(), story);
                            }
                            if (run == null || run.stopped()) {
                                return java.util.Optional.ofNullable(run);
                            }
                            final Draft draft = new Draft(run);
                            draft.stopped = true;
                            draft.voidOffers();
                            draft.progress.replaceAll((id, p) -> p.attention("STOPPED"));
                            final Run stopped = draft.freeze();
                            session.write(replace(snapshot, stopped));
                            return java.util.Optional.of(stopped);
                        })
                .orElse(null);
    }

    /**
     * Records one reading choice before its offers can leave the record lock.
     *
     * @param story the story
     * @param issueId the issue whose reading stopped
     * @param round the round the person saw
     * @param choice the choice
     * @param comment the person's words
     * @param seen the versions the person saw: of the issue, and of every item the choice saves
     * @param rework the settings a rework runs with, null for the Reworking rule's first mode and
     *     model; only a rework takes them
     * @return the run and the offers that follow
     */
    public Answer choose(
            final String story,
            final String issueId,
            final int round,
            final ReadingCycle.Choice choice,
            final String comment,
            final Basis seen,
            final NextStep.@Nullable Settings rework) {
        Ids.story(story);
        Ids.issue(issueId);
        words(comment);
        if (rework != null && choice != ReadingCycle.Choice.REWORK) {
            throw WorkRefusal.invalidSettings();
        }
        final Chosen chosen =
                record.locked(
                        session -> {
                            final Snapshot snapshot = read(session);
                            refuseInFlight(snapshot, Set.of(issueId), Set.of(story), null);
                            final Board board = store.board();
                            final Story found = WorkProposal.story(board, story);
                            WorkRefusal.unlessFree(board, story, found.state(), "");
                            WorkRefusal.unlessFree(board, issueId, "", "");
                            final Issue issue =
                                    board.issue(issueId)
                                            .orElseThrow(
                                                    () -> new StoreException.NoSuchIssue(issueId));
                            if (issue.worker() == Worker.MANUAL) {
                                throw WorkRefusal.notAllowed(
                                        issue.state(),
                                        issue.state(),
                                        "A person works "
                                                + issueId
                                                + ", so no agent's reading or rework starts on it.");
                            }
                            final Run run = snapshot.runs().get(story);
                            final NextStep.Progress previous =
                                    run == null ? null : run.issues().get(issueId);
                            if (run == null
                                    || run.stopped()
                                    || run.halted()
                                    || previous == null
                                    || !previous.rounds().stopped()
                                    || !(found.issues().contains(issueId)
                                            || issue.parent() != null
                                                    && found.issues().contains(issue.parent()))) {
                                throw WorkRefusal.readingNotStopped(issueId);
                            }
                            Edits.unchanged(board, seen, Set.of(issueId));
                            final ReadingCycle.Counted counted;
                            try {
                                counted =
                                        ReadingCycle.choose(
                                                previous.rounds(),
                                                round,
                                                choice,
                                                comment,
                                                previous.findings(),
                                                previous.unreadable());
                            } catch (final ReadingCycle.Refused refused) {
                                if ("ReadingRoundChanged".equals(refused.getMessage())) {
                                    throw WorkRefusal.readingRoundChanged(
                                            issueId, previous.rounds().rounds());
                                }
                                if ("ReadingNotStopped".equals(refused.getMessage())) {
                                    throw WorkRefusal.readingNotStopped(issueId);
                                }
                                if ("ReworkNeedsFindings".equals(refused.getMessage())) {
                                    throw WorkRefusal.reworkNeedsFindings(issueId);
                                }
                                throw WorkRefusal.invalidSettings();
                            }
                            final Offer reading =
                                    run.offers().stream()
                                            .filter(
                                                    o ->
                                                            landedReader(o)
                                                                    && o.binding()
                                                                            .issues()
                                                                            .equals(
                                                                                    List.of(
                                                                                            issueId)))
                                            .reduce((left, right) -> right)
                                            .orElseThrow(
                                                    () -> WorkRefusal.readingNotStopped(issueId));
                            if (!NextStep.owns(board, reading.binding())
                                    || !issue.state().equals(reading.binding().state())) {
                                throw WorkRefusal.readingNotStopped(issueId);
                            }
                            final NextStep.Settings settings =
                                    counted.next() == ReadingCycle.Next.REWORK
                                            ? reworkSettings(
                                                    board, reading.binding().rule(), issue, rework)
                                            : null;
                            final Draft draft = new Draft(run);
                            draft.progress.put(
                                    issueId,
                                    new NextStep.Progress(
                                            counted.rounds(),
                                            previous.findings(),
                                            false,
                                            "NONE",
                                            previous.answer()));
                            final List<String> following = new ArrayList<>();
                            Writes writes = NOTHING;
                            final String target =
                                    switch (counted.next()) {
                                        case REVIEW -> reading.binding().rule().done();
                                        case READ -> reading.binding().state();
                                        case REWORK -> reading.binding().rule().blocked();
                                        default -> null;
                                    };
                            if (target == null) {
                                draft.unavailable(
                                        issueId,
                                        draft.progress(issueId),
                                        "the rule "
                                                + reading.binding().state()
                                                + " names no state for the choice "
                                                + choice);
                            } else {
                                try {
                                    if (counted.next() == ReadingCycle.Next.REVIEW) {
                                        if (NextStep.waitsForChildren(board, issue, target)) {
                                            draft.set(
                                                    new Offer(
                                                            reading.id(),
                                                            reading.arguments(),
                                                            reading.binding(),
                                                            reading.status(),
                                                            reading.task(),
                                                            reading.containerId(),
                                                            reading.machine(),
                                                            reading.since(),
                                                            reading.ended(),
                                                            reading.fingerprint(),
                                                            reading.following(),
                                                            List.of(issueId),
                                                            reading.refusal(),
                                                            true,
                                                            comment,
                                                            null));
                                        } else {
                                            writes =
                                                    NextStep.writes(
                                                            board, story, Map.of(issueId, target));
                                        }
                                    } else if (counted.next() == ReadingCycle.Next.REWORK
                                            && NextStep.waitsForChildren(board, issue, target)) {
                                        draft.set(
                                                new Offer(
                                                        reading.id(),
                                                        reading.arguments(),
                                                        reading.binding(),
                                                        reading.status(),
                                                        reading.task(),
                                                        reading.containerId(),
                                                        reading.machine(),
                                                        reading.since(),
                                                        reading.ended(),
                                                        reading.fingerprint(),
                                                        reading.following(),
                                                        List.of(issueId),
                                                        reading.refusal(),
                                                        false,
                                                        comment,
                                                        settings));
                                    } else {
                                        final WorkRule rule =
                                                WorkRules.check(board.workflow(), board.work())
                                                        .rules()
                                                        .get(target);
                                        if (rule == null
                                                || counted.next() != ReadingCycle.Next.READ
                                                        && !rule.from().contains(issue.state())) {
                                            throw WorkRefusal.invalidSettings();
                                        }
                                        final Offer next =
                                                offer(
                                                        NextStep.task(
                                                                board,
                                                                story,
                                                                issue,
                                                                target,
                                                                rule,
                                                                settings,
                                                                run.comment(),
                                                                NextStep.findingText(
                                                                                previous.findings())
                                                                        + (comment.isEmpty()
                                                                                ? ""
                                                                                : "\n" + comment),
                                                                lastBuild(run, issueId)),
                                                        story);
                                        draft.offers.add(next);
                                        following.add(next.id());
                                    }
                                } catch (final WorkRefusal | IllegalArgumentException cannotBuild) {
                                    // Nothing was offered, so the reading still waits for a choice.
                                    draft.unavailable(
                                            issueId,
                                            previous,
                                            "the choice "
                                                    + choice
                                                    + " toward "
                                                    + target
                                                    + " cannot be offered: "
                                                    + NextStep.said(cannotBuild));
                                }
                            }
                            writes = reconcile(board, draft, writes, following);
                            final Set<String> targets = new java.util.TreeSet<>(writes.targets());
                            targets.add(issueId);
                            Edits.unchanged(board, seen, targets);
                            return new Chosen(
                                    save(
                                            session,
                                            snapshot,
                                            draft,
                                            writes,
                                            reading.id(),
                                            board,
                                            issueId,
                                            seen),
                                    following);
                        });
        return answer(landed(chosen.planned()), chosen.following());
    }

    /**
     * A reading choice planned under the record's lock, its save written outside it.
     *
     * @param planned the save
     * @param following the offers that follow
     */
    private record Chosen(Planned planned, List<String> following) {}

    /**
     * Serializes a store write that touches no run, such as a new issue, with the runs' saves.
     *
     * @param save the write
     * @param <T> what the write answers
     * @return what the write answered
     */
    public <T> T alone(final Supplier<T> save) {
        return record.locked(session -> save.get());
    }

    /**
     * Serializes a store write that touches no run but must not stand beside one, such as a new
     * issue placed in a working state, with the runs' saves.
     *
     * @param save the write, given the runs the record holds under the lock
     * @param <T> what the write answers
     * @return what the write answered
     */
    public <T> T withRuns(final Function<Moves.Runs, T> save) {
        return record.locked(session -> save.apply(held(read(session))));
    }

    /**
     * Saves a new story under the record's lock: checks it against the board read under the lock
     * first, then drops every run whose story is gone, so a story saved under a gone story's id
     * never takes its run over and a refused story writes nothing.
     *
     * @param check checks the story against the board and answers what the save takes
     * @param save the write, given the board and what the check answered
     * @param <C> what the check answers
     * @param <T> what the write answers
     * @return what the write answered
     * @throws WorkRefusal {@code WorkRunning} while a task of a gone story's run still works
     */
    public <C, T> T newStory(final Function<Board, C> check, final BiFunction<Board, C, T> save) {
        return record.locked(
                session -> {
                    final Board board = store.board();
                    final C checked = check.apply(board);
                    final Snapshot snapshot = read(session);
                    final Snapshot kept = withoutGoneRuns(snapshot, board);
                    if (!kept.runs().equals(snapshot.runs())) {
                        session.preflight(kept);
                        session.write(kept);
                    }
                    return save.apply(board, checked);
                });
    }

    /**
     * Saves a new story over issues that stand on the board, as {@link #newStory(Function,
     * BiFunction)} saves one, and refuses it while a started task works one of those issues or a
     * sub-issue of one: the task's results would land in a story it was not started for. The runs
     * of gone stories are dropped from the work record only once the store's save stands, still
     * under the record's lock, so a call the store refuses leaves the record as it was; the record
     * is checked writable before the save.
     *
     * @param issues the issues the new story takes
     * @param check checks the story against the board and answers what the save takes
     * @param save the write, given the board and what the check answered
     * @param <C> what the check answers
     * @param <T> what the write answers
     * @return what the write answered
     * @throws WorkRefusal {@code WorkRunning} while a started task works an issue taken, naming the
     *     issue, or a task of a gone story's run still works
     */
    public <C, T> T newStory(
            final List<String> issues,
            final Function<Board, C> check,
            final BiFunction<Board, C, T> save) {
        issues.forEach(Ids::issue);
        return record.locked(
                session -> {
                    final Board board = store.board();
                    final C checked = check.apply(board);
                    final Snapshot snapshot = read(session);
                    for (final String issue : issues) {
                        refuseOwned(snapshot, withSubIssues(board, issue), Set.of(), issue);
                    }
                    final Snapshot kept = withoutGoneRuns(snapshot, board);
                    final boolean dropped = !kept.runs().equals(snapshot.runs());
                    if (dropped) {
                        session.preflight(kept);
                    }
                    final T saved = save.apply(board, checked);
                    if (dropped) {
                        session.write(kept);
                    }
                    return saved;
                });
    }

    /**
     * Saves a story's new issue list under the record's lock, refusing it while a started task
     * works an issue it takes out, or one of its sub-issues: the task's results would no longer
     * belong to the story. The board is read, checked and compared with the list under the lock, so
     * an issue hung in and started meanwhile is never taken out unseen, and a stale or wrong list
     * is refused as such before any run is asked.
     *
     * @param story the story's id
     * @param issues the story's new issue list
     * @param check checks the list against the board read under the lock
     * @param save the write, given that board
     * @param <T> what the write answers
     * @return what the write answered
     * @throws WorkRefusal {@code WorkRunning} while a started task owns an issue taken out
     */
    public <T> T storyIssues(
            final String story,
            final List<String> issues,
            final Consumer<Board> check,
            final Function<Board, T> save) {
        Ids.story(story);
        issues.forEach(Ids::issue);
        return record.locked(
                session -> {
                    final Board board = store.board();
                    check.accept(board);
                    final Set<String> owned = new HashSet<>();
                    board.story(story).map(Story::issues).orElse(List.of()).stream()
                            .filter(issue -> !issues.contains(issue))
                            .forEach(issue -> owned.addAll(withSubIssues(board, issue)));
                    refuseOwned(read(session), owned, Set.of());
                    return save.apply(board);
                });
    }

    /**
     * Follows a discussion's offer, so the reports of the task started with it are read and its
     * idea saved once Porelo records that task.
     *
     * @param offer the discussion's offer, a canonical lowercase UUID
     * @throws WorkRefusal {@code WorkRecordUnavailable} when the record cannot be written
     */
    public void discussed(final String offer) {
        record.locked(
                session -> {
                    ReportTaking.follow(session, offer, clock.instant());
                    return Boolean.TRUE;
                });
    }

    /**
     * Reads and takes the reports of every task the planner started that it still follows: a report
     * of kind {@value TaskReport#IDEA} becomes a story with its issues once per task name and
     * number, an idea its check refuses is taken and said, any other report is left to the window.
     *
     * @param project the project's name
     * @param records Porelo's task records
     * @param reports Porelo's reports
     * @param ideas reads an idea from a report's text
     * @return what was taken, in fixed words
     * @throws TaskRecords.Unanswered when Porelo cannot answer its task records
     */
    public ReportTaking.Outcome takeReports(
            final String project,
            final TaskRecords records,
            final TaskReports reports,
            final ReportTaking.IdeaText ideas) {
        return new ReportTaking(store, queue, record, clock).take(project, records, reports, ideas);
    }

    /**
     * Takes the result a stage's task handed in for its offer, once per offer: a sound result is
     * saved through the store, which moves its issues to the rule's done state, those a readiness
     * check finds not ready back to the rule's from state, and the offer's outcome is recorded, so
     * a repeated hand-in answers the first outcome and writes nothing.
     *
     * @param task the task's name, as the offer was bound to it when it started
     * @param offer the offer
     * @param handIn the hand-in
     * @return the outcome, saved or missing
     * @throws WorkRefusal {@code InvalidSettings} for a malformed task name, {@code
     *     TaskNotAnswered} for an unknown, void or foreign offer, {@code ResultRefused} for an
     *     offer that takes no result now or a result that is refused
     */
    public HandedResults.Answer result(
            final String task, final String offer, final HandedResults.HandIn handIn) {
        if (!Ids.isTask(task)) {
            throw WorkRefusal.invalidSettings();
        }
        HandedResults.offer(offer);
        return record.<Supplier<HandedResults.Answer>>locked(
                        session -> {
                            final Snapshot snapshot = read(session);
                            final HandedResults.Handed known = snapshot.results().get(offer);
                            if (known != null && !known.task().equals(task)) {
                                throw WorkRefusal.taskNotAnswered(task, offer);
                            }
                            final boolean waiting =
                                    known != null && HandedResults.WAITING.equals(known.status());
                            if (known != null && !waiting) {
                                final HandedResults.Answer replayed =
                                        replayed(session, snapshot, offer, known);
                                return () -> replayed;
                            }
                            final Run run =
                                    snapshot.runs().values().stream()
                                            .filter(
                                                    r ->
                                                            r.offers().stream()
                                                                    .anyMatch(
                                                                            o ->
                                                                                    o.id().equals(
                                                                                                    offer)))
                                            .findFirst()
                                            .orElseThrow(
                                                    () -> WorkRefusal.taskNotAnswered(task, offer));
                            final Offer bound =
                                    run.offers().stream()
                                            .filter(o -> o.id().equals(offer))
                                            .findFirst()
                                            .orElseThrow();
                            if (bound.status().equals("VOID")
                                    || bound.task() != null && !task.equals(bound.task())) {
                                throw WorkRefusal.taskNotAnswered(task, offer);
                            }
                            final NextStep.Binding binding = bound.binding();
                            // A result is never judged on a run, or saved beside a save, still in
                            // flight.
                            refuseInFlight(
                                    snapshot,
                                    Set.copyOf(binding.issues()),
                                    Set.of(run.story()),
                                    null);
                            final WorkRule.Result kind = binding.rule().result();
                            if (kind == null || !binding.role().handsBackResult()) {
                                throw HandedResults.refused(
                                        "offer: The offer's task hands back no result.");
                            }
                            // A result the forge refused for access stays to be handed in after the
                            // end,
                            // as does one whose end the store refused before it wrote anything.
                            if (!(bound.status().equals("STARTED")
                                            || bound.status().equals("ENDED")
                                                    && (waiting || unsaved(run, bound)))
                                    || run.stopped()
                                    || run.halted()) {
                                throw HandedResults.refused(
                                        "offer: The offer's task is not at work.");
                            }
                            final List<String> faults =
                                    HandedResults.check(kind, binding.issues(), handIn);
                            if (!faults.isEmpty()) {
                                write(
                                        session,
                                        HandedResults.with(
                                                snapshot,
                                                offer,
                                                HandedResults.REFUSED,
                                                task,
                                                binding.issues(),
                                                faults,
                                                ""));
                                throw HandedResults.refused(faults);
                            }
                            final String missing = handIn.missing();
                            if (missing != null) {
                                write(
                                        session,
                                        HandedResults.with(
                                                snapshot,
                                                offer,
                                                HandedResults.MISSING,
                                                task,
                                                binding.issues(),
                                                List.of(),
                                                missing));
                                final HandedResults.Answer missed =
                                        new HandedResults.Answer(
                                                HandedResults.MISSING,
                                                store.board(),
                                                binding.issues());
                                return () -> missed;
                            }
                            final Board board = store.board();
                            final String unowned = unowned(board, binding);
                            if (unowned != null) {
                                final List<String> said = List.of("issues: " + unowned);
                                final Snapshot refused =
                                        HandedResults.with(
                                                snapshot,
                                                offer,
                                                HandedResults.REFUSED,
                                                task,
                                                binding.issues(),
                                                said,
                                                "");
                                final Draft draft = new Draft(run);
                                for (final String id : binding.issues()) {
                                    final NextStep.Progress p = draft.progress.get(id);
                                    if (p != null) {
                                        draft.unavailable(
                                                id, p, "its result was refused: " + said.get(0));
                                    }
                                }
                                written(
                                        save(
                                                session,
                                                refused,
                                                draft,
                                                NOTHING,
                                                offer,
                                                board,
                                                null,
                                                Basis.none()));
                                throw HandedResults.refused(said);
                            }
                            final ResultFlight flight =
                                    saved(session, snapshot, offer, task, binding, kind, handIn);
                            return () -> land(flight);
                        })
                .get();
    }

    /**
     * A stage result's save marked pending in the record under its id, to be written outside the
     * record's lock.
     *
     * @param mark the record's mark
     * @param save the save's id
     * @param offer the offer
     * @param task the task
     * @param binding the offer's binding
     * @param before the offer's result before the save
     * @param result the result
     */
    private record ResultFlight(
            Mark mark,
            String save,
            String offer,
            String task,
            NextStep.Binding binding,
            HandedResults.@Nullable Handed before,
            StageResult result) {}

    /**
     * Marks a checked result pending in one short step of the record, so no stop between saves it
     * twice and a close waits for it; its save is written by {@link #land(ResultFlight)}.
     */
    private ResultFlight saved(
            final Session session,
            final Snapshot snapshot,
            final String offer,
            final String task,
            final NextStep.Binding binding,
            final WorkRule.Result kind,
            final HandedResults.HandIn handIn) {
        final StageResult result =
                HandedResults.result(
                        kind,
                        binding.state(),
                        binding.rule().done(),
                        // A readiness check's rule names its one from state, checked by WorkRules.
                        binding.rule().from().getFirst(),
                        java.util.Objects.requireNonNull(handIn.result()));
        final String save = UUID.randomUUID().toString();
        final HandedResults.@Nullable Handed before = snapshot.results().get(offer);
        session.preflight(HandedResults.pending(snapshot, offer, task, binding.issues(), save));
        final Mark mark =
                record.mark(
                        save,
                        now -> HandedResults.pending(now, offer, task, binding.issues(), save));
        return new ResultFlight(mark, save, offer, task, binding, before, result);
    }

    /** Writes a marked result through the store, holding no lock of the record, and settles it. */
    private HandedResults.Answer land(final ResultFlight flight) {
        final String offer = flight.offer();
        final String task = flight.task();
        final List<String> issues = flight.binding().issues();
        final Board saved;
        try {
            saved = store.saveResult(flight.result());
        } catch (final UnsupportedOperationException notOffered) {
            // A store that saves no result saved nothing, so the window may hand it in again.
            throw settledAfter(
                    notOffered,
                    () ->
                            settleResult(
                                    flight,
                                    now -> HandedResults.kept(now, offer, flight.before())));
        } catch (final StoreException.NoForgeAccess noAccess) {
            // Nothing was written; kept as waiting, so the task's end loses no result handed in
            // again once the owner account handed a token on.
            throw settledAfter(
                    noAccess,
                    () ->
                            settleResult(
                                    flight,
                                    now ->
                                            HandedResults.with(
                                                    now,
                                                    offer,
                                                    HandedResults.WAITING,
                                                    task,
                                                    issues,
                                                    List.of(),
                                                    "")));
        } catch (final StoreException.Busy | StoreException.StaleRevision notStarted) {
            // The store refuses these before it writes anything, so the window tries again later.
            throw settledAfter(
                    notStarted,
                    () ->
                            settleResult(
                                    flight,
                                    now -> HandedResults.kept(now, offer, flight.before())));
        } catch (final RuntimeException | Error failed) {
            final List<String> said =
                    List.of(
                            "result: The store did not save the result: "
                                    + HandedResults.bound(
                                            failed.getMessage() == null
                                                    ? failed.getClass().getSimpleName()
                                                    : failed.getMessage()));
            // An Error is thrown on as it is, after its result is recorded as refused.
            throw settledAfter(
                    failed instanceof Error ? failed : HandedResults.refused(said),
                    () ->
                            settleResult(
                                    flight,
                                    now ->
                                            HandedResults.with(
                                                    now,
                                                    offer,
                                                    HandedResults.REFUSED,
                                                    task,
                                                    issues,
                                                    said,
                                                    "")));
        }
        settleResult(
                flight,
                now ->
                        HandedResults.with(
                                now, offer, HandedResults.SAVED, task, issues, List.of(), ""));
        return new HandedResults.Answer(HandedResults.SAVED, saved, issues);
    }

    /**
     * Records a result save's outcome for its own offer alone, on the record as it stands now;
     * refused when the offer's pending result is not this save's. An outcome that cannot be
     * recorded ends the mark with the result left pending, for the repair at the next open, and is
     * told in one line.
     */
    private void settleResult(
            final ResultFlight flight, final java.util.function.UnaryOperator<Snapshot> outcome) {
        try {
            record.outcome(
                    flight.mark(),
                    now -> {
                        final HandedResults.Handed standing = now.results().get(flight.offer());
                        if (standing == null
                                || !HandedResults.PENDING.equals(standing.status())
                                || !flight.save().equals(standing.save())) {
                            throw new IllegalStateException("The result's mark is not this save's");
                        }
                        return outcome.apply(now);
                    });
        } catch (final RuntimeException | Error unsettled) {
            abandon(flight.mark(), unsettled);
            try {
                told.accept(
                        "The outcome of the result save "
                                + flight.save()
                                + " of offer "
                                + flight.offer()
                                + " was not recorded, so the next open refuses it: "
                                + unsettled.getMessage());
            } catch (final RuntimeException untold) {
                // The result stays pending for the repair; only the line is lost.
            }
            throw unsettled;
        }
    }

    /** Answers an offer's recorded outcome; a save in flight is refused as in flight. */
    private HandedResults.Answer replayed(
            final Session session,
            final Snapshot snapshot,
            final String offer,
            final HandedResults.Handed known) {
        switch (known.status()) {
            case HandedResults.SAVED, HandedResults.MISSING -> {
                return new HandedResults.Answer(known.status(), store.board(), known.issues());
            }
            case HandedResults.PENDING ->
                    // Its own save is still at the store, which alone settles it; a save a stopped
                    // planner left is refused by the repair at open.
                    throw HandedResults.refused(List.of(HandedResults.IN_FLIGHT));
            default -> throw HandedResults.refused(known.faults());
        }
    }

    private static void write(final Session session, final Snapshot snapshot) {
        session.preflight(snapshot);
        session.write(snapshot);
    }

    /**
     * Why the offer's issues can no longer take its result: no longer its own, out of its working
     * state, or taken over by a person; null when they all can.
     */
    private static @Nullable String unowned(final Board board, final NextStep.Binding binding) {
        java.util.Optional<String> held = board.holdsWork(binding.story());
        for (final String id : binding.issues()) {
            held = held.or(() -> board.holdsWork(id));
        }
        if (held.isPresent()) {
            return held.get();
        }
        if (!NextStep.owns(board, binding)) {
            return "The issues no longer belong to story "
                    + binding.story()
                    + " in repository "
                    + binding.repository()
                    + ".";
        }
        for (final String id : binding.issues()) {
            final Issue issue = board.issue(id).orElseThrow();
            if (!issue.state().equals(binding.state())) {
                return id + " no longer stands in " + binding.state() + ".";
            }
            if (issue.worker() == Worker.MANUAL) {
                return id + " is now worked by a person.";
            }
        }
        return null;
    }

    /**
     * Serializes a change of who works an issue with the runs' saves, refused as a move of the
     * issue is while a run with a started task owns it.
     *
     * @param issue the issue's id
     * @param save the save
     * @param <T> what the save answers
     * @return what the save answered
     * @throws WorkRefusal {@code WorkRunning} while a running work owns the issue or one of its
     *     sub-issues, which take its worker
     */
    public <T> T worker(final String issue, final Supplier<T> save) {
        Ids.issue(issue);
        return record.locked(
                session -> {
                    final Snapshot snapshot = read(session);
                    refuseOwned(snapshot, withSubIssues(store.board(), issue), Set.of());
                    return save.get();
                });
    }

    /** The issue and every issue below it, since a sub-issue takes its parent's worker. */
    private static Set<String> withSubIssues(final Board board, final String issue) {
        final Set<String> found = new HashSet<>(Set.of(issue));
        boolean grown = true;
        while (grown) {
            grown = false;
            for (final Issue each : board.issues()) {
                final String parent = each.parent();
                if (parent != null && found.contains(parent) && found.add(each.id())) {
                    grown = true;
                }
            }
        }
        return found;
    }

    /**
     * Answers what a batch of moves would do, read with the work last written and without waiting
     * for a save in flight, writing nothing; the confirmed save judges it again under the lock.
     *
     * @param moves the moves, in the order sent
     * @return the preview, its targets with the versions the board holds
     * @throws Moves.Refused when the save would refuse the batch itself
     */
    public MovePreview preview(final List<Moves.Move> moves) {
        final MovePreview quick = unchanging(moves);
        if (quick != null) {
            return quick;
        }
        final Snapshot snapshot = record.latest();
        return Moves.preview(store.board(), moves, owners(snapshot), held(snapshot));
    }

    /** The preview of moves that change nothing, read without the lock, or null for any other. */
    private @Nullable MovePreview unchanging(final List<Moves.Move> moves) {
        return unchanging(store.board(), moves, record.latest());
    }

    /**
     * The preview of a batch whose every item stands in its target already, with no reason to write
     * and no run at work over it, judged on the board held and the last generation written; any
     * other batch, or one these refuse, is answered null and judged under the lock as before.
     */
    private @Nullable MovePreview unchanging(
            final Board board, final List<Moves.Move> moves, final Snapshot snapshot) {
        try {
            final Moves.Runs runs = held(snapshot);
            final MovePreview preview = Moves.preview(board, moves, owners(snapshot), runs);
            return preview.warnings().isEmpty() && Moves.check(board, moves, runs).none()
                    ? preview
                    : null;
        } catch (final RuntimeException e) {
            return null;
        }
    }

    /**
     * What a confirmed save of moves answers: what the store save answered, and every run it
     * stopped with the tasks at work the window must end.
     *
     * @param saved what the store save answered
     * @param stopped each run the save stopped, with its tasks at work
     * @param <T> what the store save answers
     */
    public record MoveSaved<T>(T saved, List<StoppedRun> stopped) {

        /** Copies the list. */
        public MoveSaved {
            stopped = List.copyOf(stopped);
        }
    }

    /**
     * A run a confirmed save of moves stopped.
     *
     * @param story the run's story
     * @param run the run's id
     * @param tasks each task at work the window must end
     */
    public record StoppedRun(String story, String run, List<StoppedTask> tasks) {

        /** Copies the list. */
        public StoppedRun {
            tasks = List.copyOf(tasks);
        }
    }

    /**
     * A task at work a confirmed save of moves stopped the run of.
     *
     * @param task the task's name
     * @param container the container it runs in
     */
    public record StoppedTask(String task, String container) {}

    /**
     * Serializes a window move with report ownership checks and the store save itself, and saves
     * only the batch whose preview the person confirmed: computed again under the writer lock,
     * before anything is stopped or written, its digest must be the one sent. A confirmed save
     * stops each run with tasks at work over what moves, which the preview named and the digest
     * covers, and records the stop with the save; an unconfirmed one is refused while such a run
     * stands. Before the digest, the targets the preview finds now are checked against the versions
     * the person saw.
     *
     * @param moves the moves, in the order sent
     * @param basis the versions the person saw, the preview's targets among them
     * @param digest the preview's digest the window sends back
     * @param confirm whether the person confirmed the preview's warnings
     * @param repository the project's own repository, which the digest covers
     * @param branch its branch, or null
     * @param unchanged the answer to a batch that changes nothing, given the board it was judged on
     *     and the runs last written; it never reaches the store, since it runs outside the lock
     * @param save the save, given the runs the record holds under the lock
     * @param <T> what the save answers
     * @return what the save answered and the runs it stopped
     * @throws StoreException.BasisIncomplete for a target the basis names no version for
     * @throws StoreException.Changed for a target changed since the person saw it
     * @throws WorkRefusal {@code PreviewChanged} when the preview answers another digest now, or
     *     {@code WorkRunning} while a running work owns what moves and the save is not confirmed
     */
    public <T> MoveSaved<T> moves(
            final List<Moves.Move> moves,
            final Basis basis,
            final String digest,
            final boolean confirm,
            final String repository,
            final @Nullable String branch,
            final BiFunction<Board, Moves.Runs, T> unchanged,
            final Function<Moves.Runs, T> save) {
        final Snapshot latest = record.latest();
        final Board kept = store.board();
        final MovePreview quick = unchanging(kept, moves, latest);
        if (quick != null) {
            // Nothing is written, so no other save is waited for; what the person saw still holds.
            Edits.unchanged(kept, basis, quick.targets().keySet());
            if (!MessageDigest.isEqual(
                    quick.digest(repository, branch).getBytes(StandardCharsets.UTF_8),
                    digest.getBytes(StandardCharsets.UTF_8))) {
                throw WorkRefusal.previewChanged();
            }
            return new MoveSaved<>(unchanged.apply(kept, held(latest)), List.of());
        }
        return record.locked(
                session -> {
                    final Snapshot snapshot = read(session);
                    final Board board = store.board();
                    final MovePreview again =
                            Moves.preview(board, moves, owners(snapshot), held(snapshot));
                    Edits.unchanged(board, basis, again.targets().keySet());
                    final String now = again.digest(repository, branch);
                    if (!MessageDigest.isEqual(
                            now.getBytes(StandardCharsets.UTF_8),
                            digest.getBytes(StandardCharsets.UTF_8))) {
                        throw WorkRefusal.previewChanged();
                    }
                    final Set<String> affected = new HashSet<>(Moves.touched(board, moves));
                    final Set<String> stories = Moves.stories(board, moves);
                    if (!confirm) {
                        refuseOwned(snapshot, affected, stories);
                    }
                    final List<StoppedRun> stopped = new ArrayList<>();
                    final Snapshot stopping = stopOwners(snapshot, affected, stories, stopped);
                    // Judged on the record before this save stops a run, as its preview was.
                    final T saved = save.apply(held(snapshot));
                    final Board after = store.board();
                    affected.removeIf(id -> manual(board, id) || !stateChanged(board, after, id));
                    if (stopping != snapshot) {
                        session.write(stopping);
                    }
                    settled(session, stopping, affected);
                    return new MoveSaved<>(saved, stopped);
                });
    }

    /**
     * Answers what the correction of one warning would write, read under the writer lock, writing
     * nothing.
     *
     * @param asked the correction
     * @return the preview, its targets with the versions the board holds
     * @throws Moves.Refused when the correction is refused, as {@link Corrections} checks it
     * @throws UnsupportedOperationException when the store offers no correction of the warning
     */
    public Corrections.Preview correctionPreview(final Corrections.Asked asked) {
        return record.locked(session -> Corrections.check(store.board(), asked));
    }

    /**
     * Saves a correction whose preview the person confirmed: checked again on the board as it
     * stands, before anything is written, its digest must be the one sent, so a stale, changed or
     * repeated confirm writes nothing. A correction never writes a state, so it neither waits on
     * nor stops a run at work. It holds the record's lock while the store writes, unlike a run's
     * save: its check that no save in flight holds a target stands only while no run save can mark
     * one, and a mark of its own would be a new kind of entry in the record.
     *
     * @param asked the correction
     * @param basis the versions the person saw, its preview's targets among them
     * @param digest the preview's digest the window sends back
     * @param repository the project's own repository, which the digest covers
     * @param branch its branch, or null
     * @return the board after the save
     * @throws StoreException.BasisIncomplete for a target the basis names no version for
     * @throws StoreException.Changed for a target changed since the person saw it
     * @throws WorkRefusal {@code PreviewChanged} when the preview answers another digest now
     * @throws Moves.Refused as {@link #correctionPreview} refuses it
     * @throws UnsupportedOperationException when the store offers no correction of the warning
     */
    public Board correction(
            final Corrections.Asked asked,
            final Basis basis,
            final String digest,
            final String repository,
            final @Nullable String branch) {
        return record.locked(
                session -> {
                    final Board board = store.board();
                    // The item corrected is the target, so one changed since it was seen is
                    // refused as changed before its warning is judged on what it stands at now.
                    Edits.unchanged(board, basis, Set.of(asked.id()));
                    final Corrections.Preview preview = Corrections.check(board, asked);
                    Edits.unchanged(board, basis, preview.targets().keySet());
                    if (!MessageDigest.isEqual(
                            preview.digest(repository, branch).getBytes(StandardCharsets.UTF_8),
                            digest.getBytes(StandardCharsets.UTF_8))) {
                        throw WorkRefusal.correctionChanged();
                    }
                    final Set<String> targets = preview.targets().keySet();
                    refuseInFlight(read(session), targets, targets, null);
                    return written(
                            RunCommands.group(
                                    UUID.randomUUID().toString(),
                                    // A correction is the owner account's, no run's.
                                    "",
                                    preview.writes(),
                                    basis,
                                    Recheck.none()));
                });
    }

    /**
     * Writes an owner account's group through the queue, under the record's lock, and answers the
     * board after it; a refused command's refusal reaches the caller as the store gave it.
     */
    private Board written(final CommandGroup group) {
        return switch (queue.submit(group)) {
            case CommandOutcome.Done done -> {
                // Every command is written; a board the queue could not read is read here.
                final Board board = done.board();
                yield board != null ? board : store.board();
            }
            case CommandOutcome.Refused refused -> throw passedOn(refused.cause());
            // Held with a part written, the group stands in part for the owner account; held with
            // nothing written, it is refused before writing.
            case CommandOutcome.Held held ->
                    throw held.mayHaveWritten() ? heldInPart(held) : new StoreException.Busy("");
        };
    }

    /**
     * Answers the calls a repository's correction would make, read under the writer lock, writing
     * nothing: the repository's labels are read anew from its forge first, so no call is shown for
     * a label someone else deleted or created since they were last read.
     *
     * @param asked the correction
     * @return the calls with the revision they were read at
     * @throws Moves.Refused when the correction is refused, as {@link Corrections} checks it
     * @throws UnsupportedOperationException when the store keeps no repository's labels or offers
     *     no correction of the warning
     */
    public Corrections.Labels labelsPreview(final Corrections.Repository asked) {
        if (!store.keepsRepositoryLabels()) {
            throw new UnsupportedOperationException(PlanningStore.NOT_OFFERED);
        }
        return record.locked(
                session ->
                        Corrections.checkRepository(store.rereadLabels(asked.repository()), asked));
    }

    /**
     * Makes the calls of a repository's correction whose preview the person confirmed: checked
     * again under the writer lock on the repository's labels read anew from its forge, its digest
     * must be the one sent, so a stale or repeated confirm, once the warning is gone or the labels
     * changed since the preview, makes no call. It touches no item, so no run waits on it or is
     * stopped. It holds the record's lock while the calls are made, unlike a run's save, so a
     * second confirm reads the labels only after the first one's calls and is refused, not made
     * twice.
     *
     * @param asked the correction
     * @param digest the preview's digest the window sends back
     * @param repository the project's own repository, which the digest covers
     * @param branch its branch, or null
     * @return the board after the calls
     * @throws WorkRefusal {@code PreviewChanged} when the preview answers another digest now
     * @throws Moves.Refused as {@link #labelsPreview} refuses it
     * @throws UnsupportedOperationException when the store keeps no repository's labels or offers
     *     no correction of the warning
     */
    public Board labels(
            final Corrections.Repository asked,
            final String digest,
            final String repository,
            final @Nullable String branch) {
        if (!store.keepsRepositoryLabels()) {
            throw new UnsupportedOperationException(PlanningStore.NOT_OFFERED);
        }
        return record.locked(
                session -> {
                    final Corrections.Labels preview =
                            Corrections.checkRepository(
                                    store.rereadLabels(asked.repository()), asked);
                    if (!MessageDigest.isEqual(
                            preview.digest(repository, branch).getBytes(StandardCharsets.UTF_8),
                            digest.getBytes(StandardCharsets.UTF_8))) {
                        throw WorkRefusal.correctionChanged();
                    }
                    // Labels are no item of a story, so no save in flight holds them.
                    return written(
                            new CommandGroup(
                                    UUID.randomUUID().toString(),
                                    List.of(
                                            new Command.CorrectLabels(
                                                    "labels",
                                                    asked.repository(),
                                                    preview.calls()))));
                });
    }

    /**
     * Stops, as the story's Stop does, each run with tasks at work over what a confirmed batch
     * touches, collecting its tasks at work; nothing is written here.
     */
    private static Snapshot stopOwners(
            final Snapshot snapshot,
            final Set<String> issues,
            final Set<String> stories,
            final List<StoppedRun> stopped) {
        // A run is stopped on the record its save settles, never beside that save.
        refuseInFlight(snapshot, issues, stories, null);
        Snapshot next = snapshot;
        for (final Run run : snapshot.runs().values()) {
            final List<StoppedTask> tasks =
                    run.offers().stream()
                            .filter(o -> o.status().equals("STARTED"))
                            .map(
                                    o ->
                                            new StoppedTask(
                                                    String.valueOf(o.task()),
                                                    String.valueOf(o.containerId())))
                            .toList();
            if (tasks.isEmpty() || !owns(run, issues, stories)) {
                continue;
            }
            stopped.add(new StoppedRun(run.story(), run.id(), tasks));
            if (run.stopped()) {
                continue;
            }
            final Draft draft = new Draft(run);
            draft.stopped = true;
            draft.voidOffers();
            draft.progress.replaceAll((id, p) -> p.attention("STOPPED"));
            next = replace(next, draft.freeze());
        }
        return next;
    }

    /**
     * The runs whose started tasks own what a batch touches, as {@link #refuseOwned} reads them.
     */
    private static MovePreview.Running owners(final Snapshot snapshot) {
        return (issues, stories) -> {
            final List<MovePreview.Owned> owned = new ArrayList<>();
            for (final Run run : snapshot.runs().values()) {
                final List<String> active =
                        run.offers().stream()
                                .filter(o -> o.status().equals("STARTED"))
                                .map(o -> String.valueOf(o.task()))
                                .toList();
                if (!active.isEmpty() && owns(run, issues, stories)) {
                    owned.add(
                            new MovePreview.Owned(
                                    run.story(),
                                    run.id(),
                                    active,
                                    run.offers().stream()
                                            .filter(o -> o.status().equals("OFFERED"))
                                            .map(Offer::id)
                                            .toList()));
                }
            }
            return owned;
        };
    }

    /**
     * The runs of the record that work an issue now: a run of a story holding it, or one whose
     * offer names it, with a task offered or started. A run whose tasks all ended or stopped holds
     * nothing, so its story's issues can be moved by hand again.
     *
     * @param snapshot the work record
     * @return the runs that hold an issue now
     */
    public static Moves.Runs held(final Snapshot snapshot) {
        return (issue, stories) ->
                snapshot.runs().values().stream()
                        .anyMatch(
                                run ->
                                        run.offers().stream()
                                                .filter(WorkRun::working)
                                                .anyMatch(
                                                        o ->
                                                                stories.contains(run.story())
                                                                        || o.binding()
                                                                                .issues()
                                                                                .contains(issue)));
    }

    /** Whether an offer's task is offered or at work, so a hand move would stand beside it. */
    private static boolean working(final Offer offer) {
        return offer.status().equals("OFFERED") || offer.status().equals("STARTED");
    }

    private static boolean owns(
            final Run run, final Set<String> issues, final Set<String> stories) {
        return stories.contains(run.story())
                || run.offers().stream()
                        .flatMap(o -> o.binding().issues().stream())
                        .anyMatch(issues::contains);
    }

    /**
     * Refuses a write of an issue or story that a save in flight writes or whose run it holds: a
     * run's save, or a stage result's, which settles that run or result alone once its commands are
     * written. The refusal is the one a started task's work gives, and lasts only as long as the
     * save.
     */
    private static void refuseInFlight(
            final Snapshot snapshot,
            final Set<String> issues,
            final Set<String> stories,
            final @Nullable String named) {
        final Run run = inFlight(snapshot, issues, stories);
        if (run == null) {
            return;
        }
        final List<String> active =
                run.offers().stream()
                        .filter(o -> o.status().equals("STARTED"))
                        .map(o -> String.valueOf(o.task()))
                        .toList();
        throw named == null
                ? WorkRefusal.workRunning(run.story(), active)
                : WorkRefusal.workRunning(run.story(), active, named);
    }

    /**
     * The run whose save in flight writes one of the issues or stories or owns one, or the run of a
     * stage whose result's save in flight moves one of the issues; null when none does.
     */
    private static @Nullable Run inFlight(
            final Snapshot snapshot, final Set<String> issues, final Set<String> stories) {
        for (final Run run : snapshot.runs().values()) {
            final PendingSave pending = run.pending();
            if (pending != null
                    && (owns(run, issues, stories)
                            || pending.items().stream()
                                    .anyMatch(i -> issues.contains(i) || stories.contains(i)))) {
                return run;
            }
        }
        for (final Map.Entry<String, HandedResults.Handed> result : snapshot.results().entrySet()) {
            if (HandedResults.PENDING.equals(result.getValue().status())
                    && result.getValue().issues().stream().anyMatch(issues::contains)) {
                for (final Run run : snapshot.runs().values()) {
                    if (run.offers().stream().anyMatch(o -> o.id().equals(result.getKey()))) {
                        return run;
                    }
                }
            }
        }
        return null;
    }

    /** Whether a save changed an issue's state; a move to where it stood answers nothing. */
    private static boolean stateChanged(final Board before, final Board after, final String id) {
        final String was = before.issue(id).map(Issue::state).orElse(null);
        final String is = after.issue(id).map(Issue::state).orElse(null);
        return !java.util.Objects.equals(was, is);
    }

    /**
     * Clears the attention of issues a person moved by hand, since the move is their answer to it
     * and nothing else would ever clear it; a halted run whose issue a person moved is no longer
     * halted, since the refused save it held is moot once the person's own save stands.
     */
    private static void settled(
            final Session session, final Snapshot snapshot, final Set<String> moved) {
        Snapshot next = snapshot;
        for (final Run run : snapshot.runs().values()) {
            if (run.pending() != null) {
                continue;
            }
            final Draft draft = new Draft(run);
            boolean changed = false;
            if (run.halted() && moved.stream().anyMatch(run.issues()::containsKey)) {
                draft.halted = false;
                changed = true;
            }
            for (final String id : moved) {
                final NextStep.Progress p = draft.progress.get(id);
                if (p != null && (p.needsYou() || p.rounds().stopped())) {
                    draft.progress.put(
                            id,
                            new NextStep.Progress(
                                    new ReadingCycle.Rounds(
                                            p.rounds().rounds(), p.rounds().roundsMost(), false),
                                    p.findings(),
                                    false,
                                    "NONE",
                                    p.answer()));
                    changed = true;
                }
            }
            for (final Offer offer : List.copyOf(draft.offers)) {
                if (offer.status().equals("OFFERED")
                        && offer.startRefusal() != null
                        && offer.binding().issues().stream().anyMatch(moved::contains)
                        && offer.binding().issues().stream()
                                .noneMatch(id -> refusalAsks(draft.progress.get(id)))) {
                    // The refusal goes with the ask it raised, so a later refusal of the same
                    // offer is judged afresh and asks the person again.
                    draft.set(withoutStartRefusal(offer));
                    changed = true;
                }
            }
            if (changed) {
                next = replace(next, draft.freeze());
            }
        }
        if (next != snapshot) {
            session.write(next);
        }
    }

    /** Whether an issue asks the person because a Start of its work was refused. */
    private static boolean refusalAsks(final NextStep.@Nullable Progress progress) {
        return progress != null
                && progress.needsYou()
                && (progress.reason().equals(START_REFUSED)
                        || progress.reason().equals(PLACE_CHANGED));
    }

    /** The same offer with no refusal of its Start standing on it. */
    private static Offer withoutStartRefusal(final Offer offer) {
        return new Offer(
                offer.id(),
                offer.arguments(),
                offer.binding(),
                offer.status(),
                offer.task(),
                offer.containerId(),
                offer.machine(),
                offer.since(),
                offer.ended(),
                offer.fingerprint(),
                offer.following(),
                offer.deferred(),
                offer.refusal(),
                offer.reviewAnyway(),
                offer.reworkComment(),
                offer.rework(),
                null);
    }

    /** Refuses a change of a story or an issue that a run with a started task owns. */
    private static void refuseOwned(
            final Snapshot snapshot, final Set<String> issues, final Set<String> stories) {
        refuseOwned(snapshot, issues, stories, null);
    }

    /** Refuses as above, naming the issue a new story would take when one is given. */
    private static void refuseOwned(
            final Snapshot snapshot,
            final Set<String> issues,
            final Set<String> stories,
            final @Nullable String named) {
        refuseInFlight(snapshot, issues, stories, named);
        for (final Run run : snapshot.runs().values()) {
            final List<String> active =
                    run.offers().stream()
                            .filter(o -> o.status().equals("STARTED"))
                            .map(o -> String.valueOf(o.task()))
                            .toList();
            if (active.isEmpty()) {
                continue;
            }
            if (owns(run, issues, stories)) {
                throw named == null
                        ? WorkRefusal.workRunning(run.story(), active)
                        : WorkRefusal.workRunning(run.story(), active, named);
            }
        }
    }

    /**
     * A save's plan, made under the record's lock: either the run already written, since the save
     * writes nothing to the store, or the run marked pending and its commands still to be written.
     */
    private sealed interface Planned permits Written, Flight {}

    /**
     * A run written with nothing for the store.
     *
     * @param run the run as written
     */
    private record Written(Run run) implements Planned {}

    /**
     * A run marked pending under its group's id, its commands to be written outside the record's
     * lock and its outcome settled in a step of its own.
     *
     * @param mark the record's mark
     * @param pending the run's pending mark, naming the group
     * @param group the commands
     * @param owner whether the owner account asked for the save, else a task's event did
     * @param before the record before the judgment
     * @param snapshot the record as judged
     * @param draft the run as the save leaves it once written
     * @param eventOffer the offer whose event or choice the save writes, or null for a save no
     *     offer's event or choice asked for, which a refusal changes no offer of
     */
    private record Flight(
            Mark mark,
            PendingSave pending,
            CommandGroup group,
            boolean owner,
            Snapshot before,
            Snapshot snapshot,
            Draft draft,
            @Nullable String eventOffer)
            implements Planned {}

    /** The run a plan answers once its writing ended, landing a flight first. */
    private Run landed(final Planned planned) {
        return switch (planned) {
            case Written written -> written.run();
            case Flight flight -> land(flight);
        };
    }

    /** The run a plan for no store write answers, which never flies. */
    private static Run written(final Planned planned) {
        if (planned instanceof Written written) {
            return written.run();
        }
        throw new IllegalStateException("A save that writes nothing is in flight");
    }

    /**
     * Saves the owner account's writes, or a run's that write nothing, as {@link #save} does,
     * checked by the versions the owner account saw, with no rule read again by the store.
     */
    private Planned save(
            final Session session,
            final Snapshot snapshot,
            final Draft draft,
            final Writes writes,
            final String eventOffer,
            final Board base,
            final @Nullable String issue,
            final Basis seen) {
        return save(
                session,
                true,
                snapshot,
                snapshot,
                draft,
                writes,
                eventOffer,
                base,
                issue,
                seen,
                Recheck.none());
    }

    /**
     * Plans a run's save under the record's lock: a save that writes nothing is written at once;
     * any other is marked pending under its command group's id in one short step of the record, and
     * {@link #land} writes its commands outside the lock and settles it. Writes are checked by the
     * versions of their targets, the owner account's as it saw them and a task's as its judgment
     * read them, and by the recheck's rule.
     */
    private Planned save(
            final Session session,
            final boolean owner,
            final Snapshot before,
            final Snapshot snapshot,
            final Draft draft,
            final Writes writes,
            final @Nullable String eventOffer,
            final Board base,
            final @Nullable String issue,
            final Basis seen,
            final Recheck recheck) {
        final Run finalRun = draft.freeze();
        final Board planned = issue == null ? base : staged(base, writes.issues());
        try {
            preflight(session, replace(snapshot, finalRun), planned, issue);
        } catch (final WorkRefusal full) {
            if (!full(full)) {
                throw full;
            }
            keepNoNextStep(draft, eventOffer);
            preflight(session, replace(snapshot, draft.freeze()), planned, issue);
        }
        if (writes.none()) {
            final Run saved = draft.freeze();
            session.write(replace(snapshot, saved));
            tell(draft);
            return new Written(saved);
        }
        final CommandGroup group =
                RunCommands.group(UUID.randomUUID().toString(), draft.story, writes, seen, recheck);
        final PendingSave pending = PendingSave.of(group.id(), writes);
        draft.pending = pending;
        final Run marked = draft.freeze();
        preflight(session, replace(snapshot, marked), planned, issue);
        final Mark mark = record.mark(group.id(), now -> replace(now, marked));
        draft.pending = null;
        return new Flight(mark, pending, group, owner, before, snapshot, draft, eventOffer);
    }

    /** Whether a refusal is the record's being full. */
    private static boolean full(final WorkRefusal refusal) {
        return refusal.name().equals("WorkRecordUnavailable")
                && "The work record is full.".equals(refusal.parameters().get("reason"));
    }

    /** Keeps no next step of the run, so it fits a full record. */
    private static void keepNoNextStep(final Draft draft, final @Nullable String eventOffer) {
        draft.voidOffers();
        draft.compact(eventOffer);
        for (final String id : List.copyOf(draft.progress.keySet())) {
            draft.unavailable(
                    id, draft.progress(id), "the work record is full, so no next step is kept");
        }
    }

    /**
     * Writes a flight's commands, holding no lock of the record, and settles its outcome in a short
     * step: every command written saves the run. A refusal the store gives before writing anything
     * leaves an owner account's run as it stood before the judgment, and keeps a task's event with
     * the refusal under Needs you, for its next identical report to save again, with the offer's
     * result as the record before the judgment holds it. A group the stand-in queue holds unsent is
     * taken as refused before writing; any other failure halts the run, an {@code Error} too. Only
     * this save's mark is settled, on the record as it is then; a failure to settle it is added to
     * the store's refusal, which is thrown on as it is.
     *
     * <p>Three owner account writes still submit under the record's lock, each for a hold it needs
     * there: an idea, since its offer's record and its save stand as one against a resent hand-in,
     * which outside the lock would meet a save not yet ended; a correction, since its check that no
     * save in flight holds its targets stands only while no run save can mark one of them; and a
     * labels correction, since its confirm checked against the labels read anew stands only until a
     * second confirm of it reads them.
     */
    private Run land(final Flight flight) {
        final CommandOutcome outcome;
        try {
            outcome = queue.submit(flight.group());
        } catch (final RuntimeException | Error thrown) {
            throw settledAfter(thrown, () -> settle(flight, haltedBy(flight, thrown)));
        }
        switch (outcome) {
            case CommandOutcome.Done done -> {
                final Run saved = flight.draft().freeze();
                settle(flight, saved);
                tell(flight.draft());
                return saved;
            }
            case CommandOutcome.Refused refused -> {
                final Throwable cause = refused.cause();
                // A folded save refused part way, such as with a PartlySaved, names no command
                // written but may have written: it halts.
                if (refused.done().isEmpty()
                        && !refused.mayHaveWritten()
                        && cause instanceof StoreException unwritten) {
                    throw settledAfter(unwritten, () -> settleUnwritten(flight, unwritten));
                }
                throw settledAfter(cause, () -> settle(flight, haltedBy(flight, cause)));
            }
            case CommandOutcome.Held held -> {
                if (held.mayHaveWritten()) {
                    // Written in part, or perhaps, and held for the owner account: the run halts,
                    // as after a save that pushed only part.
                    final StoreException partly = heldInPart(held);
                    throw settledAfter(partly, () -> settle(flight, haltedBy(flight, partly)));
                }
                // Held with nothing written: refused before writing.
                final StoreException unsent = new StoreException.Busy("");
                throw settledAfter(unsent, () -> settleUnwritten(flight, unsent));
            }
        }
    }

    /**
     * A group the queue held after part of it was written, or may have been, as the save that
     * pushed only part.
     *
     * @param held what the queue answered
     * @return the failure, naming why the rest is held
     */
    static StoreException.PartlySaved heldInPart(final CommandOutcome.Held held) {
        return new StoreException.PartlySaved(
                "",
                List.of(),
                held.done().size()
                        + " of the save's commands were written, and the rest may stand or is held"
                        + " for the owner account: "
                        + held.why());
    }

    /**
     * Settles a save after its failure and answers the failure to throw on, as {@link #passedOn}
     * does: a failure to settle is added to it as suppressed, so the log and the caller have both;
     * an {@code Error} in settling is thrown instead, the failure added to it.
     *
     * @param failure what the store's save failed with
     * @param settling the settling of the save's mark
     * @return the failure to throw
     */
    static RuntimeException settledAfter(final Throwable failure, final Runnable settling) {
        try {
            settling.run();
        } catch (final Error broken) {
            if (broken != failure) {
                broken.addSuppressed(failure);
            }
            throw broken;
        } catch (final RuntimeException unsettled) {
            if (unsettled != failure) {
                failure.addSuppressed(unsettled);
            }
        }
        return passedOn(failure);
    }

    /**
     * A queue's refusal as it is thrown on: a runtime exception as it is and an {@code Error}
     * thrown as it is, anything else wrapped, so no cause is lost.
     *
     * @param cause what the store's call threw
     * @return the exception to throw
     */
    static RuntimeException passedOn(final Throwable cause) {
        if (cause instanceof RuntimeException runtime) {
            return runtime;
        }
        if (cause instanceof Error error) {
            throw error;
        }
        return new IllegalStateException(cause.getMessage(), cause);
    }

    /** Settles a flight nothing of which was written, as decision (b) says. */
    private void settleUnwritten(final Flight flight, final StoreException unwritten) {
        final String story = flight.draft().story;
        if (flight.owner()) {
            // Nothing was written: the owner account looks again, and the run stands as it was
            // before the judgment.
            settle(flight, flight.snapshot().runs().get(story), results -> results);
            return;
        }
        // Nothing was written: the task's event stands with the refusal, under Needs you, and the
        // task's next identical report saves it once more; the offer's result stands as before
        // the judgment, so a result handed in later is taken.
        final String offer =
                Objects.requireNonNull(flight.eventOffer(), "a task's event names its offer");
        final HandedResults.@Nullable Handed result = flight.before().results().get(offer);
        settle(
                flight,
                refusedEvent(
                        flight.snapshot(), flight.draft(), offer, unwritten, flight.group().id()),
                results -> {
                    if (result == null) {
                        results.remove(offer);
                    } else {
                        results.put(offer, result);
                    }
                    return results;
                });
    }

    /** The run halted by a failed save, its issues under Needs you and the refusal on its offer. */
    private static Run haltedBy(final Flight flight, final Throwable refused) {
        final Draft draft = flight.draft();
        draft.pending = null;
        draft.halted = true;
        draft.voidOffers();
        draft.progress.replaceAll((id, p) -> p.attention("SAVE_REFUSED"));
        draft.offers.replaceAll(
                o ->
                        !o.id().equals(flight.eventOffer())
                                ? o
                                : new Offer(
                                        o.id(),
                                        o.arguments(),
                                        o.binding(),
                                        o.status(),
                                        o.task(),
                                        o.containerId(),
                                        o.machine(),
                                        o.since(),
                                        o.ended(),
                                        o.fingerprint(),
                                        List.of(),
                                        List.of(),
                                        refused instanceof StoreException
                                                        || refused
                                                                instanceof
                                                                UnsupportedOperationException
                                                ? SaveFailure.of((RuntimeException) refused)
                                                : null,
                                        o.reviewAnyway(),
                                        o.reworkComment(),
                                        o.rework(),
                                        o.startRefusal()));
        return draft.freeze();
    }

    private void settle(final Flight flight, final @Nullable Run run) {
        settle(flight, run, results -> results);
    }

    /** Ends a mark whose outcome was not recorded; a failure to end it is added to that one. */
    private void abandon(final Mark mark, final Throwable unsettled) {
        try {
            record.abandon(mark);
        } catch (final RuntimeException | Error unended) {
            unsettled.addSuppressed(unended);
        }
    }

    /**
     * Records a flight's outcome for its own run alone, on the record as it stands now, so another
     * save's mark or run is never written back over. A run that no longer fits the record keeps no
     * next step, as a save does on a full record. An outcome that cannot be recorded at all ends
     * the mark with the run left pending, so the repair at the next open halts it as a crash would,
     * and is told in one line.
     */
    private void settle(
            final Flight flight,
            final @Nullable Run run,
            final java.util.function.UnaryOperator<Map<String, HandedResults.Handed>> results) {
        try {
            try {
                record.outcome(flight.mark(), now -> settled(now, flight, run, results));
            } catch (final WorkRefusal refusal) {
                if (run == null || !full(refusal)) {
                    throw refusal;
                }
                final Draft smaller = new Draft(run);
                keepNoNextStep(smaller, flight.eventOffer());
                final Run fits = smaller.freeze();
                record.outcome(flight.mark(), now -> settled(now, flight, fits, results));
            }
        } catch (final RuntimeException | Error unsettled) {
            abandon(flight.mark(), unsettled);
            try {
                told.accept(
                        "The outcome of save "
                                + flight.pending().id()
                                + " of story "
                                + flight.draft().story
                                + " was not recorded, so the next open halts its run: "
                                + unsettled.getMessage());
            } catch (final RuntimeException untold) {
                // The run stays pending for the repair; only the line is lost.
            }
            throw unsettled;
        }
    }

    /**
     * The record as it stands now with the flight's run settled; refused when the run's mark is not
     * this save's.
     */
    private static Snapshot settled(
            final Snapshot now,
            final Flight flight,
            final @Nullable Run run,
            final java.util.function.UnaryOperator<Map<String, HandedResults.Handed>> results) {
        final String story = flight.draft().story;
        final Run standing = now.runs().get(story);
        if (standing == null || !flight.pending().equals(standing.pending())) {
            throw new IllegalStateException("The run's mark is not this save's");
        }
        final Map<String, Run> runs = new LinkedHashMap<>(now.runs());
        if (run == null) {
            runs.remove(story);
        } else {
            runs.put(story, run);
        }
        return now.withRuns(now.generation() + 1, runs)
                .withResults(
                        now.generation() + 1, results.apply(new LinkedHashMap<>(now.results())));
    }

    /**
     * The run as it stood before a task's event, with the event recorded on its offer and the
     * store's refusal kept there, its issues under Needs you; nothing else the event's judgment
     * planned, such as a following task, is kept, since the store wrote nothing.
     */
    private static Run refusedEvent(
            final Snapshot snapshot,
            final Draft draft,
            final String eventOffer,
            final StoreException refused,
            final String group) {
        final Run before = Objects.requireNonNull(snapshot.runs().get(draft.story));
        final Offer accepted =
                draft.offers.stream()
                        .filter(o -> o.id().equals(eventOffer))
                        .findFirst()
                        .orElseThrow();
        final Draft kept = new Draft(before);
        kept.pending = null;
        kept.set(
                new Offer(
                        accepted.id(),
                        accepted.arguments(),
                        accepted.binding(),
                        accepted.status(),
                        accepted.task(),
                        accepted.containerId(),
                        accepted.machine(),
                        accepted.since(),
                        accepted.ended(),
                        accepted.fingerprint(),
                        List.of(),
                        List.of(),
                        SaveFailure.of(refused).naming(group),
                        accepted.reviewAnyway(),
                        accepted.reworkComment(),
                        accepted.rework(),
                        accepted.startRefusal()));
        accepted.binding()
                .issues()
                .forEach(
                        id ->
                                kept.progress.computeIfPresent(
                                        id, (key, p) -> p.attention("SAVE_REFUSED")));
        return kept.freeze();
    }

    /**
     * Says why each issue of a saved run could not take its next step, one line each; a line that
     * cannot be told is dropped, since the run it speaks of is already saved.
     */
    private void tell(final Draft draft) {
        draft.unavailable.forEach(
                (issue, why) -> {
                    try {
                        told.accept("Issue " + issue + " of story " + draft.story + ": " + why);
                    } catch (final RuntimeException untold) {
                        // The saved run stands; only its line is lost.
                    }
                });
    }

    private static void preflight(
            final Session session,
            final Snapshot snapshot,
            final Board board,
            final @Nullable String issue) {
        if (issue == null) {
            session.preflight(snapshot);
        } else {
            session.preflightIssue(snapshot, board, issue);
        }
    }

    /**
     * Whether an offer an end or a person's choice made, a reader or a rework, still waits for the
     * window to start: one offered beside work that already ran, or one an end lists. An order no
     * task of which ran yet, beside a build it carries for its readers, may still be replaced.
     */
    private static boolean followUpWaits(final Run run) {
        final Set<String> following =
                run.offers().stream()
                        .flatMap(o -> o.following().stream())
                        .collect(java.util.stream.Collectors.toSet());
        final boolean ran =
                run.offers().stream()
                        .anyMatch(o -> !o.status().equals("OFFERED") && !o.status().equals("VOID"));
        return run.offers().stream()
                .anyMatch(
                        o ->
                                o.status().equals("OFFERED")
                                        && (ran
                                                || following.contains(o.id())
                                                || run.placeOffers().contains(o.id())));
    }

    /**
     * The places the old run kept, carried into the run that replaces it with the author runs they
     * name, put void before the new run's offers in their old order where it does not hold them
     * yet, so the issue's work is still found where it waits, the new run's own builds stay the
     * last ones found, and no report can bind to those runs again. An issue its author builds
     * afresh keeps no place, since its work no longer waits there.
     */
    private static Map<String, IssuePlace> carriedPlaces(
            final @Nullable Run old, final List<Offer> offers, final Set<String> fresh) {
        if (old == null) {
            return Map.of();
        }
        final Map<String, IssuePlace> kept = new LinkedHashMap<>(old.places());
        kept.keySet().removeAll(fresh);
        final Set<String> named = new HashSet<>();
        kept.values().forEach(place -> named.addAll(place.authorRuns()));
        final List<Offer> carried = new ArrayList<>();
        for (final Offer run : old.offers()) {
            if (named.remove(run.id()) && offers.stream().noneMatch(o -> o.id().equals(run.id()))) {
                carried.add(run.status().equals("VOID") ? run : withStatus(run, "VOID"));
            }
        }
        // The record's check holds every author run a place names to an offer of its run.
        if (!named.isEmpty()) {
            throw new IllegalStateException("A kept place names an author run its run lacks.");
        }
        offers.addAll(0, carried);
        return kept;
    }

    /**
     * The build a hand reading reads, kept void in the run that replaces the old one, so a later
     * round or rework of that reading still finds it and no report can bind to it again.
     */
    private static java.util.Optional<Offer> carriedBuild(
            final @Nullable Run run, final String issueId) {
        return run == null
                ? java.util.Optional.empty()
                : run.offers().stream()
                        .filter(
                                o ->
                                        !o.binding().reading()
                                                && o.binding().issues().contains(issueId)
                                                && o.task() != null)
                        .reduce((left, right) -> right)
                        .map(o -> o.status().equals("VOID") ? o : withStatus(o, "VOID"));
    }

    /**
     * A reader a person starts for an issue standing where the reading rule takes from, reading the
     * issue's last build in the story's run where it landed; refused when none is known, since a
     * reader of nothing passes whatever stands.
     */
    private static NextStep.Planned handReader(
            final Board board,
            final String storyId,
            final String to,
            final WorkRule rule,
            final @Nullable Run run,
            final String issueId,
            final NextStep.Settings settings,
            final @Nullable String effort,
            final String comment) {
        final NextStep.Build built =
                run == null ? new NextStep.Build("", List.of()) : lastBuild(run, issueId);
        final Issue issue = board.issue(issueId).orElseThrow();
        if (rule.role().readsAnothersWork()) {
            // A reviewer runs only where the work waits, offered by the read of that place.
            throw WorkRefusal.notAllowed(
                    issue.state(), to, "The rule " + to + " reviews where the work waits.");
        }
        if (built.task().isEmpty() || built.landed().isEmpty()) {
            throw WorkRefusal.notAllowed(
                    issue.state(), to, "No landed build of " + issueId + " is known to read.");
        }
        return NextStep.task(
                board,
                storyId,
                issue,
                to,
                rule,
                settings,
                comment,
                "",
                built,
                new NextStep.Chosen(effort));
    }

    /** The build that last built the issue, whose landed work a new reading reads. */
    private static NextStep.Build lastBuild(final Run run, final String issueId) {
        return run.offers().stream()
                .filter(
                        o ->
                                !o.binding().reading()
                                        && o.binding().issues().contains(issueId)
                                        && o.task() != null)
                .reduce((left, right) -> right)
                .map(WorkRun::build)
                .orElse(new NextStep.Build("", List.of()));
    }

    /** An offer's task with where its work landed, if it has. */
    private static NextStep.Build build(final Offer offer) {
        final Ended ended = offer.ended();
        return new NextStep.Build(
                Objects.requireNonNullElse(offer.task(), ""),
                ended == null ? List.of() : ended.landed());
    }

    private Writes reconcile(
            final Board board,
            final Draft draft,
            final Writes first,
            final List<String> following) {
        if (draft.stopped || draft.halted) {
            return first;
        }
        final Map<String, String> targets = new LinkedHashMap<>(first.issues());
        for (final Offer old : List.copyOf(draft.offers)) {
            // An issue stopped for its changed place stays where it stands, still following.
            final List<String> resumed =
                    old.deferred().stream()
                            .filter(id -> !placeChanged(draft.progress.get(id)))
                            .toList();
            if (resumed.isEmpty()) {
                continue;
            }
            final Board staged = staged(board, targets);
            final NextStep.Binding binding =
                    new NextStep.Binding(
                            draft.story,
                            old.binding().repository(),
                            resumed,
                            old.binding().lead(),
                            old.binding().state(),
                            old.binding().mode(),
                            old.binding().rule());
            final NextStep.Result next =
                    NextStep.resume(
                            staged,
                            binding,
                            draft.progress,
                            draft.comment,
                            old.reviewAnyway(),
                            old.reworkComment(),
                            old.rework(),
                            build(old));
            draft.progress.putAll(next.progress());
            draft.unavailable.putAll(next.unavailable());
            targets.putAll(next.writes().issues());
            final List<String> ids = new ArrayList<>(old.following());
            for (final NextStep.Planned planned : next.tasks()) {
                final Offer offer = offer(planned, draft.story);
                draft.offers.add(offer);
                following.add(offer.id());
            }
            draft.set(
                    new Offer(
                            old.id(),
                            old.arguments(),
                            old.binding(),
                            old.status(),
                            old.task(),
                            old.containerId(),
                            old.machine(),
                            old.since(),
                            old.ended(),
                            old.fingerprint(),
                            ids,
                            old.deferred().stream()
                                    .filter(
                                            id ->
                                                    !resumed.contains(id)
                                                            || next.deferred().contains(id))
                                    .toList(),
                            old.refusal(),
                            old.reviewAnyway(),
                            old.reworkComment(),
                            old.rework(),
                            old.startRefusal()));
        }
        if (targets.equals(first.issues())) {
            return first;
        }
        return NextStep.writes(board, draft.story, targets);
    }

    /**
     * Checks a rework's settings against the Reworking rule for the issue's risk before anything is
     * saved; without that rule the rework cannot be built, so only no choice passes on to say so as
     * attention. No choice stays none, so the rework takes what the rule names for the risk.
     */
    private static NextStep.@Nullable Settings reworkSettings(
            final Board board,
            final WorkRule reading,
            final Issue issue,
            final NextStep.@Nullable Settings chosen) {
        final String blocked = reading.blocked();
        final WorkRule rule =
                blocked == null
                        ? null
                        : WorkRules.check(board.workflow(), board.work()).rules().get(blocked);
        if (blocked == null || rule == null) {
            if (chosen != null) {
                throw WorkRefusal.invalidSettings();
            }
            return null;
        }
        if (chosen == null) {
            return null;
        }
        return NextStep.Settings.of(rule, Choice.of(blocked, rule, board, List.of(issue)), chosen);
    }

    private NextStep.Result next(
            final Board board,
            final NextStep.Binding binding,
            final WorkEnds.Judgment judgment,
            final Map<String, NextStep.Progress> progress,
            final String comment,
            final NextStep.Build ended) {
        try {
            return NextStep.after(board, binding, judgment, progress, comment, ended);
        } catch (final WorkRefusal
                | IllegalArgumentException
                | UnsupportedOperationException cannotBuild) {
            return unavailable(binding, progress, cannotBuild);
        }
    }

    private NextStep.Result landedNext(
            final Board board,
            final NextStep.Binding binding,
            final Map<String, NextStep.Progress> progress,
            final String comment,
            final NextStep.Build build) {
        try {
            return NextStep.landed(board, binding, progress, comment, build);
        } catch (final WorkRefusal
                | IllegalArgumentException
                | UnsupportedOperationException cannotBuild) {
            return unavailable(binding, progress, cannotBuild);
        }
    }

    /** Every issue of the binding asks the person, saying the refusal that stopped its step. */
    private static NextStep.Result unavailable(
            final NextStep.Binding binding,
            final Map<String, NextStep.Progress> progress,
            final RuntimeException refused) {
        final Map<String, NextStep.Progress> attention = new LinkedHashMap<>(progress);
        final Map<String, String> unavailable = new LinkedHashMap<>();
        for (final String id : binding.issues()) {
            final NextStep.Progress p = attention.get(id);
            if (p != null) {
                attention.put(id, p.attention("NEXT_STEP_UNAVAILABLE"));
                unavailable.put(id, "its next step cannot be planned: " + NextStep.said(refused));
            }
        }
        return new NextStep.Result(NOTHING, List.of(), attention, List.of(), unavailable);
    }

    /**
     * Reads the record for a writer, naming readers of an older record their builds; a run pending
     * here is another save's in flight, which only that save settles, so nothing is halted.
     */
    private Snapshot read(final Session session) {
        Snapshot snapshot = session.read();
        for (final Run run : List.copyOf(snapshot.runs().values())) {
            if (run.pending() != null) {
                continue;
            }
            final Draft draft = new Draft(run);
            final Run named = readersNamingTheirBuilds(run, draft);
            if (named != run) {
                snapshot = replace(snapshot, named);
                session.write(snapshot);
                tell(draft);
            }
        }
        return snapshot;
    }

    /**
     * Names the build each unstarted reader of an older record reads, which that record did not
     * keep; a reader whose build is unknown is voided and its issue asks the person, since it would
     * read nothing.
     */
    private static Run readersNamingTheirBuilds(final Run run, final Draft draft) {
        boolean changed = false;
        for (int index = 0; index < run.offers().size(); index++) {
            final Offer reader = run.offers().get(index);
            final WorkOrder.Task arguments = reader.arguments();
            if (!reader.binding().role().readLanded()
                    || !reader.status().equals("OFFERED")
                    || !arguments.readLanded().isEmpty()) {
                continue;
            }
            final String issue = reader.binding().issues().getFirst();
            final String built =
                    run.offers().subList(0, index).stream()
                            .filter(
                                    o ->
                                            !o.binding().reading()
                                                    && o.binding().issues().contains(issue)
                                                    && o.task() != null)
                            .reduce((left, right) -> right)
                            .map(Offer::task)
                            .orElse(null);
            changed = true;
            if (built == null) {
                draft.set(withStatus(reader, "VOID"));
                final NextStep.Progress p = draft.progress.get(issue);
                if (p != null) {
                    draft.unavailable(
                            issue, p, "its reader was offered without the build it reads");
                }
                continue;
            }
            draft.set(
                    withArguments(
                            reader,
                            new WorkOrder.Task(
                                    arguments.repository(),
                                    arguments.issues(),
                                    arguments.role(),
                                    arguments.agent(),
                                    arguments.model(),
                                    arguments.effort(),
                                    arguments.mode(),
                                    arguments.prompt(),
                                    List.of(built),
                                    arguments.basis(),
                                    arguments.used(),
                                    arguments.result(),
                                    arguments.given(),
                                    arguments.work(),
                                    arguments.lead(),
                                    arguments.again(),
                                    arguments.place())));
        }
        return changed ? draft.freeze() : run;
    }

    private static Offer withStatus(final Offer o, final String status) {
        return new Offer(
                o.id(),
                o.arguments(),
                o.binding(),
                status,
                o.task(),
                o.containerId(),
                o.machine(),
                o.since(),
                o.ended(),
                o.fingerprint(),
                List.of(),
                List.of(),
                o.refusal(),
                o.reviewAnyway(),
                o.reworkComment(),
                o.rework(),
                o.startRefusal());
    }

    private static Offer withArguments(final Offer o, final WorkOrder.Task arguments) {
        return new Offer(
                o.id(),
                arguments,
                o.binding(),
                o.status(),
                o.task(),
                o.containerId(),
                o.machine(),
                o.since(),
                o.ended(),
                o.fingerprint(),
                o.following(),
                o.deferred(),
                o.refusal(),
                o.reviewAnyway(),
                o.reworkComment(),
                o.rework(),
                o.startRefusal());
    }

    /**
     * Drops every run whose story is no longer on the board and that has no task at work, so a
     * story saved later under that ID starts without it; a run with a task still at work is refused
     * instead, since dropping it would leave the task unstoppable.
     *
     * @param snapshot the record
     * @param board the board as read now
     * @return the record without those runs
     * @throws WorkRefusal WorkRunning, naming the story and its tasks, while such a task works
     */
    static Snapshot withoutGoneRuns(final Snapshot snapshot, final Board board) {
        final java.util.Set<String> shown =
                board.stories().stream()
                        .map(org.fuin.porelo.planner.store.Story::id)
                        .collect(java.util.stream.Collectors.toSet());
        final Map<String, Run> runs = new LinkedHashMap<>(snapshot.runs());
        for (final Run run : snapshot.runs().values()) {
            // A run whose save is in flight is settled by that save alone.
            if (shown.contains(run.story()) || run.pending() != null) {
                continue;
            }
            final List<String> working =
                    run.offers().stream()
                            .filter(o -> o.status().equals("STARTED"))
                            .map(o -> String.valueOf(o.task()))
                            .toList();
            if (!working.isEmpty()) {
                throw WorkRefusal.workRunning(run.story(), working);
            }
            runs.remove(run.story());
        }
        return runs.size() == snapshot.runs().size()
                ? snapshot
                : snapshot.withRuns(snapshot.generation(), runs);
    }

    /**
     * The record with one run replaced; a run whose save is in flight is refused as changed, so a
     * writer that would change it neither halts it nor passes its save.
     */
    private static Snapshot replace(final Snapshot snapshot, final Run run) {
        final Run standing = snapshot.runs().get(run.story());
        if (standing != null && standing.pending() != null) {
            throw WorkRefusal.runChanged(run.story(), "The run's save is still in flight.");
        }
        final Map<String, Run> runs = new LinkedHashMap<>(snapshot.runs());
        runs.put(run.story(), run);
        if (snapshot.generation() == Long.MAX_VALUE) {
            throw WorkRefusal.invalidSettings();
        }
        return snapshot.withRuns(snapshot.generation() + 1, runs);
    }

    /**
     * The issues the story's run raised with the reason a row's Start passes over: {@code
     * passOver}'s reason, or for a proposal, which writes nothing, any reason a Start may pass
     * over.
     */
    private static Set<String> raised(
            final Snapshot snapshot, final String story, final @Nullable PassOver passOver) {
        final Run run = snapshot.runs().get(story);
        if (run == null) {
            return Set.of();
        }
        final Set<String> raised = new HashSet<>();
        run.issues()
                .forEach(
                        (id, progress) -> {
                            if (progress.needsYou()
                                    && !progress.rounds().stopped()
                                    && (passOver == null
                                            ? PASSABLE.contains(progress.reason())
                                            : progress.reason().equals(passOver.reason()))) {
                                raised.add(id);
                            }
                        });
        return Set.copyOf(raised);
    }

    private static WorkProposal.RunningWork running(
            final Snapshot snapshot, final Set<String> again) {
        return new WorkProposal.RunningWork() {
            /** Supplies the issues a raised row's Start works again where they stand. */
            @Override
            public Set<String> again(final String story) {
                return again;
            }

            /** Supplies actual task activity rather than workflow-state guesses. */
            @Override
            public List<String> tasks(final String story) {
                final Run run = snapshot.runs().get(story);
                return run == null
                        ? List.of()
                        : run.offers().stream()
                                .filter(o -> o.status().equals("STARTED"))
                                .map(o -> String.valueOf(o.task()))
                                .toList();
            }

            /** Reuses a live main as the project's coordinator. */
            @Override
            public boolean coordinatorActive() {
                return snapshot.runs().values().stream()
                        .flatMap(run -> run.offers().stream())
                        .anyMatch(
                                offer ->
                                        offer.status().equals("STARTED") && offer.binding().lead());
            }
        };
    }

    private static void allowed(
            final Board board,
            final Snapshot snapshot,
            final String id,
            final String to,
            final boolean passHalted) {
        final Story story = WorkProposal.story(board, id);
        final Run run = snapshot.runs().get(id);
        if (run != null
                && !run.stopped()
                && (run.halted() && !passHalted
                        || run.issues().entrySet().stream()
                                .anyMatch(
                                        p ->
                                                p.getValue().rounds().stopped()
                                                        && !manual(board, p.getKey())))) {
            throw WorkRefusal.notAllowed(
                    story.state(), to, "The run needs attention before more work.");
        }
        if (run == null
                && (board.work().rules().containsKey(story.state()) && !heldByManual(board, story)
                        || board.issues().stream()
                                .anyMatch(
                                        i ->
                                                i.worker() != Worker.MANUAL
                                                        && (story.issues().contains(i.id())
                                                                || i.parent() != null
                                                                        && story.issues()
                                                                                .contains(
                                                                                        i.parent()))
                                                        && board.work()
                                                                .rules()
                                                                .containsKey(i.state())))) {
            throw WorkRefusal.notAllowed(
                    story.state(),
                    to,
                    "An issue in a working state is worked outside this planner.");
        }
    }

    /** Whether a person works the issue; an issue the board lacks is not theirs. */
    private static boolean manual(final Board board, final String issue) {
        return board.issue(issue).map(i -> i.worker() == Worker.MANUAL).orElse(false);
    }

    /**
     * Whether the story stands in its state because an issue a person works stands there; a state a
     * person set on the story stands over its issues, so nothing holds it.
     */
    private static boolean heldByManual(final Board board, final Story story) {
        return board.issues().stream()
                .anyMatch(
                        i ->
                                i.worker() == Worker.MANUAL
                                        && i.state().equals(story.state())
                                        && (story.issues().contains(i.id())
                                                || i.parent() != null
                                                        && story.issues().contains(i.parent())));
    }

    /** Refuses a Start replacing the run's unstarted offers without naming that run. */
    private static void replaced(
            final String story, final @Nullable Run run, final @Nullable String replaces) {
        if (replaces == null
                && run != null
                && !run.stopped()
                && !run.halted()
                && run.offers().stream().anyMatch(o -> o.status().equals("OFFERED"))) {
            throw WorkRefusal.runChanged(
                    story, "The run holds offers not started yet, which a Start must name.");
        }
    }

    /**
     * Refuses a Start naming a run that is not the one standing, before anything else about the run
     * is judged, so another run's id always reads as a changed run.
     */
    private static void standing(final String story, final @Nullable Run run, final Start start) {
        final String replaces = start.replaces();
        if (replaces != null && (run == null || !run.id().equals(replaces))) {
            throw WorkRefusal.runChanged(
                    story, "The run a Start replaces is not the one standing.");
        }
        final PassOver passOver = start.passOver();
        if (passOver != null && (run == null || !run.id().equals(passOver.run()))) {
            throw WorkRefusal.runChanged(story, "The run passed over is not the one standing.");
        }
    }

    /**
     * Refuses a Start that does not pass over the reason the run raised, or a pass-over that does
     * not name that issue's reason or would pass over another reason of the run.
     */
    private static void passedOver(
            final String story, final @Nullable Run run, final @Nullable PassOver passOver) {
        if (run == null) {
            return;
        }
        if (passOver == null) {
            // A stopped run's STOPPED reasons count: only a pass-over answers them.
            if (run.issues().values().stream().anyMatch(NextStep.Progress::needsYou)) {
                throw WorkRefusal.runChanged(story, "The run needs attention a Start must name.");
            }
            return;
        }
        final NextStep.Progress progress = run.issues().get(passOver.issue());
        if (progress == null
                || !progress.needsYou()
                || !progress.reason().equals(passOver.reason())) {
            throw WorkRefusal.runChanged(story, "The reason passed over changed.");
        }
        // A stop or a refused save raises every issue of the run with the same reason at once.
        if (run.issues().entrySet().stream()
                .anyMatch(
                        e ->
                                !e.getKey().equals(passOver.issue())
                                        && e.getValue().needsYou()
                                        && !e.getValue().reason().equals(passOver.reason()))) {
            throw WorkRefusal.runChanged(story, "Another issue of the run needs attention.");
        }
    }

    /**
     * Refuses a Start passing over any reason while an ended task of the run reports commits
     * unpushed for an issue the run raised, which only that task can push, so no new builder starts
     * beside work that would be lost; a stop that renamed a failure does not hide them.
     */
    private static void unpushedLeft(
            final Board board,
            final String story,
            final String to,
            final @Nullable Run run,
            final @Nullable PassOver passOver) {
        if (run == null || passOver == null) {
            return;
        }
        for (final Map.Entry<String, NextStep.Progress> raised : run.issues().entrySet()) {
            if (!raised.getValue().needsYou()) {
                continue;
            }
            Offer last = null;
            for (final Offer offer : run.offers()) {
                if (offer.ended() != null && offer.binding().issues().contains(raised.getKey())) {
                    last = offer;
                }
            }
            final Ended ended = last == null ? null : last.ended();
            // A reader delivers only its answer; what it checked out to read is no work to lose.
            if (last != null
                    && ended != null
                    && ended.unpushed() > 0
                    && !last.binding().reading()) {
                throw WorkRefusal.notAllowed(
                        WorkProposal.story(board, story).state(),
                        to,
                        "An ended task left commits unpushed, which only it can push.");
            }
        }
    }

    /**
     * Refuses a start into the building rule whose offered issues wait, as the tracker answers now,
     * on an issue not done: a wait the board holds as done may have opened again since it was read.
     * Other work takes a waiting issue like any other, so it asks the tracker nothing.
     */
    private void waitsNow(
            final Board board, final String story, final String to, final WorkProposal proposal) {
        if (!WorkProposal.blockersHold(board, to)) {
            return;
        }
        final List<String> offered = new ArrayList<>();
        proposal.repositories().forEach(repository -> offered.addAll(repository.issues()));
        final Map<String, List<Wait>> now = store.waitsNow(offered);
        final List<Issue> waiting = new ArrayList<>();
        for (final String id : offered) {
            final Issue issue = board.issue(id).orElseThrow();
            final List<Wait> named = now.get(id);
            // An issue the tracker named no waits for is judged by what the board holds.
            final List<Wait> waits = named == null ? issue.waitsOn() : named;
            if (waits.stream().anyMatch(Wait::holds)) {
                waiting.add(issue.withWaits(waits));
            }
        }
        if (!waiting.isEmpty()) {
            throw WorkRefusal.notAllowed(
                    WorkProposal.story(board, story).state(),
                    to,
                    WorkProposal.waiting(board, waiting));
        }
    }

    /**
     * The issues a Start of the story's own issues into the building rule holds back by a blocker
     * or a wait not done, among those standing where the rule takes from that an agent works: never
     * one a warning holds, which the person clears, and none under any other rule, which takes a
     * blocked issue like any other.
     */
    private static List<String> heldBack(
            final Board board, final String to, final WorkProposal proposal) {
        final WorkRule rule = building(board, to);
        if (rule == null) {
            return List.of();
        }
        return proposal.held().stream()
                .map(WorkProposal.Held::issue)
                .filter(id -> board.twice(id).isEmpty() && board.holdsWork(id).isEmpty())
                .flatMap(id -> board.issue(id).stream())
                .filter(
                        issue ->
                                rule.from().contains(issue.state())
                                        && issue.worker() != Worker.MANUAL
                                        && !WorkProposal.openBlockers(board, issue).isEmpty())
                .map(Issue::id)
                .distinct()
                .toList();
    }

    /**
     * What a Start naming issues keeps held back of the run it replaces: into the same working
     * state, the issues it held back that the Start does not name; it holds back nothing new.
     */
    private static List<String> stillHeld(
            final @Nullable Run earlier, final String to, final List<String> named) {
        if (earlier == null || !earlier.to().equals(to)) {
            return List.of();
        }
        return earlier.heldBack().stream().filter(id -> !named.contains(id)).toList();
    }

    /**
     * The rule of the working state {@code to} when it is the building rule, the one blockers hold
     * work back from, which hands back no result and is no reading's rework; null for any other.
     */
    private static @Nullable WorkRule building(final Board board, final String to) {
        final WorkRule rule = WorkRules.check(board.workflow(), board.work()).rules().get(to);
        return rule != null
                        && rule.result() == null
                        && rule.blocked() == null
                        && WorkProposal.blockersHold(board, to)
                ? rule
                : null;
    }

    private static Offer offer(final NextStep.Planned planned, final String story) {
        final WorkOrder.Task task = planned.task();
        if (task.agent().length() > 128
                || task.model().length() > 128
                || task.mode().length() > 128) {
            throw WorkRefusal.invalidSettings();
        }
        return new Offer(
                UUID.randomUUID().toString(),
                task,
                new NextStep.Binding(
                        story,
                        task.repository(),
                        task.issues(),
                        task.lead(),
                        planned.state(),
                        task.mode(),
                        planned.rule()),
                "OFFERED",
                null,
                null,
                null,
                null,
                null,
                "",
                List.of(),
                List.of(),
                null);
    }

    private Answer answer(final Run run, final List<String> identities) {
        final Board board = store.board();
        return new Answer(board.revision().value(), run, offered(run, identities), board);
    }

    private static List<Offered> offered(final Run run, final List<String> identities) {
        if (run.stopped() || run.halted()) {
            return List.of();
        }
        return run.offers().stream()
                .filter(o -> identities.contains(o.id()) && o.status().equals("OFFERED"))
                .map(o -> new Offered(o.id(), o.arguments()))
                .toList();
    }

    private static Board staged(final Board board, final Map<String, String> targets) {
        return new Board(
                board.revision(),
                board.workflow(),
                board.stories(),
                board.issues().stream()
                        .map(
                                i ->
                                        new Issue(
                                                i.id(),
                                                i.repository(),
                                                i.title(),
                                                targets.getOrDefault(i.id(), i.state()),
                                                i.size(),
                                                i.blockedBy(),
                                                i.parent(),
                                                i.link(),
                                                i.text(),
                                                i.worker(),
                                                i.risk(),
                                                i.estimate(),
                                                i.minutes(),
                                                i.priority(),
                                                i.warnings(),
                                                i.waitsOn()))
                        .toList(),
                board.work(),
                board.repositories(),
                board.faults(),
                board.owner(),
                board.unlisted());
    }

    /** Checks opaque and display labels before they can occur in any refusal. */
    public static void checkedReport(final String task, final Report report) {
        checkedMachine(report.machine());
        try {
            if (!Ids.isTask(task)
                    || !(report.event() == Event.REFUSED
                            ? report.container().isEmpty()
                            : Ids.isTask(report.container()))
                    || !UUID.fromString(report.offer()).toString().equals(report.offer())
                    || (report.event() == Event.ENDED) != (report.ended() != null)
                    || (report.event() == Event.LANDED) == report.landed().isEmpty()
                    || (report.event() == Event.REFUSED) != (report.refusal() != null)) {
                throw WorkRefusal.invalidSettings();
            }
            final StartRefusal refusal = report.refusal();
            if (refusal != null) {
                checkedStartRefusal(refusal);
            }
            report.landed().forEach(WorkRun::checkedLanding);
            final Ended ended = report.ended();
            if (ended != null) {
                ended.landed().forEach(WorkRun::checkedLanding);
                if (ended.at().length() > 128
                        || !AT.matcher(ended.at()).matches()
                        || ended.unpushed() < 0
                        || ended.answer().getBytes(StandardCharsets.UTF_8).length
                                > WorkEnds.MOST_ANSWER) {
                    throw WorkRefusal.invalidSettings();
                }
                OffsetDateTime.parse(ended.at().replaceFirst("(\\.[0-9]{9})[0-9]+", "$1"));
                words(ended.why());
            }
        } catch (final IllegalArgumentException | DateTimeParseException invalid) {
            throw WorkRefusal.invalidSettings();
        }
    }

    /**
     * Answers whether a landing is one Porelo may report: a branch and a remote-tracking name that
     * are branch names, a ref under {@code refs/} by the same rules, and a commit of 40 or 64
     * lowercase hex digits.
     *
     * @param landing the landing
     * @return whether each of its names is well formed
     */
    public static boolean isLanding(final NextStep.Landing landing) {
        return fault(landing) == null;
    }

    private static final Pattern REFUSAL_NAME = Pattern.compile("[A-Za-z0-9]{1,64}");

    /**
     * Checks what Porelo's refusal of a Start carries against its bounds, naming no part of it in
     * the refusal.
     *
     * @param refusal the refusal
     * @throws WorkRefusal {@code InvalidSettings} when a bound is exceeded or a control character
     *     stands in a word; only the detail may hold line breaks
     */
    public static void checkedStartRefusal(final StartRefusal refusal) {
        if (!REFUSAL_NAME.matcher(refusal.name()).matches()
                || refusal.parameters().size() > 16
                || !fits(refusal.detail(), 0, 1000, true)) {
            throw WorkRefusal.invalidSettings();
        }
        for (final Map.Entry<String, String> parameter : refusal.parameters().entrySet()) {
            if (!fits(parameter.getKey(), 1, 64, false)
                    || !fits(parameter.getValue(), 0, 500, false)) {
                throw WorkRefusal.invalidSettings();
            }
        }
    }

    private static boolean fits(
            final String text, final int least, final int most, final boolean lines) {
        final int length = text.codePointCount(0, text.length());
        return length >= least
                && length <= most
                && text.codePoints()
                        .noneMatch(
                                point ->
                                        !(lines && (point == '\n' || point == '\r'))
                                                && (Character.isISOControl(point)
                                                        || point == 0x2028
                                                        || point == 0x2029));
    }

    /** Refuses a malformed landing, naming the part that is malformed but never its value. */
    private static void checkedLanding(final NextStep.Landing landing) {
        final String part = fault(landing);
        if (part != null) {
            throw WorkRefusal.invalidSettings("The landing's " + part + " is malformed.");
        }
    }

    private static @Nullable String fault(final NextStep.Landing landing) {
        if (!isBranch(landing.branch())) {
            return "branch";
        }
        if (!landing.ref().startsWith("refs/") || !isBranch(landing.ref())) {
            return "ref";
        }
        if (!isBranch(landing.remote())) {
            return "remote";
        }
        return COMMIT.matcher(landing.commit()).matches() ? null : "commit";
    }

    /**
     * Answers whether a text is a Git ref name as Porelo may report one: 1 to 255 characters
     * (Unicode code points), none of them a control character, a space, a line or paragraph
     * separator or any of {@code ~^:?*[\}, without "..", not starting with - or /, not ending with
     * / or .lock. Letters beyond ASCII are names Git takes.
     *
     * @param name the text
     * @return whether it is a ref name
     */
    public static boolean isBranch(final String name) {
        return !name.isEmpty()
                && name.codePointCount(0, name.length()) <= 255
                && !name.contains("..")
                && !name.startsWith("-")
                && !name.startsWith("/")
                && !name.endsWith("/")
                && !name.endsWith(".lock")
                && name.codePoints().noneMatch(WorkRun::refusedInName);
    }

    /** A line break of any kind would put words on a line of their own in a reader's order. */
    private static boolean refusedInName(final int point) {
        return point <= 0x20
                || point >= 0x7F && point <= 0x9F
                || point == 0x2028
                || point == 0x2029
                || "~^:?*[\\".indexOf(point) >= 0;
    }

    /** Checks a verbatim display name without interpreting it as a machine address. */
    public static void checkedMachine(final String machine) {
        final int length = machine.codePointCount(0, machine.length());
        if (length < 1
                || length > 128
                || machine.codePoints()
                        .anyMatch(
                                point ->
                                        Character.isISOControl(point)
                                                || point == 0x2028
                                                || point == 0x2029)) {
            throw WorkRefusal.invalidSettings();
        }
    }

    private static void words(final String words) {
        if (words.codePointCount(0, words.length()) > WorkEnds.MOST_TEXT) {
            throw WorkRefusal.invalidSettings();
        }
    }

    private static WorkRefusal conflict(final String task, final Event event, final String reason) {
        return WorkRefusal.eventConflict(task, event.name(), reason);
    }

    private static String fingerprint(final Report report) {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            final Ended end = report.ended();
            final List<String> fields =
                    end == null
                            ? List.of(report.event().name(), report.container(), report.machine())
                            : new ArrayList<>(
                                    List.of(
                                            report.event().name(),
                                            report.container(),
                                            report.machine(),
                                            end.at(),
                                            Boolean.toString(end.finished()),
                                            end.why(),
                                            end.answer(),
                                            Integer.toString(end.unpushed())));
            if (end != null) {
                // Only an end with a landing adds it, so an end recorded before keeps its print.
                for (final NextStep.Landing landing : end.landed()) {
                    fields.addAll(
                            List.of(
                                    landing.branch(),
                                    landing.ref(),
                                    landing.remote(),
                                    landing.commit()));
                }
                // Only a declared outcome adds it, so an end recorded before keeps its print.
                final WorkEnds.Declared declared = end.outcome();
                if (declared != null) {
                    fields.addAll(List.of("outcome", declared.name()));
                }
            }
            for (final String field : fields) {
                final byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
                digest.update(
                        java.nio.ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (final NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is required", unavailable);
        }
    }

    private static void replayFailure(final Offer offer) {
        final SaveFailure failure = offer.refusal();
        if (failure == null) {
            return;
        }
        throw switch (failure.name()) {
            case "NoSuchStory" -> new StoreException.NoSuchStory(failure.subject());
            case "NoSuchIssue" -> new StoreException.NoSuchIssue(failure.subject());
            case "NotOffered" -> new UnsupportedOperationException("This store does not save work");
            case "StaleRevision" -> new StoreException.StaleRevision(failure.subject());
            case "Busy" -> new StoreException.Busy(failure.subject());
            // A group refused after an earlier command of it was written halts its run, so a
            // refusal before writing reaches a halted run too.
            case "Changed" -> new StoreException.Changed(List.of(failure.subject()));
            case "BasisIncomplete" ->
                    new StoreException.BasisIncomplete(List.of(failure.subject()));
            case "NotSavable" ->
                    new StoreException.NotSavable(
                            failure.subject(), "The state could not be saved.");
            case "PartlySaved" ->
                    new StoreException.PartlySaved(
                            failure.subject(),
                            failure.pushed(),
                            "A repository could not be saved.");
            case "CredentialRefused" -> new StoreException.CredentialRefused(failure.subject());
            case "NoForgeAccess" ->
                    new StoreException.NoForgeAccess(failure.subject(), failure.right());
            case "Unreachable" ->
                    new StoreException.Unreachable(
                            failure.subject(), "The repository could not be reached.");
            case "WorkflowMissing" -> new StoreException.WorkflowMissing(failure.subject());
            default ->
                    new StoreException.Unreadable(
                            failure.subject(), "The repository could not be read.");
        };
    }

    private static final class Draft {
        private final Run original;
        private final String story;
        private final String comment;
        private final List<Offer> offers;
        private final Map<String, NextStep.Progress> progress;
        private final Map<String, String> unavailable = new LinkedHashMap<>();
        private final Map<String, IssuePlace> places;
        private final List<String> placeOffers;
        private final List<WorkProposal.Repository> repositories;
        private final List<String> heldBack;
        private boolean stopped;
        private boolean halted;
        private @Nullable PendingSave pending;

        private Draft(final Run run) {
            original = run;
            story = run.story();
            comment = run.comment();
            offers = new ArrayList<>(run.offers());
            progress = new LinkedHashMap<>(run.issues());
            places = new LinkedHashMap<>(run.places());
            placeOffers = new ArrayList<>(run.placeOffers());
            repositories = new ArrayList<>(run.repositories());
            heldBack = new ArrayList<>(run.heldBack());
            stopped = run.stopped();
            halted = run.halted();
            pending = run.pending();
        }

        private void set(final Offer offer) {
            offers.replaceAll(old -> old.id().equals(offer.id()) ? offer : old);
        }

        private NextStep.Progress progress(final String id) {
            return java.util.Objects.requireNonNull(
                    progress.get(id), "Owned issues have recorded progress");
        }

        /** Asks the person about the issue, keeping the one line that says why. */
        private void unavailable(final String id, final NextStep.Progress from, final String why) {
            progress.put(id, from.attention("NEXT_STEP_UNAVAILABLE"));
            unavailable.put(id, why);
        }

        private void setFollowing(final String offer, final List<String> following) {
            offers.replaceAll(
                    o ->
                            !o.id().equals(offer)
                                    ? o
                                    : new Offer(
                                            o.id(),
                                            o.arguments(),
                                            o.binding(),
                                            o.status(),
                                            o.task(),
                                            o.containerId(),
                                            o.machine(),
                                            o.since(),
                                            o.ended(),
                                            o.fingerprint(),
                                            following,
                                            o.deferred(),
                                            o.refusal(),
                                            o.reviewAnyway(),
                                            o.reworkComment(),
                                            o.rework(),
                                            o.startRefusal()));
        }

        /**
         * Drops what an ended offer no longer needs; current names the offer just judged, if any.
         */
        private void compact(final @Nullable String current) {
            final @Nullable Offer latest =
                    current == null
                            ? null
                            : offers.stream()
                                    .filter(o -> o.id().equals(current))
                                    .findFirst()
                                    .orElseThrow();
            offers.replaceAll(
                    o -> {
                        if (o.status().equals("OFFERED") || o.status().equals("STARTED")) {
                            return o;
                        }
                        final WorkOrder.Task arguments = o.arguments();
                        final Ended end = o.ended();
                        final boolean olderReading =
                                latest != null
                                        && !o.id().equals(current)
                                        && reads(o)
                                        && o.binding().issues().stream()
                                                .anyMatch(latest.binding().issues()::contains)
                                        && reads(latest)
                                        && latest.ended() != null;
                        return new Offer(
                                o.id(),
                                new WorkOrder.Task(
                                        arguments.repository(),
                                        arguments.issues(),
                                        arguments.role(),
                                        arguments.agent(),
                                        arguments.model(),
                                        arguments.effort(),
                                        arguments.mode(),
                                        "",
                                        arguments.readLanded(),
                                        arguments.basis(),
                                        arguments.used(),
                                        arguments.result(),
                                        List.of(),
                                        arguments.work(),
                                        arguments.lead(),
                                        arguments.again(),
                                        arguments.place()),
                                o.binding(),
                                o.status(),
                                o.task(),
                                o.containerId(),
                                o.machine(),
                                o.since(),
                                end == null || !olderReading
                                        ? end
                                        : new Ended(
                                                end.at(),
                                                end.finished(),
                                                end.why(),
                                                "",
                                                end.unpushed(),
                                                end.landed(),
                                                end.outcome()),
                                o.fingerprint(),
                                o.following(),
                                o.deferred(),
                                o.refusal(),
                                o.reviewAnyway(),
                                o.reworkComment(),
                                o.rework(),
                                o.startRefusal());
                    });
        }

        private void voidOffers() {
            offers.replaceAll(
                    o ->
                            !o.status().equals("OFFERED")
                                    ? o
                                    : new Offer(
                                            o.id(),
                                            o.arguments(),
                                            o.binding(),
                                            "VOID",
                                            o.task(),
                                            o.containerId(),
                                            o.machine(),
                                            o.since(),
                                            o.ended(),
                                            o.fingerprint(),
                                            List.of(),
                                            List.of(),
                                            o.refusal(),
                                            o.reviewAnyway(),
                                            o.reworkComment(),
                                            o.rework(),
                                            o.startRefusal()));
        }

        private Run freeze() {
            return new Run(
                    original.id(),
                    story,
                    original.to(),
                    original.mode(),
                    comment,
                    original.coordinator(),
                    original.readingRounds(),
                    original.issueReadingRounds(),
                    repositories,
                    original.since(),
                    offers,
                    progress,
                    stopped,
                    halted,
                    pending,
                    places,
                    placeOffers,
                    heldBack);
        }
    }
}
