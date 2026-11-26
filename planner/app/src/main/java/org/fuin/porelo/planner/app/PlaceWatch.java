package org.fuin.porelo.planner.app;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;
import org.fuin.porelo.planner.core.IssuePlace;
import org.fuin.porelo.planner.core.PlaceRead;
import org.fuin.porelo.planner.core.PlaceReviews;
import org.fuin.porelo.planner.core.ReviewOpen;
import org.fuin.porelo.planner.core.RunPlace;
import org.fuin.porelo.planner.core.RunRole;
import org.fuin.porelo.planner.core.TaskRecord;
import org.fuin.porelo.planner.core.TaskRecords;
import org.fuin.porelo.planner.core.WorkRefusal;
import org.fuin.porelo.planner.core.WorkRun;
import org.fuin.porelo.planner.store.git.LogText;
import org.fuin.porelo.planner.store.git.PoreloProgram;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads from Porelo where each issue's work waits, at the place its run keeps: on each event of a
 * project's tasks, and every minute while a window follows the project. Each read is one at a time
 * per project, off the request's thread; a read asked while one runs runs once more after it.
 */
public final class PlaceWatch implements ChangesRegistry.Follower, AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(PlaceWatch.class);

    /** How much earlier than a run's start its record is asked for, for clocks apart. */
    static final Duration SKEW = Duration.ofMinutes(5);

    /** A refusal's or reason's name, as Porelo's contract names one. */
    private static final java.util.regex.Pattern NAME =
            java.util.regex.Pattern.compile("[A-Z][A-Za-z]{0,63}");

    /**
     * Porelo's refusals saying no pull request stands from a task's branch: none is open, or its
     * forge is none Porelo knows, so none could have been opened.
     */
    private static final Set<String> NO_PULL_REQUEST = Set.of("NoPullRequest", "UnknownForge");

    /** How far back a task whose start is not known is read from. */
    static final Duration UNKNOWN_START = Duration.ofDays(1);

    /** How often a followed project's places are read. */
    public static final Duration INTERVAL = Duration.ofSeconds(60);

    /** What the watch reads and writes of a project's work. */
    interface Places {

        /**
         * The issues whose work waits at a kept place.
         *
         * @return each with its place
         */
        List<WorkRun.Waits> waiting();

        /**
         * The tasks started for the project's runs.
         *
         * @return each task
         */
        List<WorkRun.Started> started();

        /**
         * Takes what was read of an issue's kept place.
         *
         * @param story the story
         * @param issue the issue
         * @param found what was read
         * @return what the read did
         */
        WorkRun.PlaceOutcome placeRead(String story, String issue, PlaceRead found);

        /**
         * Ends the unreadable place stop of each issue whose work no longer waits there.
         *
         * @return the stories whose runs changed
         */
        java.util.Set<String> placesLeft();

        /**
         * Offers the building work of each issue a run's Start held back, once nothing holds it
         * back any more.
         *
         * @return the stories whose runs offered work
         */
        java.util.Set<String> freed();

        /**
         * A project's work as the watch reads it.
         *
         * @param work the work
         * @return its places
         */
        static Places of(final WorkRun work) {
            return new Places() {
                @Override
                public List<WorkRun.Waits> waiting() {
                    return work.waiting();
                }

                @Override
                public List<WorkRun.Started> started() {
                    return work.started();
                }

                @Override
                public WorkRun.PlaceOutcome placeRead(
                        final String story, final String issue, final PlaceRead found) {
                    return work.placeRead(story, issue, found);
                }

                @Override
                public java.util.Set<String> placesLeft() {
                    return work.placesLeft();
                }

                @Override
                public java.util.Set<String> freed() {
                    return work.freed();
                }
            };
        }
    }

    private final Function<String, Places> works;

    private final @Nullable TaskRecords records;

    private final @Nullable PlaceReviews reviews;

    private final @Nullable String unavailable;

    private final Consumer<String> saved;

    private final TaskHelds helds;

    private final @Nullable ScheduledExecutorService scheduler;

    private final Duration interval;

    private final Map<String, ScheduledFuture<?>> polls = new HashMap<>();

    private final Map<String, Reading> reading = new HashMap<>();

    /** Whether the watch was closed, after which it reads nothing more. */
    private volatile boolean closed;

    /** Whether a project's read runs, and whether one more was asked meanwhile. */
    private static final class Reading {
        private boolean running;
        private boolean again;
    }

    /**
     * A watch asking Porelo through the readers given.
     *
     * @param works each project's work
     * @param records Porelo's task records
     * @param reviews what is open at a place
     * @param saved tells a project's windows of a change
     * @param helds the held items of the tasks, which the reads fill
     * @param scheduler runs the reads; closed with the watch
     * @param interval how often a followed project is read
     */
    public PlaceWatch(
            final Function<String, Places> works,
            final TaskRecords records,
            final PlaceReviews reviews,
            final Consumer<String> saved,
            final TaskHelds helds,
            final ScheduledExecutorService scheduler,
            final Duration interval) {
        this(works, records, reviews, null, saved, helds, scheduler, interval);
    }

    /**
     * A watch that cannot ask Porelo: every place where work waits reads as unreadable, saying why.
     *
     * @param works each project's work
     * @param unavailable why Porelo cannot be asked
     * @param saved tells a project's windows of a change
     * @param helds the held items of the tasks, which stay unread
     * @param scheduler runs the reads; closed with the watch
     * @param interval how often a followed project is read
     */
    public PlaceWatch(
            final Function<String, Places> works,
            final String unavailable,
            final Consumer<String> saved,
            final TaskHelds helds,
            final ScheduledExecutorService scheduler,
            final Duration interval) {
        this(works, null, null, unavailable, saved, helds, scheduler, interval);
    }

    private PlaceWatch(
            final Function<String, Places> works,
            final @Nullable TaskRecords records,
            final @Nullable PlaceReviews reviews,
            final @Nullable String unavailable,
            final Consumer<String> saved,
            final TaskHelds helds,
            final @Nullable ScheduledExecutorService scheduler,
            final Duration interval) {
        this.works = works;
        this.records = records;
        this.reviews = reviews;
        this.unavailable = unavailable;
        this.saved = saved;
        this.helds = helds;
        this.scheduler = scheduler;
        if (interval.isNegative() || interval.isZero()) {
            throw new IllegalArgumentException("A watch reads after a positive interval");
        }
        this.interval = interval;
    }

    /**
     * A watch that reads nothing, as the mock mode, which reaches no Porelo, needs.
     *
     * @return the watch
     */
    public static PlaceWatch none() {
        return new PlaceWatch(
                project -> {
                    throw new IllegalStateException("No place is read");
                },
                null,
                null,
                null,
                project -> {},
                new TaskHelds(),
                null,
                INTERVAL);
    }

    @Override
    public synchronized void followed(final String project) {
        final ScheduledExecutorService runs = scheduler;
        if (runs == null || closed || polls.containsKey(project)) {
            return;
        }
        try {
            polls.put(
                    project,
                    runs.scheduleWithFixedDelay(
                            () -> ask(project), 0, interval.toMillis(), TimeUnit.MILLISECONDS));
        } catch (final RejectedExecutionException stopping) {
            // The planner stops; nothing is read after.
        }
    }

    @Override
    public synchronized void left(final String project) {
        final ScheduledFuture<?> poll = polls.remove(project);
        if (poll != null) {
            poll.cancel(false);
        }
    }

    @Override
    public void reported(final String project) {
        if (scheduler != null && !closed) {
            ask(project);
        }
    }

    /**
     * Whether a project's places are read every interval.
     *
     * @param project the project's checked name
     * @return true while it is
     */
    synchronized boolean watches(final String project) {
        return polls.containsKey(project);
    }

    /** Reads a project's places off the caller's thread, once more if a read runs now. */
    private void ask(final String project) {
        synchronized (this) {
            if (closed) {
                return;
            }
            final Reading now = reading.computeIfAbsent(project, p -> new Reading());
            if (now.running) {
                now.again = true;
                return;
            }
            now.running = true;
        }
        try {
            Thread.ofVirtual().name("place-read").start(() -> readOn(project));
        } catch (final RuntimeException e) {
            synchronized (this) {
                reading.remove(project);
            }
            throw e;
        }
    }

    private void readOn(final String project) {
        boolean ended = false;
        try {
            while (true) {
                try {
                    readAll(project);
                } catch (final RuntimeException e) {
                    LOG.warn(
                            "Reading where the work of project {} waits failed: {}",
                            LogText.of(project),
                            logged(e));
                }
                synchronized (this) {
                    final Reading now = Objects.requireNonNull(reading.get(project));
                    if (!now.again || closed) {
                        reading.remove(project);
                        ended = true;
                        return;
                    }
                    now.again = false;
                }
            }
        } finally {
            if (!ended) {
                // An Error ended the reads: the project's next read starts anew.
                synchronized (this) {
                    reading.remove(project);
                }
            }
        }
    }

    /** A failure's message for the log, with no local path or secret it may name. */
    private static String logged(final Throwable failure) {
        return LogText.of(PoreloProgram.scrubbed(String.valueOf(failure.getMessage())));
    }

    /**
     * Reads every place of a project where an issue's work waits, now, on this thread: Porelo's
     * record of the task bound there, then what is open there. A read one issue cannot take leaves
     * the others read. The same read of the records gives the held items of each task started for
     * the project's runs that is still at work or whose count was never read, whether or not a
     * place waits. After them, even with no place waiting, the building work of each issue a Start
     * held back whose blockers are done is offered, so a blocker that reached review in this read
     * frees its issue in the same one.
     *
     * @param project the project's checked name
     * @return each issue's outcome, by story and issue
     */
    Map<String, WorkRun.PlaceOutcome> readAll(final String project) {
        if (scheduler == null || closed) {
            return Map.of();
        }
        final long began = System.nanoTime();
        final Places work = works.apply(project);
        // A stop for a place that could not be read ends once nothing waits there, even when
        // nothing of the project waits any more.
        boolean left;
        try {
            left = !work.placesLeft().isEmpty();
        } catch (final RuntimeException e) {
            // The places are read all the same; a save that failed may have changed the run.
            left = true;
            LOG.warn(
                    "Ending the stops of project {} whose places were left failed: {}",
                    LogText.of(project),
                    logged(e));
        }
        final List<WorkRun.Waits> waiting = work.waiting();
        final List<WorkRun.Started> counted =
                work.started().stream().filter(t -> helds.due(project, t)).toList();
        final long waitingNanos = System.nanoTime() - began;
        final Map<String, WorkRun.PlaceOutcome> outcomes = new LinkedHashMap<>();
        final TaskRecords recordsRead = records;
        final PlaceReviews reviewsRead = reviews;
        List<TaskRecord> found = List.of();
        String unread = unavailable;
        boolean changed = left;
        if (recordsRead != null
                && reviewsRead != null
                && (!waiting.isEmpty() || !counted.isEmpty())) {
            // One read serves both, from the earliest start of either; a task's start not known
            // is taken as a day ago, never as the epoch, so it does not widen every read.
            final Instant from =
                    earliest(
                            Stream.concat(
                                            waiting.stream()
                                                    .map(w -> earliest(w.since(), Instant.EPOCH)),
                                            counted.stream()
                                                    .map(
                                                            t ->
                                                                    earliest(
                                                                            t.since(),
                                                                            Instant.now()
                                                                                    .minus(
                                                                                            UNKNOWN_START))))
                                    .toList());
            try {
                found = recordsRead.since(project, from.minus(SKEW));
                changed |= helds.read(project, found, counted);
            } catch (final TaskRecords.Unanswered e) {
                if (waiting.isEmpty()) {
                    LOG.info(
                            "Porelo gave project {} no answer of its tasks' held items: {}",
                            LogText.of(project),
                            logged(e));
                } else {
                    unread = said(project, null, null, e);
                }
            }
        }
        if (waiting.isEmpty()) {
            if (freed(project, work) || changed) {
                saved.accept(project);
            }
            measured(project, 0, waitingNanos, began);
            return outcomes;
        }
        for (final WorkRun.Waits waits : waiting) {
            final String key = waits.story() + "/" + waits.issue();
            try {
                final WorkRun.PlaceOutcome outcome =
                        read(project, work, waits, found, unread, reviewsRead);
                outcomes.put(key, outcome);
                changed |=
                        outcome != WorkRun.PlaceOutcome.UNCHANGED
                                && outcome != WorkRun.PlaceOutcome.WAITING;
            } catch (final WorkRefusal | IllegalArgumentException moved) {
                // The run changed since its places were listed; the next read sees it.
                LOG.debug(
                        "Issue {} of project {} was not read: {}",
                        LogText.of(waits.issue()),
                        LogText.of(project),
                        logged(moved));
            } catch (final RuntimeException e) {
                // A save that failed may have halted the run or left it as it stood: the
                // windows read it again either way.
                changed = true;
                LOG.warn(
                        "Reading where issue {} of project {} waits failed: {}",
                        LogText.of(waits.issue()),
                        LogText.of(project),
                        logged(e));
            }
        }
        changed |= freed(project, work);
        if (changed) {
            saved.accept(project);
        }
        measured(project, waiting.size(), waitingNanos, began);
        return outcomes;
    }

    /**
     * Offers the freed issues of a project's runs; a failure is logged and leaves the windows told
     * of a change, since a save that failed may have changed a run.
     *
     * @return whether a run may have changed
     */
    private static boolean freed(final String project, final Places work) {
        try {
            return !work.freed().isEmpty();
        } catch (final RuntimeException e) {
            LOG.warn(
                    "Offering the freed issues of project {} failed: {}",
                    LogText.of(project),
                    logged(e));
            return true;
        }
    }

    /**
     * Logs what one read of a project's places took, so the cost of the minute's board reads is
     * seen in a real run: the issues waiting, the time listing them, the time of the whole read.
     */
    private static void measured(
            final String project, final int waiting, final long waitingNanos, final long began) {
        if (LOG.isDebugEnabled()) {
            LOG.debug(
                    "Read of the places of project {}: {} issues waiting, listing took {} ms, the"
                            + " read {} ms",
                    LogText.of(project),
                    waiting,
                    TimeUnit.NANOSECONDS.toMillis(waitingNanos),
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began));
        }
    }

    private static WorkRun.PlaceOutcome read(
            final String project,
            final Places work,
            final WorkRun.Waits waits,
            final List<TaskRecord> found,
            final @Nullable String unread,
            final @Nullable PlaceReviews reviews) {
        final var place = waits.place();
        if (unread != null || reviews == null) {
            return work.placeRead(
                    waits.story(),
                    waits.issue(),
                    new PlaceRead.Unread(
                            place.place(),
                            place.task(),
                            unread == null ? "Porelo cannot be asked" : unread));
        }
        final String container = waits.container();
        final TaskRecord record =
                found.stream()
                        .filter(r -> container != null && r.task().equals(container))
                        .reduce((left, right) -> right)
                        .orElse(null);
        if (record != null) {
            final WorkRun.PlaceOutcome recorded =
                    work.placeRead(waits.story(), waits.issue(), new PlaceRead.Recorded(record));
            if (recorded != WorkRun.PlaceOutcome.UNCHANGED) {
                return recorded;
            }
        }
        PlaceRead read;
        try {
            final ReviewOpen answer =
                    reviews.open(project, place.place(), RunRole.AUTHOR, place.task());
            read = new PlaceRead.Opened(place.place(), place.task(), answer);
            if (forwarded(place, answer)) {
                read = ownRead(project, waits.issue(), place, answer, reviews);
            }
        } catch (final TaskRecords.Unanswered e) {
            read =
                    new PlaceRead.Unread(
                            place.place(),
                            place.task(),
                            said(project, waits.issue(), place.place(), e));
        }
        return work.placeRead(waits.story(), waits.issue(), read);
    }

    /** Whether an author's gate push was forwarded, so its work may go on at its pull request. */
    private static boolean forwarded(final IssuePlace place, final ReviewOpen answer) {
        return place.role() == RunRole.AUTHOR
                && place.place().gatePush() != null
                && answer.decision() == ReviewOpen.Decision.APPROVED
                && answer.mergedCommit() != null
                && answer.complete();
    }

    /**
     * What is open at the task's own pull request once its gate push was forwarded: the gate's
     * answer alone where Porelo finds no pull request from the task's branch, and no answer at all
     * where it cannot say, so the forwarding is never taken for the work's end.
     */
    private static PlaceRead ownRead(
            final String project,
            final String issue,
            final IssuePlace place,
            final ReviewOpen atGate,
            final PlaceReviews reviews) {
        try {
            return new PlaceRead.Forwarded(
                    place.place(),
                    place.task(),
                    atGate,
                    reviews.open(project, RunPlace.own(), RunRole.AUTHOR, place.task()));
        } catch (final PlaceReviews.Refused refused) {
            if (NO_PULL_REQUEST.contains(refused.refusal())) {
                LOG.info(
                        "Porelo found no pull request where issue {} of project {} waits at {},"
                                + " so the gate's answer stands: {}",
                        LogText.of(issue),
                        LogText.of(project),
                        LogText.of(RunPlace.own()),
                        logged(refused));
                return new PlaceRead.Opened(place.place(), place.task(), atGate);
            }
            return new PlaceRead.Unread(
                    place.place(), place.task(), said(project, issue, RunPlace.own(), refused));
        } catch (final TaskRecords.Unanswered e) {
            return new PlaceRead.Unread(
                    place.place(), place.task(), said(project, issue, RunPlace.own(), e));
        }
    }

    /**
     * Why Porelo gave no answer, in fixed words: a typed refusal by its names, anything else in
     * words of its own, its detail only in the log, scrubbed of any local path. The log names the
     * issue and the place asked for, when the answer was asked for one.
     */
    private static String said(
            final String project,
            final @Nullable String issue,
            final @Nullable RunPlace place,
            final TaskRecords.Unanswered unanswered) {
        if (issue == null) {
            LOG.info(
                    "Porelo gave project {} no answer of where work waits: {}",
                    LogText.of(project),
                    logged(unanswered));
        } else {
            LOG.info(
                    "Porelo gave project {} no answer of what is open where issue {} waits at {}:"
                            + " {}",
                    LogText.of(project),
                    LogText.of(issue),
                    LogText.of(place),
                    logged(unanswered));
        }
        if (unanswered instanceof PlaceReviews.Refused refused
                && NAME.matcher(refused.refusal()).matches()
                && (refused.reason() == null || NAME.matcher(refused.reason()).matches())) {
            return "Porelo refused the place: "
                    + refused.refusal()
                    + (refused.reason() == null ? "" : " (" + refused.reason() + ")");
        }
        return "Porelo gave no answer that can be trusted";
    }

    /** The earliest of the starts given, the epoch for none. */
    private static Instant earliest(final List<Instant> starts) {
        Instant earliest = null;
        for (final Instant since : starts) {
            if (earliest == null || since.isBefore(earliest)) {
                earliest = since;
            }
        }
        return earliest == null ? Instant.EPOCH : earliest;
    }

    /** A start as given, or the instant taken for it when it is not known. */
    private static Instant earliest(final @Nullable String at, final Instant unknown) {
        if (at == null) {
            return unknown;
        }
        try {
            return Instant.parse(at);
        } catch (final DateTimeParseException unreadable) {
            return unknown;
        }
    }

    @Override
    public void close() {
        final ScheduledExecutorService runs = scheduler;
        synchronized (this) {
            closed = true;
            polls.values().forEach(poll -> poll.cancel(false));
            polls.clear();
        }
        if (runs != null) {
            runs.shutdownNow();
        }
    }
}
