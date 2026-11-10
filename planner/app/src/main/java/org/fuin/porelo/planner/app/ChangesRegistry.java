package org.fuin.porelo.planner.app;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.fuin.porelo.planner.core.Changes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One {@link Changes} per project while a window follows it, polled on one scheduler and no more
 * once the last window has gone.
 */
public final class ChangesRegistry implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(ChangesRegistry.class);

    private final Projects projects;

    private final Duration interval;

    private final ScheduledExecutorService scheduler;

    private final Map<String, Followed> followed = new HashMap<>();

    private boolean closed;

    private record Followed(Changes changes, ScheduledFuture<?> polling) {}

    /**
     * Makes the registry.
     *
     * @param projects the projects the window has given
     * @param interval how often a followed project's repositories are fetched
     * @param scheduler runs the polls; closed with the registry
     */
    public ChangesRegistry(
            final Projects projects,
            final Duration interval,
            final ScheduledExecutorService scheduler) {
        this.projects = projects;
        this.interval = interval;
        this.scheduler = scheduler;
        projects.onRetired(this::drop);
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
        final Changes changes = new Changes(projects.store(project), () -> idle(project));
        // Subscribed before it is followed, so a board that cannot be read leaves no polling.
        final Changes.Subscription subscription = changes.subscribe();
        followed.put(
                project,
                new Followed(
                        changes,
                        scheduler.scheduleWithFixedDelay(
                                () -> poll(project, changes),
                                interval.toMillis(),
                                interval.toMillis(),
                                TimeUnit.MILLISECONDS)));
        return subscription;
    }

    /**
     * Fetches a project's repositories now and tells a new revision to its followers.
     *
     * @param project the project's checked name
     * @return the revision after the fetch
     * @throws Refusal {@code NoSuchProject} for a project not given
     */
    public String refresh(final String project) {
        final Followed known;
        synchronized (this) {
            known = followed.get(project);
        }
        if (known == null) {
            return projects.store(project).refresh().revision().value();
        }
        return known.changes().poll().revision().value();
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
        if (known != null) {
            known.changes().saved();
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
                            each.polling().cancel(false);
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
            known.polling().cancel(false);
            known.changes().closeAll();
        }
    }

    private synchronized void idle(final String project) {
        final Followed known = followed.get(project);
        // A window that subscribed since the last one left keeps the project followed.
        if (known != null && known.changes().subscribers() == 0) {
            known.polling().cancel(false);
            followed.remove(project);
        }
    }

    private static void poll(final String project, final Changes changes) {
        try {
            changes.poll();
        } catch (final RuntimeException e) {
            // The store's words are free of secrets and local paths; the next poll tries again.
            LOG.warn("Fetching project {} failed: {}", project, e.getMessage());
        }
    }
}
