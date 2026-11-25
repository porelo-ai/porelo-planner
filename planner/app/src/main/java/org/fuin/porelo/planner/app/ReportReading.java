package org.fuin.porelo.planner.app;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.fuin.porelo.planner.core.ReportTaking;
import org.fuin.porelo.planner.core.TaskRecords;
import org.fuin.porelo.planner.core.TaskReports;
import org.fuin.porelo.planner.core.WorkRun;
import org.fuin.porelo.planner.store.git.LogText;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads the reports of the planner's own tasks off the request threads: every minute for each
 * project a window follows, and soon after a task's event, one read at a time per project; a read
 * asked for while one runs is run once more after it. What a read took is logged, each line in
 * fixed words; a read that fails is logged and tried again on the next turn.
 */
public final class ReportReading implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(ReportReading.class);

    /** How often a followed project's reports are read, before the factor {@code polling}. */
    static final Duration EVERY = Duration.ofSeconds(60);

    /** Runs one project's read; the planner's own reads its work's reports from Porelo. */
    @FunctionalInterface
    interface Read {
        /**
         * Reads a project's reports and takes them.
         *
         * @param project the project's name
         * @return what was taken
         */
        ReportTaking.Outcome read(String project);
    }

    private final Read read;

    private final Supplier<Set<String>> followed;

    private final java.util.function.Consumer<String> saved;

    private final ExecutorService reads;

    private final @Nullable ScheduledExecutorService ticks;

    /** Each project's state: absent while idle, false while one read runs, true when one more. */
    private final Map<String, Boolean> running = new ConcurrentHashMap<>();

    /** The last failure said of each project, so a failure that stays is logged once. */
    private final Map<String, String> failing = new ConcurrentHashMap<>();

    /**
     * Reads through Porelo's executable for each project the registry knows, every {@code every}
     * for the projects a window follows.
     *
     * @param projects the projects the window has given
     * @param changes the windows' streams, which name the followed projects and are told of a save
     * @param records Porelo's task records
     * @param reports Porelo's reports
     * @param every how often a followed project is read
     */
    ReportReading(
            final Projects projects,
            final ChangesRegistry changes,
            final TaskRecords records,
            final TaskReports reports,
            final Duration every) {
        this(
                project -> {
                    final WorkRun work = projects.work(project);
                    return work.takeReports(project, records, reports, WorkRequests::ideaText);
                },
                changes::followedNames,
                changes::saved,
                Executors.newVirtualThreadPerTaskExecutor(),
                Executors.newSingleThreadScheduledExecutor(
                        Thread.ofPlatform().daemon().name("report-reading").factory()));
        // A project the window gave up is read no more, and a failure of it said no more.
        projects.onRetired(failing::remove);
        final ScheduledExecutorService scheduled = ticks;
        if (scheduled != null) {
            scheduled.scheduleWithFixedDelay(
                    this::tick, every.toMillis(), every.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    ReportReading(
            final Read read,
            final Supplier<Set<String>> followed,
            final java.util.function.Consumer<String> saved,
            final ExecutorService reads,
            final @Nullable ScheduledExecutorService ticks) {
        this.read = read;
        this.followed = followed;
        this.saved = saved;
        this.reads = reads;
        this.ticks = ticks;
    }

    /**
     * A reading that reads nothing, for the mock mode and a planner without Porelo's executable.
     *
     * @return the reading
     */
    static ReportReading idle() {
        return new ReportReading(
                project -> new ReportTaking.Outcome(List.of(), List.of(), List.of(), List.of()),
                Set::of,
                project -> {},
                Executors.newVirtualThreadPerTaskExecutor(),
                null);
    }

    /** Asks a read of every project a window follows. */
    void tick() {
        try {
            followed.get().forEach(this::soon);
        } catch (final RuntimeException e) {
            LOG.warn("The followed projects could not be listed: {}", LogText.of(e.getMessage()));
        }
    }

    /**
     * Asks a read of one project, off the caller's thread; while one runs, one more follows it.
     *
     * @param project the project's checked name
     */
    void soon(final String project) {
        final Boolean before = running.compute(project, (p, state) -> state == null ? false : true);
        if (before) {
            return;
        }
        try {
            reads.execute(() -> loop(project));
        } catch (final RejectedExecutionException closed) {
            running.remove(project);
        }
    }

    /**
     * Whether a read of the project runs or waits to.
     *
     * @param project the project's checked name
     * @return whether one does
     */
    boolean busy(final String project) {
        return running.containsKey(project);
    }

    private void loop(final String project) {
        while (true) {
            once(project);
            // Ends unless another read was asked for meanwhile, which runs now.
            final Boolean again =
                    running.computeIfPresent(project, (p, state) -> state ? false : null);
            if (again == null) {
                return;
            }
        }
    }

    private void once(final String project) {
        final ReportTaking.Outcome outcome;
        try {
            outcome = read.read(project);
        } catch (final IllegalStateException retired) {
            // A project the window gave up is read no more.
            failing.remove(project);
            return;
        } catch (final Refusal e) {
            if ("NoSuchProject".equals(e.name())) {
                // A project the planner was not given, or gave up, is read no more.
                failing.remove(project);
                return;
            }
            failed(project, e.getClass().getSimpleName() + ": " + e.getMessage());
            return;
        } catch (final TaskRecords.Unanswered | org.fuin.porelo.planner.core.WorkRefusal e) {
            failed(project, String.valueOf(e.getMessage()));
            return;
        } catch (final RuntimeException e) {
            failed(project, e.getClass().getSimpleName() + ": " + e.getMessage());
            return;
        }
        if (failing.remove(project) != null) {
            LOG.info("The reports of project {} are read again", LogText.of(project));
        }
        outcome.stories()
                .forEach(
                        story ->
                                LOG.info(
                                        "Saved the idea of report {} in project {}",
                                        LogText.of(story),
                                        LogText.of(project)));
        outcome.refused()
                .forEach(
                        why ->
                                LOG.warn(
                                        "Took the idea of report {} in project {} without saving it",
                                        LogText.of(why),
                                        LogText.of(project)));
        outcome.unread()
                .forEach(
                        why ->
                                LOG.warn(
                                        "A task's reports in project {} were not taken: {}",
                                        LogText.of(project),
                                        LogText.of(why)));
        outcome.notes()
                .forEach(
                        note -> LOG.info("{} (project {})", LogText.of(note), LogText.of(project)));
        if (!outcome.stories().isEmpty()) {
            saved.accept(project);
        }
    }

    /** Whether the reading runs on its own; an idle one never does. */
    boolean scheduled() {
        return ticks != null;
    }

    /** Whether a failure of the project's last read is still kept. */
    boolean failing(final String project) {
        return failing.containsKey(project);
    }

    void failed(final String project, final String why) {
        if (!why.equals(failing.put(project, why))) {
            LOG.warn(
                    "The reports of project {} could not be read, tried again in a minute: {}",
                    LogText.of(project),
                    LogText.of(why));
        }
    }

    @Override
    public void close() {
        final ScheduledExecutorService scheduled = ticks;
        if (scheduled != null) {
            scheduled.shutdownNow();
        }
        reads.shutdownNow();
    }
}
