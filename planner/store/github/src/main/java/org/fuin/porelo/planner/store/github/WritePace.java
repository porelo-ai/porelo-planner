package org.fuin.porelo.planner.store.github;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import java.util.function.Supplier;
import org.fuin.porelo.forge.github.Delivery;
import org.fuin.porelo.forge.github.GitHubException;
import org.fuin.porelo.wire.LimitFactors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keeps the writes of every save of one project with one token under GitHub's limits on writes made
 * in a burst: the first {@link #BURST} go at once, so a move is written without a wait, and each
 * later one stands {@link #SPACING} after the last, whichever save makes it, the burst filling
 * again by one for each {@link #SPACING} the pace stands idle. A call that writes the same however
 * often it is made is made again after a failure on GitHub's side, a lost answer or a refusal as
 * rate limited, after the wait GitHub asked for or a backoff, at most {@link #TRIES} times in all;
 * the tries, the backoff and the longest wait follow the factor {@code retries}. A wait GitHub asks
 * for holds every save's next write. Every failure is logged with GitHub's status and words,
 * scrubbed of the token. Saves on many threads share the pace: each takes its turn in memory and
 * waits for it outside, so no save waits for another save's call to GitHub.
 */
final class WritePace {

    private static final Logger LOG = LoggerFactory.getLogger(WritePace.class);

    /** The writes that go at once after the pace stood idle. */
    static final int BURST = 5;

    /** The least time between two later writes, whichever save makes them. */
    static final Duration SPACING = Duration.ofSeconds(1);

    /** How often a call that may be made again is made in all, before its factor. */
    static final int TRIES = 3;

    /**
     * The longest wait before a call is made again, before its factor; a longer one GitHub asks for
     * is refused.
     */
    static final Duration MOST_WAIT = Duration.ofSeconds(60);

    /**
     * The waits before the second and the third try, when GitHub names none, before their factor;
     * each later try waits as long as the last.
     */
    private static final List<Duration> BACKOFF =
            List.of(Duration.ofSeconds(2), Duration.ofSeconds(5));

    /** Waits; a test's waits nothing. */
    @FunctionalInterface
    interface Sleeper {
        /**
         * Waits this long.
         *
         * @param wait how long
         * @throws InterruptedException when the save is stopped while it waits
         */
        void sleep(Duration wait) throws InterruptedException;
    }

    private final InstantSource clock;

    private final Sleeper sleeper;

    private final String token;

    /** How often a call that may be made again is made in all. */
    private final int tries;

    /** The longest wait before a call is made again. */
    private final Duration mostWait;

    /** The waits before each try after the first, the last standing for every later one. */
    private final List<Duration> backoff;

    /**
     * The turn the next write would take once the burst is spent, or null before the first; a turn
     * up to {@link #BURST} less one spacings before it is taken at once.
     */
    private @Nullable Instant next;

    /** Until when GitHub asked every write to wait, or null when it asked for no wait. */
    private @Nullable Instant held;

    /**
     * Makes the pace of a project's saves with one token.
     *
     * @param clock tells the time
     * @param sleeper waits
     * @param token the person's token, scrubbed from every line logged
     * @param limits the factors the tries and their waits follow
     */
    WritePace(
            final InstantSource clock,
            final Sleeper sleeper,
            final String token,
            final LimitFactors limits) {
        this.clock = clock;
        this.sleeper = sleeper;
        this.token = token;
        this.tries = limits.tries(TRIES);
        this.mostWait = limits.retryPause(MOST_WAIT);
        this.backoff = BACKOFF.stream().map(limits::retryPause).toList();
    }

    /**
     * Answers whether the pace serves the writes made with a token.
     *
     * @param other the token
     * @return whether it is the pace's own
     */
    boolean serves(final String other) {
        return token.equals(other);
    }

    /**
     * Makes a write at the pace, once.
     *
     * @param what the call, by its kind and the issue or project it writes
     * @param call the call
     * @param <T> its answer
     * @return its answer
     */
    <T> T once(final String what, final Supplier<T> call) {
        spaced(null);
        try {
            return call.get();
        } catch (final GitHubException e) {
            heard(e);
            logged(what, e, 1, false);
            throw e;
        }
    }

    /**
     * Makes a write that writes the same however often it is made, at the pace, and again after a
     * failure that may pass.
     *
     * @param what the call, by its kind and the issue or project it writes
     * @param call the call
     * @param <T> its answer
     * @return its answer
     */
    <T> T again(final String what, final Supplier<T> call) {
        @Nullable GitHubException failed = null;
        for (int attempt = 1; ; attempt++) {
            spaced(failed);
            try {
                return call.get();
            } catch (final GitHubException e) {
                final boolean asked = heard(e);
                final @Nullable Duration wait = attempt < tries ? waitBefore(e, attempt) : null;
                logged(what, e, attempt, wait != null);
                if (wait == null) {
                    throw e;
                }
                if (!asked) {
                    sleep(wait, e);
                }
                failed = e;
            }
        }
    }

    /**
     * Holds every save's next write for the wait GitHub asked for with a refusal as rate limited,
     * when that wait is one a write is made again after.
     *
     * @return whether the pace now holds for it, so the next write waits it out in its turn
     */
    private boolean heard(final GitHubException e) {
        final Duration asked = e.delay();
        if (!e.rateLimited()
                || e.delivery() != Delivery.ANSWERED
                || asked == null
                || !asked.isPositive()
                || asked.compareTo(mostWait) > 0) {
            return false;
        }
        synchronized (this) {
            final Instant until = clock.instant().plus(asked);
            final Instant before = held;
            if (before == null || until.isAfter(before)) {
                held = until;
            }
        }
        return true;
    }

    /** The wait before the next try, or null when the failure does not pass. */
    private @Nullable Duration waitBefore(final GitHubException e, final int attempt) {
        final boolean limited = e.rateLimited() && e.delivery() == Delivery.ANSWERED;
        final boolean passing = e.status() == 0 || e.status() >= 500;
        if (!limited && !passing) {
            return null;
        }
        final Duration asked = e.delay();
        if (asked != null && asked.isPositive()) {
            return asked.compareTo(mostWait) <= 0 ? asked : null;
        }
        final Duration last = backoff.getLast();
        return (limited && !passing) || attempt > backoff.size() ? last : backoff.get(attempt - 1);
    }

    private void logged(
            final String what, final GitHubException e, final int attempt, final boolean again) {
        final Duration asked = e.delay();
        LOG.warn(
                "GitHub's answer to {} (try {} of {}): status {}, {}, rate limited {}, wait asked"
                        + " {}; {}: {}",
                what,
                attempt,
                tries,
                e.status(),
                e.delivery(),
                e.rateLimited(),
                asked == null ? "none" : asked.toMillis() + " ms",
                again ? "asked again" : "not asked again",
                Scrubbed.text(String.valueOf(e.getMessage()), List.of(token)));
    }

    /** Takes the next write's turn and waits for it, outside the pace's own bookkeeping. */
    private void spaced(final @Nullable GitHubException failed) {
        final Duration wait = turn();
        if (wait.isPositive()) {
            sleep(wait, failed);
        }
    }

    /**
     * Takes the next turn: at once while the burst lasts, else a spacing after the turn before, and
     * never before a wait GitHub asked for ends.
     *
     * @return how long the write waits for its turn
     */
    private synchronized Duration turn() {
        final Instant now = clock.instant();
        final Instant due = next == null || next.isBefore(now) ? now : next;
        Instant at = due.minus(SPACING.multipliedBy(BURST - 1L));
        if (at.isBefore(now)) {
            at = now;
        }
        final Instant until = held;
        if (until != null && until.isAfter(at)) {
            at = until;
        }
        next = (at.isAfter(due) ? at : due).plus(SPACING);
        return Duration.between(now, at);
    }

    private void sleep(final Duration wait, final @Nullable GitHubException failure) {
        try {
            sleeper.sleep(wait);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            if (failure != null) {
                throw failure;
            }
            throw new IllegalStateException("the save was stopped while it waited for GitHub", e);
        }
    }
}
