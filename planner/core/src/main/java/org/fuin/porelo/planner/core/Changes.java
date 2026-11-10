package org.fuin.porelo.planner.core;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.TimeUnit;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.PlanningStore;
import org.jspecify.annotations.Nullable;

/**
 * One project's changes for the windows subscribed to them: a board with a new revision, found by a
 * poll or made by the planner's own save, reaches each subscription once, and a slow one keeps only
 * the newest.
 */
public final class Changes {

    private final PlanningStore store;

    private final Object fetching = new Object();

    private final Runnable whenIdle;

    private final Set<Subscription> subscriptions = new CopyOnWriteArraySet<>();

    /**
     * Makes the changes of one project.
     *
     * @param store the project's store; a project given anew gets changes of its own
     * @param whenIdle runs once each time the last subscription is closed
     */
    public Changes(final PlanningStore store, final Runnable whenIdle) {
        this.store = store;
        this.whenIdle = whenIdle;
    }

    /**
     * Subscribes to the project's changes.
     *
     * @return a subscription holding the current board
     */
    public Subscription subscribe() {
        synchronized (this) {
            // Under the lock that telling takes, so no board is told between the read and the add.
            final Subscription subscription = new Subscription(store.board());
            subscriptions.add(subscription);
            return subscription;
        }
    }

    /**
     * Fetches the project's repositories and tells a new revision to every subscription.
     *
     * @return the board after the fetch
     * @throws RuntimeException what the store's refresh threw; no subscription is told anything
     */
    public Board poll() {
        // One fetch at a time, so a slow one never tells an older board after a newer one.
        synchronized (fetching) {
            final Board board = store.refresh();
            tell(board);
            return board;
        }
    }

    /**
     * Tells the store's board after the planner's own save to every subscription; read from the
     * store, never handed in, so a save told late never tells a board a poll has replaced.
     */
    public void saved() {
        synchronized (fetching) {
            tell(store.board());
        }
    }

    /** Closes every subscription, as when the project is given anew or the planner stops. */
    public void closeAll() {
        for (final Subscription subscription : subscriptions) {
            subscription.close();
        }
    }

    /**
     * Answers how many subscriptions are open.
     *
     * @return their number
     */
    public int subscribers() {
        return subscriptions.size();
    }

    private synchronized void tell(final Board board) {
        for (final Subscription subscription : subscriptions) {
            subscription.offer(board);
        }
    }

    private void closed(final Subscription subscription) {
        final boolean last;
        synchronized (this) {
            last = subscriptions.remove(subscription) && subscriptions.isEmpty();
        }
        if (last) {
            whenIdle.run();
        }
    }

    /** One window's subscription: the board it started with, then only ever the newest. */
    public final class Subscription implements AutoCloseable {

        private final Board first;

        private @Nullable Board pending;

        private String last;

        private boolean closed;

        private Subscription(final Board first) {
            this.first = first;
            this.last = first.revision().value();
        }

        /**
         * Answers the board the subscription started with.
         *
         * @return the board
         */
        public Board first() {
            return first;
        }

        /**
         * Waits for a board newer than the last one answered.
         *
         * @param wait how long to wait at most
         * @return the newest board, or empty when none came within the wait or the subscription is
         *     closed
         * @throws InterruptedException if the waiting thread is interrupted
         */
        public synchronized Optional<Board> next(final Duration wait) throws InterruptedException {
            final long end = System.nanoTime() + wait.toNanos();
            while (pending == null && !closed) {
                final long left = end - System.nanoTime();
                if (left <= 0) {
                    return Optional.empty();
                }
                TimeUnit.NANOSECONDS.timedWait(this, left);
            }
            final Board board = closed ? null : pending;
            pending = null;
            return Optional.ofNullable(board);
        }

        private synchronized void offer(final Board board) {
            // The revision this subscription already holds is never told again.
            final String revision = board.revision().value();
            if (!closed && !revision.equals(last)) {
                last = revision;
                pending = board;
                notifyAll();
            }
        }

        /**
         * Answers whether the subscription is still open.
         *
         * @return false once it is closed
         */
        public synchronized boolean open() {
            return !closed;
        }

        /** Ends the subscription; it is told nothing more. */
        @Override
        public void close() {
            synchronized (this) {
                if (closed) {
                    return;
                }
                closed = true;
                pending = null;
                notifyAll();
            }
            closed(this);
        }
    }
}
