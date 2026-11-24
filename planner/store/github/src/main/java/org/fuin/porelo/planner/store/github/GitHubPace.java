package org.fuin.porelo.planner.store.github;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.fuin.porelo.forge.github.Conditional;
import org.fuin.porelo.forge.github.GitHubException;
import org.fuin.porelo.forge.github.GitHubGraphQl;
import org.fuin.porelo.forge.github.RateLimit;
import org.fuin.porelo.wire.LimitFactors;
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

    /** The wait when GitHub names none, before the factor {@code retries}. */
    static final Duration WITHOUT_A_WORD = Duration.ofMinutes(1);

    /** The longest wait taken, GitHub's rate-limit window, whatever an answer names. */
    static final Duration MOST_WAIT = Duration.ofHours(1);

    private final InstantSource clock;

    /** The wait when GitHub names none. */
    private final Duration withoutAWord;

    private @Nullable Instant heldUntil;

    private @Nullable Long remaining;

    private @Nullable Long limit;

    /**
     * Makes the pace.
     *
     * @param clock tells the time a hold ends by
     */
    public GitHubPace(final InstantSource clock) {
        this(clock, LimitFactors.DEFAULT);
    }

    /**
     * Makes the pace whose wait, when GitHub names none, follows the factor {@code retries}.
     *
     * @param clock tells the time a hold ends by
     * @param limits the factors
     */
    public GitHubPace(final InstantSource clock, final LimitFactors limits) {
        this.clock = clock;
        this.withoutAWord = limits.retryPause(WITHOUT_A_WORD);
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
                wait == null || wait.isNegative() || wait.isZero() ? withoutAWord : wait;
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
     * Wraps a project's reads that never wait for GitHub's rate limit, such as the look for
     * changes, so a spent rate limit they meet holds every look, and an answer ends the hold.
     *
     * @param queries the reads
     * @return the same reads, noting a spent rate limit
     */
    public ProjectQueries watching(final ProjectQueries queries) {
        return paced(queries, true);
    }

    /**
     * Wraps a board reader's reads so an answer ends the hold; a rate limit they meet holds no look
     * here, since the reader may wait it out and go on, and holds through {@link #spent} only when
     * its read fails on it.
     *
     * @param queries the reads
     * @return the same reads, ending the hold at an answer
     */
    public ProjectQueries answering(final ProjectQueries queries) {
        return paced(queries, false);
    }

    private ProjectQueries paced(final ProjectQueries queries, final boolean notes) {
        return new ProjectQueries() {
            @Override
            public GitHubGraphQl.ProjectFields projectFields(
                    final String token,
                    final String owner,
                    final int number,
                    final @Nullable String after) {
                return read(() -> queries.projectFields(token, owner, number, after), notes);
            }

            @Override
            public GitHubGraphQl.ProjectItems projectItems(
                    final String token,
                    final String owner,
                    final int number,
                    final @Nullable String after) {
                return read(() -> queries.projectItems(token, owner, number, after), notes);
            }

            @Override
            public GitHubGraphQl.ProjectItems projectItems(
                    final String token,
                    final String owner,
                    final int number,
                    final @Nullable String after,
                    final int first) {
                return read(() -> queries.projectItems(token, owner, number, after, first), notes);
            }

            @Override
            public GitHubGraphQl.ProjectItems projectItems(
                    final String token,
                    final String owner,
                    final int number,
                    final @Nullable String after,
                    final int first,
                    final GitHubGraphQl.ItemLimits limits) {
                return read(
                        () -> queries.projectItems(token, owner, number, after, first, limits),
                        notes);
            }

            @Override
            public GitHubGraphQl.IssueBoard issueBoard(
                    final String token, final List<String> issueIds, final String projectId) {
                return read(() -> queries.issueBoard(token, issueIds, projectId), notes);
            }
        };
    }

    /**
     * Wraps a project's REST item list for the look for changes, which never waits for GitHub's
     * rate limit, so a spent rate limit it meets holds every look, the limit each answer names
     * paces the looks, and an answer ends the hold.
     *
     * @param pages the item list
     * @return the same list, noting the rate limit
     */
    public GitHubChanges.ItemPages watching(final GitHubChanges.ItemPages pages) {
        return (token, owner, number, after, perPage, fieldIds, etag) ->
                limited(
                        read(
                                () ->
                                        pages.page(
                                                token, owner, number, after, perPage, fieldIds,
                                                etag),
                                true));
    }

    /**
     * Wraps a board reader's read of the organization's issue fields so an answer ends the hold;
     * the reader holds a rate limit it fails on.
     *
     * @param fields the read
     * @return the same read, ending the hold at an answer
     */
    public ItemsBoardReader.IssueFields answering(final ItemsBoardReader.IssueFields fields) {
        return (token, owner) -> read(() -> fields.read(token, owner), false);
    }

    /**
     * Wraps a board reader's pages of a repository's open issues so the limit each answer names
     * paces the looks and an answer ends the hold; the reader holds a rate limit it fails on.
     *
     * @param pages the pages
     * @return the same pages, noting the rate limit each answer names
     */
    public RepositoryIssues.Pages answering(final RepositoryIssues.Pages pages) {
        return (token, owner, repository, page, etag) ->
                limited(read(() -> pages.page(token, owner, repository, page, etag), false));
    }

    /** Notes the rate limit an answer names, as the looks are paced by it. */
    private <T> Conditional<T> limited(final Conditional<T> answer) {
        final RateLimit limit = answer.rateLimit();
        if (limit != null) {
            seen(limit.remaining(), limit.limit());
        }
        return answer;
    }

    /**
     * Makes a read: an answer ends the hold, since GitHub answers again; a spent rate limit it
     * meets holds every look when the read notes it.
     */
    private <T> T read(final java.util.function.Supplier<T> read, final boolean notes) {
        final T answer;
        try {
            answer = read.get();
        } catch (final GitHubException e) {
            if (notes && e.rateLimited()) {
                spent(e.delay());
            }
            throw e;
        }
        synchronized (this) {
            heldUntil = null;
        }
        return answer;
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
