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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Ids;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.PlanningStore;
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.planner.store.Story;
import org.fuin.porelo.planner.store.WorkRule;
import org.fuin.porelo.planner.store.Worker;
import org.fuin.porelo.planner.store.Writes;
import org.jspecify.annotations.Nullable;

/** Runs story work under one project record lock; only the window starts and stops tasks. */
public final class WorkRun {

    private static final Pattern LABEL = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}");
    private static final Pattern COMMIT = Pattern.compile("[0-9a-f]{40}|[0-9a-f]{64}");
    private static final Pattern AT =
            Pattern.compile(
                    "[0-9]{4}-[0-9]{2}-[0-9]{2}[Tt][0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.[0-9]+)?(?:[Zz]|[+-][0-9]{2}:[0-9]{2})");
    private static final Writes NOTHING = new Writes(Map.of(), Map.of(), List.of());
    private final PlanningStore store;
    private final Record record;
    private final Clock clock;

    /** Supplies the store, durable record and time source without depending on Spring. */
    public WorkRun(final PlanningStore store, final Record record, final Clock clock) {
        this.store = store;
        this.record = record;
        this.clock = clock;
    }

    /** A project writer holds its lock across every record and store operation in the action. */
    public interface Record {
        /** Refuses competing writers rather than waiting on a different planner. */
        <T extends @Nullable Object> T locked(Action<T> action);
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
     * The current runs and the discussions' offers whose ideas were handed in, with a monotonically
     * increasing local generation.
     */
    public record Snapshot(
            long generation, Map<String, Run> runs, Map<String, HandedIdeas.Handed> ideas) {
        /** Prevents actions from mutating an already published generation. */
        public Snapshot {
            if (generation < 0) {
                throw WorkRefusal.invalidSettings();
            }
            runs = Map.copyOf(runs);
            ideas = Map.copyOf(ideas);
        }

        /** A snapshot of runs without a handed-in idea. */
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
        LANDED
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
     * {@code ended}, and only LANDED carries {@code landed}, never empty.
     */
    public record Report(
            String offer,
            Event event,
            String container,
            String machine,
            @Nullable Ended ended,
            List<NextStep.Landing> landed) {

        /** Keeps the landings as they were reported. */
        public Report {
            landed = List.copyOf(landed);
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

    /** A save refusal kept without exception text, paths or credentials. */
    public record SaveFailure(String name, String subject, List<String> pushed) {
        /** Keeps evidence of repositories already pushed. */
        public SaveFailure {
            pushed = List.copyOf(pushed);
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
            NextStep.@Nullable Settings rework) {
        /** Starts with no deferred operator override. */
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
                    null);
        }

        /** Keeps replay dispositions and deferred ownership immutable. */
        public Offer {
            following = List.copyOf(following);
            deferred = List.copyOf(deferred);
        }
    }

    /** One story's accepted order, task bindings and retained reading state. */
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
            boolean pending) {
        /** Makes each published run a snapshot rather than a shared mutable object. */
        public Run {
            issueReadingRounds = List.copyOf(issueReadingRounds);
            repositories = List.copyOf(repositories);
            offers = List.copyOf(offers);
            issues = Map.copyOf(issues);
        }
    }

    /** Returns a proposal using recorded activity and refusing lost or halted runs. */
    public WorkProposal propose(final String story, final String to) {
        return propose(story, to, null);
    }

    /**
     * Returns a proposal for the story's issues {@code issues}, or its own when null, using
     * recorded activity and refusing lost or halted runs.
     */
    public WorkProposal propose(
            final String story, final String to, final @Nullable List<String> issues) {
        Ids.story(story);
        return record.locked(
                session -> {
                    final Snapshot snapshot = read(session);
                    final Board board = store.board();
                    allowed(board, snapshot, story, to);
                    return WorkProposal.propose(board, story, to, running(snapshot), issues);
                });
    }

    /** Replaces an unstarted order, retaining completed readings and their allowances. */
    public Order order(final String storyId, final String to, final WorkOrder.Decision decision) {
        Ids.story(storyId);
        return record.locked(
                session -> {
                    final Snapshot snapshot = read(session);
                    final Board board = store.board();
                    if (!board.revision().value().equals(decision.revision())) {
                        throw WorkRefusal.staleRevision(board.revision().value());
                    }
                    allowed(board, snapshot, storyId, to);
                    final Run earlier = snapshot.runs().get(storyId);
                    if (earlier != null
                            && !earlier.stopped()
                            && !earlier.halted()
                            && followUpWaits(earlier)) {
                        throw WorkRefusal.notAllowed(
                                WorkProposal.story(board, storyId).state(),
                                to,
                                "Work an earlier end offered waits to start.");
                    }
                    final WorkOrder order =
                            WorkOrder.accept(board, storyId, to, decision, running(snapshot));
                    final WorkProposal proposal =
                            WorkProposal.propose(
                                    board, storyId, to, running(snapshot), decision.issues());
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
                                                        !task.role().equals("MAIN")
                                                                || order.tasks().stream()
                                                                        .noneMatch(
                                                                                other ->
                                                                                        other.role()
                                                                                                        .equals(
                                                                                                                "BUILDER")
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
                                                                t.role().equals("BUILDER")
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
                                                                                                + "/BUILDER"))
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
                                        task.role().equals("MAIN") ? helpers : "",
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
                                new WorkOrder.Task(
                                        task.repository(),
                                        owned,
                                        task.role(),
                                        task.agent(),
                                        task.model(),
                                        task.effort(),
                                        task.mode(),
                                        prompt);
                        // The proposal named the choice's basis when the person decided on it.
                        offers.add(offer(new NextStep.Planned(scoped, to, rule, ""), storyId));
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
                                    decision.repositories() == null
                                            ? proposal.repositories()
                                            : decision.repositories(),
                                    clock.instant().toString(),
                                    offers,
                                    progress,
                                    false,
                                    false,
                                    false);
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
        return record.locked(
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
                                                    WorkRefusal.taskNotAnswered(
                                                            task, report.offer()));
                    final Offer known =
                            run.offers().stream()
                                    .filter(o -> o.id().equals(report.offer()))
                                    .findFirst()
                                    .orElseThrow();
                    if (known.status().equals("VOID")) {
                        throw WorkRefusal.taskNotAnswered(task, report.offer());
                    }
                    if (known.task() != null
                            && (!task.equals(known.task())
                                    || !report.container().equals(known.containerId())
                                    || known.machine() != null
                                            && !report.machine().equals(known.machine()))) {
                        throw conflict(
                                task, report.event(), "The report contradicts the task binding.");
                    }
                    if (report.event() == Event.STARTED && known.task() != null) {
                        replayFailure(known);
                        return answer(run, known.following());
                    }
                    if (report.event() != Event.STARTED && !known.status().equals("STARTED")) {
                        if (known.status().equals(report.event().name())
                                && known.fingerprint().equals(fingerprint(report))) {
                            replayFailure(known);
                            return answer(run, known.following());
                        }
                        throw conflict(
                                task,
                                report.event(),
                                "The task has not started or already ended differently.");
                    }
                    if (report.event() == Event.STARTED && (run.stopped() || run.halted())) {
                        throw WorkRefusal.taskNotAnswered(task, report.offer());
                    }
                    final Draft draft = new Draft(run);
                    final Board board = store.board();
                    Writes writes = NOTHING;
                    Offer accepted;
                    final List<String> following = new ArrayList<>();
                    if (report.event() == Event.STARTED) {
                        if (!NextStep.owns(board, known.binding())) {
                            throw conflict(
                                    task, report.event(), "The offered ownership has changed.");
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
                                                                                known.binding()
                                                                                                .issues()
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
                                .anyMatch(o -> task.equals(o.task()))) {
                            throw conflict(task, report.event(), "The task is already bound.");
                        }
                        writes = NextStep.started(board, known.binding(), store::storyState);
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
                            known.binding()
                                    .issues()
                                    .forEach(
                                            id -> {
                                                final NextStep.Progress p = draft.progress.get(id);
                                                if (p != null
                                                        && known.binding()
                                                                .role()
                                                                .equals("READER")) {
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
                            final WorkEnds.Judgment judgment =
                                    WorkEnds.judge(
                                            known.binding().role(),
                                            WorkEnds.Outcome.of(
                                                    ended.finished(),
                                                    ended.unpushed(),
                                                    ended.outcome()),
                                            ended.answer());
                            final NextStep.Result next =
                                    next(
                                            board,
                                            known.binding(),
                                            judgment,
                                            draft.progress,
                                            draft.comment,
                                            new NextStep.Build(task, ended.landed()));
                            draft.progress.putAll(next.progress());
                            writes = next.writes();
                            for (final NextStep.Planned planned : next.tasks()) {
                                final Offer offer = offer(planned, run.story());
                                draft.offers.add(offer);
                                following.add(offer.id());
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
                                            next.deferred(),
                                            null);
                        }
                    }
                    draft.set(accepted);
                    writes = reconcile(board, draft, writes, following);
                    draft.setFollowing(accepted.id(), following);
                    draft.compact(accepted.id());
                    final Run saved =
                            save(session, snapshot, draft, writes, accepted.id(), board, null);
                    return answer(saved, following);
                });
    }

    /**
     * Records where a build's work landed and offers its readers exactly then; refused as a
     * conflict for an unknown offer, one that did not end done, or one that already landed, so a
     * resend offers no second reader.
     */
    private Answer landed(final String task, final Report report) {
        final List<NextStep.Landing> landed = report.landed();
        return record.locked(
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
                    if (!(WorkEnds.judge(
                                    known.binding().role(),
                                    WorkEnds.Outcome.of(
                                            ended.finished(), ended.unpushed(), ended.outcome()),
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
                                    known.binding(),
                                    draft.progress,
                                    draft.comment,
                                    new NextStep.Build(task, landed));
                    draft.progress.putAll(next.progress());
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
                                    known.rework()));
                    draft.compact(known.id());
                    final Run saved =
                            save(session, snapshot, draft, NOTHING, known.id(), board, null);
                    return answer(saved, readers);
                });
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
                                o.binding().role().equals("READER")
                                        && !o.status().equals("VOID")
                                        && o.binding().issues().stream()
                                                .anyMatch(build.binding().issues()::contains));
    }

    /** Reads the run without inferring anything from a working state when its record was lost. */
    public @Nullable Run work(final String story) {
        Ids.story(story);
        return record.locked(
                        session -> java.util.Optional.ofNullable(read(session).runs().get(story)))
                .orElse(null);
    }

    /** Reads a board and work generation together under the writer lock. */
    public <T> T view(final java.util.function.BiFunction<Board, Snapshot, T> answer) {
        return record.locked(
                session -> {
                    final Snapshot snapshot = read(session);
                    return answer.apply(store.board(), snapshot);
                });
    }

    /**
     * Answers how this run's store derives a story's state from its issues, for the boards it
     * views.
     *
     * @return the store's rule
     */
    public Moves.StoryState derive() {
        return store::storyState;
    }

    /** Reads one complete generation for board decoration. */
    public Snapshot snapshot() {
        return record.locked(this::read);
    }

    /** Returns recorded active task names, never names inferred from board states. */
    public List<String> active(final String story) {
        return record.locked(session -> running(read(session)).tasks(story));
    }

    /** Records cancellation without clearing activity until the window reports a terminal event. */
    public @Nullable Run stop(final String story) {
        Ids.story(story);
        return record.locked(
                        session -> {
                            final Snapshot snapshot = read(session);
                            final Run run = snapshot.runs().get(story);
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
     * @param revision the board's revision the person saw
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
            final String revision,
            final NextStep.@Nullable Settings rework) {
        Ids.story(story);
        Ids.issue(issueId);
        words(comment);
        if (rework != null && choice != ReadingCycle.Choice.REWORK) {
            throw WorkRefusal.invalidSettings();
        }
        return record.locked(
                session -> {
                    final Snapshot snapshot = read(session);
                    final Board board = store.board();
                    final Story found = WorkProposal.story(board, story);
                    final Issue issue =
                            board.issue(issueId)
                                    .orElseThrow(() -> new StoreException.NoSuchIssue(issueId));
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
                    if (!board.revision().value().equals(revision)) {
                        throw WorkRefusal.staleRevision(board.revision().value());
                    }
                    final ReadingCycle.Counted counted;
                    try {
                        counted =
                                ReadingCycle.choose(
                                        previous.rounds(),
                                        round,
                                        choice,
                                        comment,
                                        previous.findings());
                    } catch (final ReadingCycle.Refused refused) {
                        if ("ReadingRoundChanged".equals(refused.getMessage())) {
                            throw WorkRefusal.readingRoundChanged(
                                    issueId, previous.rounds().rounds());
                        }
                        if ("ReadingNotStopped".equals(refused.getMessage())) {
                            throw WorkRefusal.readingNotStopped(issueId);
                        }
                        throw WorkRefusal.invalidSettings();
                    }
                    final Offer reading =
                            run.offers().stream()
                                    .filter(
                                            o ->
                                                    o.binding().role().equals("READER")
                                                            && o.binding()
                                                                    .issues()
                                                                    .equals(List.of(issueId)))
                                    .reduce((left, right) -> right)
                                    .orElseThrow(() -> WorkRefusal.readingNotStopped(issueId));
                    if (!NextStep.owns(board, reading.binding())
                            || !issue.state().equals(reading.binding().state())) {
                        throw WorkRefusal.readingNotStopped(issueId);
                    }
                    final NextStep.Settings settings =
                            counted.next() == ReadingCycle.Next.REWORK
                                    ? reworkSettings(board, reading.binding().rule(), issue, rework)
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
                        draft.progress.put(
                                issueId,
                                draft.progress(issueId).attention("NEXT_STEP_UNAVAILABLE"));
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
                                                    board,
                                                    story,
                                                    Map.of(issueId, target),
                                                    null,
                                                    store::storyState);
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
                                                        NextStep.findingText(previous.findings())
                                                                + (comment.isEmpty()
                                                                        ? ""
                                                                        : "\n" + comment),
                                                        counted.next() == ReadingCycle.Next.READ
                                                                ? "READER"
                                                                : "BUILDER",
                                                        lastBuild(run, issueId)),
                                                story);
                                draft.offers.add(next);
                                following.add(next.id());
                            }
                        } catch (final WorkRefusal | IllegalArgumentException cannotBuild) {
                            // Nothing was offered, so the reading still waits for a choice.
                            draft.progress.put(
                                    issueId, previous.attention("NEXT_STEP_UNAVAILABLE"));
                        }
                    }
                    writes = reconcile(board, draft, writes, following);
                    final Run saved =
                            save(session, snapshot, draft, writes, reading.id(), board, issueId);
                    return answer(saved, following);
                });
    }

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
     * Saves the idea a discussion wrote, once per offer: checked first, then saved under the
     * record's lock with the offer recorded before and after the store's save.
     *
     * @param offer the discussion's offer
     * @param idea the idea
     * @param shape the faults of the idea's shape its reader found
     * @return the story's id, the one an earlier hand-in of the offer saved too
     * @throws WorkRefusal {@code IdeaRefused} for a malformed idea or an offer whose save was
     *     pending or partly pushed
     */
    public String idea(final String offer, final NewIdeas.Idea idea, final List<String> shape) {
        HandedIdeas.offer(offer);
        NewIdeas.check(store.board(), idea, shape);
        return record.locked(
                session -> HandedIdeas.save(store, session, read(session), offer, idea, shape));
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

    /** Serializes a window move with report ownership checks and the store save itself. */
    public <T> T moves(final List<Moves.Move> moves, final Supplier<T> save) {
        return record.locked(
                session -> {
                    final Snapshot snapshot = read(session);
                    final Board board = store.board();
                    final Set<String> affected = new HashSet<>();
                    for (final Moves.Move move : moves) {
                        if (!move.story()) {
                            affected.add(move.id());
                            continue;
                        }
                        final Story story = board.story(move.id()).orElse(null);
                        if (story == null) {
                            continue;
                        }
                        affected.addAll(story.issues());
                        for (final Issue issue : board.issues()) {
                            final String parent = issue.parent();
                            if (parent != null && story.issues().contains(parent)) {
                                affected.add(issue.id());
                            }
                        }
                    }
                    final Set<String> stories = new HashSet<>();
                    moves.stream().filter(Moves.Move::story).forEach(m -> stories.add(m.id()));
                    refuseOwned(snapshot, affected, stories);
                    final T saved = save.get();
                    final Board after = store.board();
                    affected.removeIf(id -> manual(board, id) || !stateChanged(board, after, id));
                    settled(session, snapshot, affected);
                    return saved;
                });
    }

    /** Whether a save changed an issue's state; a move to where it stood answers nothing. */
    private static boolean stateChanged(final Board before, final Board after, final String id) {
        final String was = before.issue(id).map(Issue::state).orElse(null);
        final String is = after.issue(id).map(Issue::state).orElse(null);
        return !java.util.Objects.equals(was, is);
    }

    /**
     * Clears the attention of issues a person moved by hand, since the move is their answer to it
     * and nothing else would ever clear it; a halted run keeps its refused save in view.
     */
    private static void settled(
            final Session session, final Snapshot snapshot, final Set<String> moved) {
        Snapshot next = snapshot;
        for (final Run run : snapshot.runs().values()) {
            if (run.halted() || run.pending()) {
                continue;
            }
            final Draft draft = new Draft(run);
            boolean changed = false;
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
            if (changed) {
                next = replace(next, draft.freeze());
            }
        }
        if (next != snapshot) {
            session.write(next);
        }
    }

    /** Refuses a change of a story or an issue that a run with a started task owns. */
    private static void refuseOwned(
            final Snapshot snapshot, final Set<String> issues, final Set<String> stories) {
        for (final Run run : snapshot.runs().values()) {
            final List<String> active =
                    run.offers().stream()
                            .filter(o -> o.status().equals("STARTED"))
                            .map(o -> String.valueOf(o.task()))
                            .toList();
            if (active.isEmpty()) {
                continue;
            }
            if (stories.contains(run.story())
                    || run.offers().stream()
                            .flatMap(o -> o.binding().issues().stream())
                            .anyMatch(issues::contains)) {
                throw WorkRefusal.workRunning(run.story(), active);
            }
        }
    }

    private Run save(
            final Session session,
            final Snapshot snapshot,
            final Draft draft,
            final Writes writes,
            final String eventOffer,
            final Board base,
            final @Nullable String issue) {
        final Run finalRun = draft.freeze();
        final Board planned = issue == null ? base : staged(base, writes.issues());
        try {
            preflight(session, replace(snapshot, finalRun), planned, issue);
        } catch (final WorkRefusal full) {
            if (!full.name().equals("WorkRecordUnavailable")
                    || !"The work record is full.".equals(full.parameters().get("reason"))) {
                throw full;
            }
            draft.voidOffers();
            draft.compact(eventOffer);
            draft.progress.replaceAll((id, p) -> p.attention("NEXT_STEP_UNAVAILABLE"));
            preflight(session, replace(snapshot, draft.freeze()), planned, issue);
        }
        if (writes.none()) {
            final Run saved = draft.freeze();
            session.write(replace(snapshot, saved));
            return saved;
        }
        draft.pending = true;
        final Snapshot pending = replace(snapshot, draft.freeze());
        preflight(session, pending, planned, issue);
        session.write(pending);
        try {
            store.save(base.revision(), writes);
        } catch (final StoreException | UnsupportedOperationException refused) {
            draft.pending = false;
            draft.halted = true;
            draft.voidOffers();
            draft.progress.replaceAll((id, p) -> p.attention("SAVE_REFUSED"));
            draft.offers.replaceAll(
                    o ->
                            !o.id().equals(eventOffer)
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
                                            new SaveFailure(
                                                    refused instanceof StoreException
                                                            ? refused.getClass().getSimpleName()
                                                            : "NotOffered",
                                                    refused instanceof StoreException failure
                                                            ? failure.subject()
                                                            : "",
                                                    refused
                                                                    instanceof
                                                                    StoreException.PartlySaved
                                                                            partly
                                                            ? partly.pushed()
                                                            : List.of()),
                                            o.reviewAnyway(),
                                            o.reworkComment(),
                                            o.rework()));
            session.write(replace(pending, draft.freeze()));
            throw refused;
        }
        draft.pending = false;
        final Run saved = draft.freeze();
        session.write(replace(pending, saved));
        return saved;
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
                .anyMatch(o -> o.status().equals("OFFERED") && (ran || following.contains(o.id())));
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
                                        !o.binding().role().equals("READER")
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
            final String comment) {
        final NextStep.Build built =
                run == null ? new NextStep.Build("", List.of()) : lastBuild(run, issueId);
        final Issue issue = board.issue(issueId).orElseThrow();
        if (built.task().isEmpty() || built.landed().isEmpty()) {
            throw WorkRefusal.notAllowed(
                    issue.state(), to, "No landed build of " + issueId + " is known to read.");
        }
        return NextStep.task(
                board, storyId, issue, to, rule, settings, comment, "", "READER", built);
    }

    /** The build that last built the issue, whose landed work a new reading reads. */
    private static NextStep.Build lastBuild(final Run run, final String issueId) {
        return run.offers().stream()
                .filter(
                        o ->
                                !o.binding().role().equals("READER")
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
            if (old.deferred().isEmpty()) {
                continue;
            }
            final Board staged = staged(board, targets);
            final NextStep.Binding binding =
                    new NextStep.Binding(
                            draft.story,
                            old.binding().repository(),
                            old.deferred(),
                            old.binding().role(),
                            old.binding().state(),
                            old.binding().mode(),
                            old.binding().rule());
            final NextStep.Result next =
                    NextStep.resume(
                            staged,
                            binding,
                            draft.progress,
                            draft.comment,
                            store::storyState,
                            old.reviewAnyway(),
                            old.reworkComment(),
                            old.rework(),
                            build(old));
            draft.progress.putAll(next.progress());
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
                            next.deferred(),
                            old.refusal(),
                            old.reviewAnyway(),
                            old.reworkComment(),
                            old.rework()));
        }
        if (targets.equals(first.issues())) {
            return first;
        }
        return NextStep.writes(board, draft.story, targets, null, store::storyState);
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
        return NextStep.Settings.of(rule, Choice.of(blocked, rule, List.of(issue)), chosen);
    }

    private NextStep.Result next(
            final Board board,
            final NextStep.Binding binding,
            final WorkEnds.Judgment judgment,
            final Map<String, NextStep.Progress> progress,
            final String comment,
            final NextStep.Build ended) {
        try {
            return NextStep.after(
                    board, binding, judgment, progress, comment, store::storyState, ended);
        } catch (final WorkRefusal
                | IllegalArgumentException
                | UnsupportedOperationException cannotBuild) {
            final Map<String, NextStep.Progress> attention = new LinkedHashMap<>(progress);
            binding.issues()
                    .forEach(
                            id ->
                                    attention.computeIfPresent(
                                            id, (key, p) -> p.attention("NEXT_STEP_UNAVAILABLE")));
            return new NextStep.Result(NOTHING, List.of(), attention, List.of());
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
            final Map<String, NextStep.Progress> attention = new LinkedHashMap<>(progress);
            binding.issues()
                    .forEach(
                            id ->
                                    attention.computeIfPresent(
                                            id, (key, p) -> p.attention("NEXT_STEP_UNAVAILABLE")));
            return new NextStep.Result(NOTHING, List.of(), attention, List.of());
        }
    }

    private Snapshot read(final Session session) {
        Snapshot snapshot = session.read();
        for (final Run run : List.copyOf(snapshot.runs().values())) {
            if (run.pending()) {
                final Draft draft = new Draft(run);
                draft.pending = false;
                draft.halted = true;
                draft.voidOffers();
                draft.progress.replaceAll((id, p) -> p.attention("SAVE_REFUSED"));
                snapshot = replace(snapshot, draft.freeze());
                session.write(snapshot);
            }
        }
        for (final Run run : List.copyOf(snapshot.runs().values())) {
            final Run named = readersNamingTheirBuilds(run);
            if (named != run) {
                snapshot = replace(snapshot, named);
                session.write(snapshot);
            }
        }
        return snapshot;
    }

    /**
     * Names the build each unstarted reader of an older record reads, which that record did not
     * keep; a reader whose build is unknown is voided and its issue asks the person, since it would
     * read nothing.
     */
    private static Run readersNamingTheirBuilds(final Run run) {
        final Draft draft = new Draft(run);
        boolean changed = false;
        for (int index = 0; index < run.offers().size(); index++) {
            final Offer reader = run.offers().get(index);
            final WorkOrder.Task arguments = reader.arguments();
            if (!reader.binding().role().equals("READER")
                    || !reader.status().equals("OFFERED")
                    || !arguments.reads().isEmpty()) {
                continue;
            }
            final String issue = reader.binding().issues().getFirst();
            final String built =
                    run.offers().subList(0, index).stream()
                            .filter(
                                    o ->
                                            !o.binding().role().equals("READER")
                                                    && o.binding().issues().contains(issue)
                                                    && o.task() != null)
                            .reduce((left, right) -> right)
                            .map(Offer::task)
                            .orElse(null);
            changed = true;
            if (built == null) {
                draft.set(withStatus(reader, "VOID"));
                draft.progress.computeIfPresent(
                        issue, (key, p) -> p.attention("NEXT_STEP_UNAVAILABLE"));
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
                                    List.of(built))));
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
                o.rework());
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
                o.rework());
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
            if (shown.contains(run.story())) {
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
                : new Snapshot(snapshot.generation(), runs, snapshot.ideas());
    }

    private static Snapshot replace(final Snapshot snapshot, final Run run) {
        final Map<String, Run> runs = new LinkedHashMap<>(snapshot.runs());
        runs.put(run.story(), run);
        if (snapshot.generation() == Long.MAX_VALUE) {
            throw WorkRefusal.invalidSettings();
        }
        return new Snapshot(snapshot.generation() + 1, runs, snapshot.ideas());
    }

    private static WorkProposal.RunningWork running(final Snapshot snapshot) {
        return new WorkProposal.RunningWork() {
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
                                        offer.status().equals("STARTED")
                                                && offer.binding().role().equals("MAIN"));
            }
        };
    }

    private static void allowed(
            final Board board, final Snapshot snapshot, final String id, final String to) {
        final Story story = WorkProposal.story(board, id);
        final Run run = snapshot.runs().get(id);
        if (run != null
                && !run.stopped()
                && (run.halted()
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
        return !story.stateGiven()
                && board.issues().stream()
                        .anyMatch(
                                i ->
                                        i.worker() == Worker.MANUAL
                                                && i.state().equals(story.state())
                                                && (story.issues().contains(i.id())
                                                        || i.parent() != null
                                                                && story.issues()
                                                                        .contains(i.parent())));
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
                        task.role(),
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
                                                i.risk()))
                        .toList(),
                board.work(),
                board.repositories());
    }

    /** Checks opaque and display labels before they can occur in any refusal. */
    public static void checkedReport(final String task, final Report report) {
        checkedMachine(report.machine());
        try {
            if (!LABEL.matcher(task).matches()
                    || !LABEL.matcher(report.container()).matches()
                    || !UUID.fromString(report.offer()).toString().equals(report.offer())
                    || (report.event() == Event.ENDED) != (report.ended() != null)
                    || (report.event() == Event.LANDED) == report.landed().isEmpty()) {
                throw WorkRefusal.invalidSettings();
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
            case "NotSavable" ->
                    new StoreException.NotSavable(
                            failure.subject(), "The state could not be saved.");
            case "PartlySaved" ->
                    new StoreException.PartlySaved(
                            failure.subject(),
                            failure.pushed(),
                            "A repository could not be saved.");
            case "CredentialRefused" -> new StoreException.CredentialRefused(failure.subject());
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
        private boolean stopped;
        private boolean halted;
        private boolean pending;

        private Draft(final Run run) {
            original = run;
            story = run.story();
            comment = run.comment();
            offers = new ArrayList<>(run.offers());
            progress = new LinkedHashMap<>(run.issues());
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
                                            o.rework()));
        }

        private void compact(final String current) {
            final Offer latest =
                    offers.stream().filter(o -> o.id().equals(current)).findFirst().orElseThrow();
            offers.replaceAll(
                    o -> {
                        if (o.status().equals("OFFERED") || o.status().equals("STARTED")) {
                            return o;
                        }
                        final WorkOrder.Task arguments = o.arguments();
                        final Ended end = o.ended();
                        final boolean olderReading =
                                !o.id().equals(current)
                                        && o.binding().role().equals("READER")
                                        && o.binding().issues().stream()
                                                .anyMatch(latest.binding().issues()::contains)
                                        && latest.binding().role().equals("READER")
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
                                        arguments.reads()),
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
                                o.rework());
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
                                            o.rework()));
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
                    original.repositories(),
                    original.since(),
                    offers,
                    progress,
                    stopped,
                    halted,
                    pending);
        }
    }
}
