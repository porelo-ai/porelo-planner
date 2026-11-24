package org.fuin.porelo.planner.app;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.fuin.porelo.planner.core.Changes;
import org.fuin.porelo.planner.store.PlanningStore;
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.planner.store.git.LogText;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One {@link Changes} per project while a window follows it, polled on one scheduler and no more
 * once the last window has gone.
 */
public final class ChangesRegistry implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(ChangesRegistry.class);

    /**
     * The longest wait between two polls of a project, a day, whatever its store names, before the
     * factor {@code polling}.
     */
    static final Duration LONGEST_WAIT = Duration.ofDays(1);

    private final Projects projects;

    private final Duration interval;

    private final Duration probed;

    private final ScheduledExecutorService scheduler;

    /** The longest wait between two polls of a project. */
    private final Duration longestWait;

    private final Map<String, Followed> followed = new HashMap<>();

    private final HeldSavesOf held;

    private boolean closed;

    private record Followed(Changes changes, Polling polling) {}

    /**
     * A read of a followed project that failed, which its streams tell as the read's problem.
     *
     * @param at when it failed
     * @param cause what the read threw
     */
    public record ReadFailure(java.time.Instant at, RuntimeException cause) {}

    /** The last read of each followed project that failed, until a read succeeds. */
    private final Map<String, ReadFailure> failures =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Answers the last failed read of a project, if no read succeeded since.
     *
     * @param project the project's checked name
     * @return the failure, or empty
     */
    public java.util.Optional<ReadFailure> lastFailure(final String project) {
        return java.util.Optional.ofNullable(failures.get(project));
    }

    private void readEnded(final String project, final @Nullable RuntimeException failure) {
        if (failure == null) {
            failures.remove(project);
        } else {
            failures.put(project, new ReadFailure(java.time.Instant.now(), failure));
        }
    }

    /**
     * Makes the registry, polling every project at one interval.
     *
     * @param projects the projects the window has given
     * @param interval how often a followed project's repositories are fetched
     * @param scheduler runs the polls; closed with the registry
     */
    public ChangesRegistry(
            final Projects projects,
            final Duration interval,
            final ScheduledExecutorService scheduler) {
        this(projects, interval, interval, scheduler);
    }

    /**
     * Makes the registry.
     *
     * @param projects the projects the window has given
     * @param interval how often a followed project's repositories are fetched
     * @param probed how often a followed project whose store asks cheaply whether it changed is
     *     asked, as its store's pace allows
     * @param scheduler runs the polls; closed with the registry
     */
    public ChangesRegistry(
            final Projects projects,
            final Duration interval,
            final Duration probed,
            final ScheduledExecutorService scheduler) {
        this(projects, interval, probed, scheduler, LONGEST_WAIT);
    }

    /**
     * Makes the registry with its own longest wait between two polls.
     *
     * @param projects the projects the window has given
     * @param interval how often a followed project's repositories are fetched
     * @param probed how often a followed project whose store asks cheaply whether it changed is
     *     asked, as its store's pace allows
     * @param scheduler runs the polls; closed with the registry
     * @param longestWait the longest wait between two polls of a project, whatever its store names
     */
    public ChangesRegistry(
            final Projects projects,
            final Duration interval,
            final Duration probed,
            final ScheduledExecutorService scheduler,
            final Duration longestWait) {
        this(projects, interval, probed, scheduler, longestWait, HeldSavesOf.NONE);
    }

    /**
     * Makes the registry whose boards carry the saves each project's outbox keeps for the person.
     *
     * @param projects the projects the window has given
     * @param interval how often a followed project's repositories are fetched
     * @param probed how often a followed project whose store asks cheaply whether it changed is
     *     asked, as its store's pace allows
     * @param scheduler runs the polls; closed with the registry
     * @param longestWait the longest wait between two polls of a project, whatever its store names
     * @param held gives each project the saves its outbox keeps
     */
    ChangesRegistry(
            final Projects projects,
            final Duration interval,
            final Duration probed,
            final ScheduledExecutorService scheduler,
            final Duration longestWait,
            final HeldSavesOf held) {
        this.projects = projects;
        this.interval = interval;
        this.probed = probed;
        this.scheduler = scheduler;
        this.longestWait = longestWait;
        this.held = held;
        projects.onRetired(this::drop);
        projects.onSaved(this::saved);
    }

    /**
     * Answers the projects the window has given.
     *
     * @return the projects
     */
    public Projects projects() {
        return projects;
    }

    /**
     * Follows a project's changes.
     *
     * @param project the project's checked name
     * @return a subscription holding the current board
     * @throws Refusal {@code NoSuchProject} for a project not given, before anything is followed
     */
    public Changes.Subscription subscribe(final String project) {
        // The first read may clone over the network, so it holds no lock another project needs;
        // the read under the lock below then answers from memory.
        projects.store(project).board();
        return follow(project);
    }

    private synchronized Changes.Subscription follow(final String project) {
        if (closed) {
            throw new IllegalStateException("The planner is stopping");
        }
        final Followed known = followed.get(project);
        if (known != null) {
            return known.changes().subscribe();
        }
        final Changes changes =
                new Changes(
                        projects.store(project),
                        () -> idle(project),
                        projects.work(project),
                        () -> HeldSavesController.answers(project, held.of(project)));
        // Subscribed before it is followed, so a board that cannot be read leaves no polling.
        final Changes.Subscription subscription = changes.subscribe();
        final Polling polling = new Polling(project, changes);
        followed.put(project, new Followed(changes, polling));
        // At once first: the store may answer its clones as an earlier run left them.
        polling.schedule(Duration.ZERO);
        return subscription;
    }

    /**
     * Fetches a project's repositories now and tells a new revision to its followers.
     *
     * @param project the project's checked name
     * @return the revision after the fetch; for a followed store that reloads alone the revision
     *     held when the read began, or null while it holds no board yet
     * @throws Refusal {@code NoSuchProject} for a project not given
     */
    public @Nullable String refresh(final String project) {
        final Followed known;
        synchronized (this) {
            known = followed.get(project);
        }
        final PlanningStore store = projects.store(project);
        if (known == null) {
            if (!store.reloadsAlone()) {
                return store.refresh().revision().value();
            }
            // Before any window follows the project its first read may outlast the window's wait
            // too; a stream opened meanwhile joins that read and tells its board.
            final @Nullable String held =
                    store.held().map(org.fuin.porelo.planner.store.Revision::value).orElse(null);
            Thread.ofVirtual()
                    .name("planner-refresh")
                    .start(
                            () -> {
                                store.reloadAsked();
                                try {
                                    store.refresh();
                                    readEnded(project, null);
                                } catch (final RuntimeException e) {
                                    readEnded(project, e);
                                    LOG.warn(
                                            "Refreshing project {} failed: {}",
                                            LogText.of(project),
                                            LogText.of(String.valueOf(e.getMessage())));
                                } finally {
                                    store.reloadEnded();
                                }
                            });
            return held;
        }
        if (!store.reloadsAlone()) {
            return known.changes().poll().revision().value();
        }
        // A whole read of a large project outlasts the window's wait, so the board kept is
        // answered at once, none before the first, and the read goes on alone; its stream tells
        // the window what it finds.
        final @Nullable String kept =
                store.held().map(org.fuin.porelo.planner.store.Revision::value).orElse(null);
        Thread.ofVirtual()
                .name("planner-refresh")
                .start(
                        () -> {
                            try {
                                known.changes().poll();
                                readEnded(project, null);
                            } catch (final RuntimeException e) {
                                readEnded(project, e);
                                LOG.warn(
                                        "Refreshing project {} failed: {}",
                                        LogText.of(project),
                                        LogText.of(String.valueOf(e.getMessage())));
                            }
                        });
        return kept;
    }

    /**
     * Tells a followed project's windows the board the planner's own save made.
     *
     * @param project the project's checked name
     */
    public void saved(final String project) {
        final Followed known;
        synchronized (this) {
            known = followed.get(project);
        }
        if (known == null) {
            return;
        }
        try {
            known.changes().saved();
        } catch (final StoreException.NoForgeAccess refused) {
            LOG.warn(
                    "Reading project {} after a save was refused, its streams end: {}",
                    LogText.of(project),
                    LogText.of(refused.getMessage()));
            // Its windows open their stream again, are told the refusal and offer a token.
            end(project, known.changes());
        } catch (final RuntimeException e) {
            // The save's own answer, a refusal or the board, is what the window must get.
            LOG.warn(
                    "Reading project {} after a save failed: {}",
                    LogText.of(project),
                    LogText.of(e.getMessage()));
        }
    }

    /**
     * Answers how many projects are followed, which is how many are polled.
     *
     * @return their number
     */
    public synchronized int followedProjects() {
        return followed.size();
    }

    /** Ends every subscription and stops the polling. */
    @Override
    public void close() {
        final Map<String, Followed> all;
        synchronized (this) {
            closed = true;
            all = Map.copyOf(followed);
            followed.clear();
        }
        all.values()
                .forEach(
                        each -> {
                            each.polling().cancel();
                            each.changes().closeAll();
                        });
        scheduler.shutdownNow();
    }

    private void drop(final String project) {
        final Followed known;
        synchronized (this) {
            known = followed.remove(project);
        }
        if (known != null) {
            // Its windows reconnect and read the project from its new store, or learn it is gone.
            known.polling().cancel();
            known.changes().closeAll();
        }
    }

    private void end(final String project, final Changes changes) {
        final Followed known;
        synchronized (this) {
            known = followed.get(project);
            if (known == null || known.changes() != changes) {
                return;
            }
            followed.remove(project);
        }
        known.polling().cancel();
        known.changes().closeAll();
    }

    private synchronized void idle(final String project) {
        final Followed known = followed.get(project);
        // A window that subscribed since the last one left keeps the project followed.
        if (known != null && known.changes().subscribers() == 0) {
            known.polling().cancel();
            followed.remove(project);
        }
    }

    /** One project's polls, each scheduled after the one before by the wait its store names. */
    private final class Polling {

        private final String project;

        private final Changes changes;

        private @Nullable ScheduledFuture<?> next;

        private boolean cancelled;

        Polling(final String project, final Changes changes) {
            this.project = project;
            this.changes = changes;
        }

        synchronized void schedule(final Duration delay) {
            if (cancelled) {
                return;
            }
            try {
                next = scheduler.schedule(this::run, delay.toMillis(), TimeUnit.MILLISECONDS);
            } catch (final RejectedExecutionException e) {
                // The scheduler stops with the planner; nothing is polled after.
                cancelled = true;
            }
        }

        synchronized void cancel() {
            cancelled = true;
            final ScheduledFuture<?> pending = next;
            if (pending != null) {
                pending.cancel(false);
            }
        }

        private void run() {
            try {
                poll(project, changes);
            } finally {
                // Whatever the poll threw, the project stays polled while it is followed.
                schedule(delay());
            }
        }

        /** The store's wait, never shorter than the shorter interval, so no poll runs hot. */
        private Duration delay() {
            final Duration least = interval.compareTo(probed) < 0 ? interval : probed;
            try {
                final Duration wait = changes.pollDelay(interval, probed);
                return clamped(wait.compareTo(least) < 0 ? least : wait, longestWait);
            } catch (final RuntimeException e) {
                return clamped(interval, longestWait);
            }
        }
    }

    /**
     * A wait no longer than the longest, so its milliseconds never overflow and no store stops its
     * project's polls for good.
     *
     * @param wait the wait asked for
     * @param longest the longest wait taken
     * @return the wait taken
     */
    static Duration clamped(final Duration wait, final Duration longest) {
        return wait.compareTo(longest) > 0 ? longest : wait;
    }

    private void poll(final String project, final Changes changes) {
        try {
            changes.pollOnSchedule();
            readEnded(project, null);
        } catch (final StoreException.NoForgeAccess refused) {
            readEnded(project, refused);
            LOG.warn(
                    "Fetching project {} was refused, its streams end: {}",
                    LogText.of(project),
                    LogText.of(refused.getMessage()));
            // Its windows open their stream again, are told the refusal and offer a token.
            end(project, changes);
        } catch (final RuntimeException e) {
            readEnded(project, e);
            // The store's words are free of secrets and local paths; the next poll tries again.
            LOG.warn(
                    "Fetching project {} failed: {}",
                    LogText.of(project),
                    LogText.of(e.getMessage()));
        }
    }
}
