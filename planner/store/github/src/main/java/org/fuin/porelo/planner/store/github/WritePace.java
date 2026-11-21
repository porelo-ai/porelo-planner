package org.fuin.porelo.planner.store.github;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import java.util.function.Supplier;
import org.fuin.porelo.forge.github.Delivery;
import org.fuin.porelo.forge.github.GitHubException;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keeps one save's writes under GitHub's limits on writes made in a burst: the first {@link #BURST}
 * go at once, so a move is written without a wait, and each later one stands {@link #SPACING} after
 * the last, as GitHub asks of many writes. A call that writes the same however often it is made is
 * made again after a failure on GitHub's side, a lost answer or a refusal as rate limited, after
 * the wait GitHub asked for or a backoff, at most {@link #TRIES} times in all. Every failure is
 * logged with GitHub's status and words, scrubbed of the token. One pace serves one save, from one
 * thread.
 */
final class WritePace {

    private static final Logger LOG = LoggerFactory.getLogger(WritePace.class);

    /** The writes of one save that go at once. */
    static final int BURST = 5;

    /** The least time between two later writes of one save. */
    static final Duration SPACING = Duration.ofSeconds(1);

    /** How often a call that may be made again is made in all. */
    static final int TRIES = 3;

    /** The longest wait before a call is made again; a longer one GitHub asks for is refused. */
    static final Duration MOST_WAIT = Duration.ofSeconds(60);

    /** The waits before the second and the third try, when GitHub names none. */
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

    private int made;

    private @Nullable Instant last;

    /**
     * Makes the pace of one save.
     *
     * @param clock tells the time
     * @param sleeper waits
     * @param token the person's token, scrubbed from every line logged
     */
    WritePace(final InstantSource clock, final Sleeper sleeper, final String token) {
        this.clock = clock;
        this.sleeper = sleeper;
        this.token = token;
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
        spaced();
        try {
            return call.get();
        } catch (final GitHubException e) {
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
        for (int attempt = 1; ; attempt++) {
            spaced();
            try {
                return call.get();
            } catch (final GitHubException e) {
                final @Nullable Duration wait = attempt < TRIES ? waitBefore(e, attempt) : null;
                logged(what, e, attempt, wait != null);
                if (wait == null) {
                    throw e;
                }
                sleep(wait, e);
            }
        }
    }

    /** The wait before the next try, or null when the failure does not pass. */
    private static @Nullable Duration waitBefore(final GitHubException e, final int attempt) {
        final boolean limited = e.rateLimited() && e.delivery() == Delivery.ANSWERED;
        final boolean passing = e.status() == 0 || e.status() >= 500;
        if (!limited && !passing) {
            return null;
        }
        final Duration asked = e.delay();
        if (asked != null && asked.isPositive()) {
            return asked.compareTo(MOST_WAIT) <= 0 ? asked : null;
        }
        return limited && !passing ? BACKOFF.get(BACKOFF.size() - 1) : BACKOFF.get(attempt - 1);
    }

    private void logged(
            final String what, final GitHubException e, final int attempt, final boolean again) {
        final Duration asked = e.delay();
        LOG.warn(
                "GitHub's answer to {} (try {} of {}): status {}, {}, rate limited {}, wait asked"
                        + " {}; {}: {}",
                what,
                attempt,
                TRIES,
                e.status(),
                e.delivery(),
                e.rateLimited(),
                asked == null ? "none" : asked.toMillis() + " ms",
                again ? "asked again" : "not asked again",
                Scrubbed.text(String.valueOf(e.getMessage()), List.of(token)));
    }

    private void spaced() {
        made++;
        final Instant now = clock.instant();
        final Instant previous = last;
        if (made > BURST && previous != null) {
            final Duration since = Duration.between(previous, now);
            if (since.compareTo(SPACING) < 0) {
                sleep(SPACING.minus(since), null);
            }
        }
        last = clock.instant();
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
