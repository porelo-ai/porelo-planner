package org.fuin.porelo.planner.store.github;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.fuin.porelo.forge.github.GitHubException;
import org.fuin.porelo.forge.github.GitHubGraphQl;
import org.jspecify.annotations.Nullable;

/**
 * The pace of one person's token on GitHub, shared by every project read with it, since GitHub
 * counts the rate limit per person: a spent limit holds every look until GitHub's wait ends, a low
 * one lengthens the interval.
 */
public final class GitHubPace implements Pace {

    /** Below this share of the limit left, the interval is lengthened. */
    static final long LOW_SHARE = 10;

    /** How much longer the interval is while the limit is low. */
    static final int LENGTHENED = 10;

    /** The wait when GitHub names none. */
    static final Duration WITHOUT_A_WORD = Duration.ofMinutes(1);

    /** The longest wait taken, GitHub's rate-limit window, whatever an answer names. */
    static final Duration MOST_WAIT = Duration.ofHours(1);

    private final InstantSource clock;

    private @Nullable Instant heldUntil;

    private @Nullable Long remaining;

    private @Nullable Long limit;

    /**
     * Makes the pace.
     *
     * @param clock tells the time a hold ends by
     */
    public GitHubPace(final InstantSource clock) {
        this.clock = clock;
    }

    /**
     * Holds every look until GitHub's wait ends.
     *
     * @param wait the wait GitHub named, or null when it named none
     */
    public synchronized void spent(final @Nullable Duration wait) {
        spentAt(clock.instant(), wait);
    }

    /**
     * Holds every look until GitHub's wait ends and answers the words of the hold, read at the same
     * instant, so a refusal is never answered without them.
     *
     * @param wait the wait GitHub named, or null when it named none
     * @return the words of the hold
     */
    synchronized String refused(final @Nullable Duration wait) {
        final Instant now = clock.instant();
        return words(Duration.between(now, spentAt(now, wait)));
    }

    /** Holds until the wait from now ends, and answers when the hold ends. */
    private Instant spentAt(final Instant now, final @Nullable Duration wait) {
        // A wait of nothing is no wait GitHub named, and an endless one would hold for good.
        final Duration named =
                wait == null || wait.isNegative() || wait.isZero() ? WITHOUT_A_WORD : wait;
        final Instant until = now.plus(named.compareTo(MOST_WAIT) > 0 ? MOST_WAIT : named);
        final Instant before = heldUntil;
        if (before == null || until.isAfter(before)) {
            heldUntil = until;
            return until;
        }
        return before;
    }

    /**
     * Notes what an answer said of the rate limit.
     *
     * @param left the requests left, or null when the answer did not say
     * @param of the requests allowed in the window, or null when the answer did not say
     */
    public synchronized void seen(final @Nullable Long left, final @Nullable Long of) {
        if (left != null && of != null && of > 0) {
            remaining = left;
            limit = of;
            if (left > 0) {
                // GitHub answers again, so its wait ended, as with a fresh token.
                heldUntil = null;
            }
        }
    }

    @Override
    public synchronized @Nullable String hold() {
        final Duration left = heldFor();
        return left == null ? null : words(left);
    }

    private static String words(final Duration left) {
        final long whole = left.toSeconds();
        return "GitHub's rate limit is spent; it reads again in "
                + (left.toNanosPart() > 0 ? whole + 1 : whole)
                + " s";
    }

    @Override
    public synchronized List<String> faults() {
        final List<String> faults = new ArrayList<>();
        final Instant until = heldUntil;
        if (heldFor() != null && until != null) {
            faults.add(
                    "GitHub's rate limit is spent; the board shown is the one read before, and"
                            + " GitHub is read again at "
                            + until.truncatedTo(ChronoUnit.SECONDS)
                            + " UTC");
        } else if (low()) {
            faults.add(
                    "GitHub's rate limit is low ("
                            + remaining
                            + " of "
                            + limit
                            + " left); GitHub is asked for changes less often until it recovers");
        }
        return faults;
    }

    @Override
    public synchronized Duration delay(final Duration probed) {
        final Duration left = heldFor();
        if (left != null) {
            return left;
        }
        return low() ? probed.multipliedBy(LENGTHENED) : probed;
    }

    /**
     * Wraps a project's reads so a spent rate limit they meet holds every look.
     *
     * @param queries the reads
     * @return the same reads, noting a spent rate limit
     */
    public ProjectQueries watching(final ProjectQueries queries) {
        return new ProjectQueries() {
            @Override
            public GitHubGraphQl.ProjectFields projectFields(
                    final String token,
                    final String owner,
                    final int number,
                    final @Nullable String after) {
                try {
                    return queries.projectFields(token, owner, number, after);
                } catch (final GitHubException e) {
                    throw noted(e);
                }
            }

            @Override
            public GitHubGraphQl.ProjectItems projectItems(
                    final String token,
                    final String owner,
                    final int number,
                    final @Nullable String after) {
                try {
                    return queries.projectItems(token, owner, number, after);
                } catch (final GitHubException e) {
                    throw noted(e);
                }
            }

            @Override
            public GitHubGraphQl.ProjectItems projectItems(
                    final String token,
                    final String owner,
                    final int number,
                    final @Nullable String after,
                    final int first) {
                try {
                    return queries.projectItems(token, owner, number, after, first);
                } catch (final GitHubException e) {
                    throw noted(e);
                }
            }

            @Override
            public GitHubGraphQl.IssueBoard issueBoard(
                    final String token, final List<String> issueIds, final String projectId) {
                try {
                    return queries.issueBoard(token, issueIds, projectId);
                } catch (final GitHubException e) {
                    throw noted(e);
                }
            }
        };
    }

    /**
     * Notes a spent rate limit a failure names and hands the failure back.
     *
     * @param failure what GitHub answered
     * @return the same failure
     */
    GitHubException noted(final GitHubException failure) {
        if (failure.rateLimited()) {
            spent(failure.delay());
        }
        return failure;
    }

    /** The time left of the hold, or null when none holds. */
    private @Nullable Duration heldFor() {
        final Instant until = heldUntil;
        if (until == null) {
            return null;
        }
        final Duration left = Duration.between(clock.instant(), until);
        if (left.isNegative() || left.isZero()) {
            heldUntil = null;
            return null;
        }
        return left;
    }

    private boolean low() {
        final Long left = remaining;
        final Long of = limit;
        return left != null && of != null && left * LOW_SHARE < of;
    }
}
