package org.fuin.porelo.planner.store.github;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.fuin.porelo.forge.github.Batch;
import org.fuin.porelo.forge.github.Delivery;
import org.fuin.porelo.forge.github.GitHubException;
import org.fuin.porelo.forge.github.GitHubGraphQl;
import org.jspecify.annotations.Nullable;

/**
 * Keeps a carry-over's calls under GitHub's limits, so a run of a large project goes on unattended
 * to its end. Making an issue and writing a comment create content, which GitHub allows about 80
 * times a minute and 500 times an hour: each such call stands a second (or the run's pace) after
 * the last and waits while {@link #PER_HOUR} stand in the last hour. Every other write stands a
 * second after the last write, as GitHub asks of mutations; reads are not paced. Any call GitHub
 * refuses as rate limited is made again after a wait, at most {@link #TRIES} times. Every wait
 * longer than {@link #SAID} is said as one line naming the call by its kind, repository and number,
 * never by its text or GitHub's words. One pace serves one run, from one thread.
 */
public final class CarryOverPace {

    /** The content-creating calls let stand in any hour, under GitHub's 500. */
    public static final int PER_HOUR = 450;

    /** The least time between two content-creating calls, under GitHub's 80 a minute. */
    public static final Duration SPACING = Duration.ofSeconds(1);

    /** The most content-creating calls a minute a person may ask for, one each {@link #SPACING}. */
    public static final int MOST_PER_MINUTE = 60;

    /** How often a call refused as rate limited is made in all before its refusal is thrown. */
    public static final int TRIES = 9;

    /** A wait longer than this is said. */
    static final Duration SAID = Duration.ofSeconds(10);

    private static final Duration HOUR = Duration.ofHours(1);

    private static final Duration LEAST_WAIT = Duration.ofMinutes(1);

    /** Waits; a test's moves its clock instead. */
    @FunctionalInterface
    public interface Sleeper {

        /**
         * Waits this long.
         *
         * @param wait how long
         * @throws InterruptedException when the run is stopped while it waits
         */
        void sleep(Duration wait) throws InterruptedException;
    }

    private final InstantSource clock;

    private final Sleeper sleeper;

    private final Consumer<String> lines;

    private final Duration spacing;

    /** When each content-creating call of the last hour was made, the oldest first. */
    private final Deque<Instant> made = new ArrayDeque<>();

    private @Nullable Instant lastMade;

    private @Nullable Instant lastWrite;

    /** What a call does, which sets its pace. */
    enum Kind {
        /** Reads: not paced. */
        READ,
        /** Writes without making content: a second apart from the last write. */
        WRITE,
        /** Makes an issue or a comment: spaced at the run's pace and counted against the hour. */
        CREATE
    }

    /**
     * Makes a pace.
     *
     * @param clock tells the time
     * @param sleeper waits
     * @param lines takes each line said of a wait
     */
    public CarryOverPace(
            final InstantSource clock, final Sleeper sleeper, final Consumer<String> lines) {
        this(clock, sleeper, lines, MOST_PER_MINUTE);
    }

    /**
     * Makes a pace that makes at most this many content-creating calls a minute.
     *
     * @param clock tells the time
     * @param sleeper waits
     * @param lines takes each line said of a wait
     * @param perMinute the content-creating calls a minute, from 1 to {@link #MOST_PER_MINUTE}
     */
    public CarryOverPace(
            final InstantSource clock,
            final Sleeper sleeper,
            final Consumer<String> lines,
            final int perMinute) {
        if (perMinute < 1 || perMinute > MOST_PER_MINUTE) {
            throw new IllegalArgumentException(
                    "the calls a minute are from 1 to " + MOST_PER_MINUTE + ", not " + perMinute);
        }
        this.clock = clock;
        this.sleeper = sleeper;
        this.lines = lines;
        this.spacing = Duration.ofMinutes(1).dividedBy(perMinute);
    }

    /**
     * Makes a call at the pace, again after a refusal as rate limited.
     *
     * @param what the call, by its kind, repository and number
     * @param creates whether it creates content GitHub counts against its limit
     * @param call the call
     * @param <T> its answer
     * @return its answer
     */
    <T> T call(final String what, final Kind kind, final Supplier<T> call) {
        for (int attempt = 1; ; attempt++) {
            if (kind != Kind.READ) {
                spaced(what, kind == Kind.CREATE);
            }
            try {
                return call.get();
            } catch (final GitHubException e) {
                // GitHub refuses a rate-limited call before it runs it, so only an answered refusal
                // is
                // made again; one whose answer was lost may have taken effect, and stops the run.
                if (!e.rateLimited() || e.delivery() != Delivery.ANSWERED || attempt >= TRIES) {
                    throw e;
                }
                final Duration wait = afterRefusal(e.delay(), attempt);
                say(
                        "GitHub refused "
                                + what
                                + " as rate limited; waiting "
                                + spoken(wait)
                                + ", try "
                                + (attempt + 1)
                                + " of "
                                + TRIES
                                + " at "
                                + at(wait));
                sleep(wait);
            }
        }
    }

    private void run(final String what, final Kind kind, final Runnable call) {
        call(
                what,
                kind,
                () -> {
                    call.run();
                    return Boolean.TRUE;
                });
    }

    /** Waits until a write may stand, and counts a content-creating one against the hour. */
    private void spaced(final String what, final boolean creates) {
        Instant now = clock.instant();
        while (!made.isEmpty() && !made.peekFirst().plus(HOUR).isAfter(now)) {
            made.removeFirst();
        }
        Duration wait = Duration.ZERO;
        if (lastWrite != null) {
            wait = later(wait, Duration.between(now, lastWrite.plus(SPACING)));
        }
        if (creates && lastMade != null) {
            wait = later(wait, Duration.between(now, lastMade.plus(spacing)));
        }
        final boolean hourly = creates && made.size() >= PER_HOUR;
        if (hourly) {
            wait = later(wait, Duration.between(now, made.peekFirst().plus(HOUR)));
        }
        if (wait.isPositive()) {
            if (wait.compareTo(SAID) > 0) {
                say(
                        "waiting "
                                + spoken(wait)
                                + " before "
                                + what
                                + (hourly
                                        ? ", as "
                                                + PER_HOUR
                                                + " issues and comments stand in the last hour"
                                        : "")
                                + "; going on at "
                                + at(wait));
            }
            sleep(wait);
            now = clock.instant();
            while (!made.isEmpty() && !made.peekFirst().plus(HOUR).isAfter(now)) {
                made.removeFirst();
            }
        }
        lastWrite = now;
        if (creates) {
            lastMade = now;
            made.addLast(now);
        }
    }

    /**
     * The wait before a call is made again: GitHub's, at least a minute doubled, at most an hour.
     */
    private static Duration afterRefusal(final @Nullable Duration named, final int attempt) {
        final Duration least = LEAST_WAIT.multipliedBy(1L << Math.min(attempt - 1, 6));
        final Duration wait = named == null ? least : later(named, least);
        return wait.compareTo(HOUR) > 0 ? HOUR : wait;
    }

    private static Duration later(final Duration a, final Duration b) {
        return a.compareTo(b) >= 0 ? a : b;
    }

    private String at(final Duration wait) {
        return clock.instant().plus(wait).truncatedTo(ChronoUnit.SECONDS).toString();
    }

    private static String spoken(final Duration wait) {
        final long seconds = Math.max(1, (wait.toMillis() + 999) / 1000);
        return seconds < 120 ? seconds + " s" : ((seconds + 59) / 60) + " min";
    }

    private void say(final String line) {
        lines.accept(line);
    }

    private void sleep(final Duration wait) {
        try {
            sleeper.sleep(wait);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CarryOver.Stopped("The run was stopped while it waited for GitHub");
        }
    }

    /**
     * Wraps the carry-over's writes in this pace.
     *
     * @param writes the writes
     * @return the writes at this pace
     */
    public ProjectWrites writes(final ProjectWrites writes) {
        return new ProjectWrites() {
            @Override
            public GitHubGraphQl.IssueBoard issueBoard(
                    final String token, final List<String> issueIds, final String projectId) {
                return call(
                        "reading " + issueIds.size() + " issue(s)",
                        Kind.READ,
                        () -> writes.issueBoard(token, issueIds, projectId));
            }

            @Override
            public Batch<String> addProjectItems(
                    final String token, final String projectId, final List<String> issueIds) {
                return call(
                        "adding " + issueIds.size() + " item(s) to the project",
                        Kind.WRITE,
                        () -> writes.addProjectItems(token, projectId, issueIds));
            }

            @Override
            public Batch<String> setFieldValues(
                    final String token,
                    final String projectId,
                    final List<GitHubGraphQl.FieldValue> values) {
                return call(
                        "writing " + values.size() + " field value(s)",
                        Kind.WRITE,
                        () -> writes.setFieldValues(token, projectId, values));
            }

            @Override
            public GitHubGraphQl.FieldOptions setFieldOptions(
                    final String token,
                    final String fieldId,
                    final List<GitHubGraphQl.NewOption> options) {
                return call(
                        "setting a field's options",
                        Kind.WRITE,
                        () -> writes.setFieldOptions(token, fieldId, options));
            }

            @Override
            public Created createIssue(
                    final String token,
                    final String owner,
                    final String repository,
                    final String title,
                    final String body,
                    final List<String> labels) {
                return call(
                        "making an issue in " + repository,
                        Kind.CREATE,
                        () -> writes.createIssue(token, owner, repository, title, body, labels));
            }

            @Override
            public void addSubIssue(
                    final String token,
                    final String owner,
                    final String repository,
                    final int number,
                    final long subIssueId) {
                run(
                        "adding a sub-issue to " + repository + "#" + number,
                        Kind.WRITE,
                        () -> writes.addSubIssue(token, owner, repository, number, subIssueId));
            }

            @Override
            public void removeSubIssue(
                    final String token,
                    final String owner,
                    final String repository,
                    final int number,
                    final long subIssueId) {
                run(
                        "removing a sub-issue of " + repository + "#" + number,
                        Kind.WRITE,
                        () -> writes.removeSubIssue(token, owner, repository, number, subIssueId));
            }

            @Override
            public void reprioritizeSubIssue(
                    final String token,
                    final String owner,
                    final String repository,
                    final int number,
                    final long subIssueId,
                    final @Nullable Long afterId,
                    final @Nullable Long beforeId) {
                run(
                        "ordering a sub-issue of " + repository + "#" + number,
                        Kind.WRITE,
                        () ->
                                writes.reprioritizeSubIssue(
                                        token,
                                        owner,
                                        repository,
                                        number,
                                        subIssueId,
                                        afterId,
                                        beforeId));
            }

            @Override
            public void addBlockedBy(
                    final String token,
                    final String owner,
                    final String repository,
                    final int number,
                    final long blockerId) {
                run(
                        "adding a dependency to " + repository + "#" + number,
                        Kind.WRITE,
                        () -> writes.addBlockedBy(token, owner, repository, number, blockerId));
            }

            @Override
            public void removeBlockedBy(
                    final String token,
                    final String owner,
                    final String repository,
                    final int number,
                    final long blockerId) {
                run(
                        "removing a dependency of " + repository + "#" + number,
                        Kind.WRITE,
                        () -> writes.removeBlockedBy(token, owner, repository, number, blockerId));
            }

            @Override
            public FoundPage issuesCreatedSince(
                    final String token,
                    final String owner,
                    final String repository,
                    final java.time.Instant since,
                    final int page) {
                return call(
                        "reading the issues made in " + repository,
                        Kind.READ,
                        () -> writes.issuesCreatedSince(token, owner, repository, since, page));
            }

            @Override
            public void updateIssue(
                    final String token,
                    final String owner,
                    final String repository,
                    final int number,
                    final @Nullable String title,
                    final @Nullable String body,
                    final @Nullable Opening opening) {
                run(
                        "writing " + repository + "#" + number,
                        Kind.WRITE,
                        () ->
                                writes.updateIssue(
                                        token, owner, repository, number, title, body, opening));
            }

            @Override
            public CommentsPage issueComments(
                    final String token,
                    final String owner,
                    final String repository,
                    final int number,
                    final int page) {
                return call(
                        "reading the comments of " + repository + "#" + number,
                        Kind.READ,
                        () -> writes.issueComments(token, owner, repository, number, page));
            }

            @Override
            public void createIssueComment(
                    final String token,
                    final String owner,
                    final String repository,
                    final int number,
                    final String body) {
                run(
                        "commenting on " + repository + "#" + number,
                        Kind.CREATE,
                        () -> writes.createIssueComment(token, owner, repository, number, body));
            }
        };
    }

    /**
     * Wraps the calls only a carry-over makes in this pace.
     *
     * @param calls the calls
     * @return the calls at this pace
     */
    public CarryOverCalls calls(final CarryOverCalls calls) {
        return new CarryOverCalls() {
            @Override
            public String body(
                    final String token,
                    final String owner,
                    final String repository,
                    final int number) {
                return call(
                        "reading the body of " + repository + "#" + number,
                        Kind.READ,
                        () -> calls.body(token, owner, repository, number));
            }

            @Override
            public List<Long> subIssues(
                    final String token,
                    final String owner,
                    final String repository,
                    final int number) {
                return call(
                        "reading the sub-issues of " + repository + "#" + number,
                        Kind.READ,
                        () -> calls.subIssues(token, owner, repository, number));
            }

            @Override
            public List<Long> blockedBy(
                    final String token,
                    final String owner,
                    final String repository,
                    final int number) {
                return call(
                        "reading the dependencies of " + repository + "#" + number,
                        Kind.READ,
                        () -> calls.blockedBy(token, owner, repository, number));
            }
        };
    }

    /**
     * Wraps the project's reads in this pace.
     *
     * @param queries the reads
     * @return the reads at this pace
     */
    public ProjectQueries queries(final ProjectQueries queries) {
        return new ProjectQueries() {
            @Override
            public GitHubGraphQl.ProjectFields projectFields(
                    final String token,
                    final String owner,
                    final int number,
                    final @Nullable String after) {
                return call(
                        "reading the project's fields",
                        Kind.READ,
                        () -> queries.projectFields(token, owner, number, after));
            }

            @Override
            public GitHubGraphQl.ProjectItems projectItems(
                    final String token,
                    final String owner,
                    final int number,
                    final @Nullable String after) {
                return call(
                        "reading the project's items",
                        Kind.READ,
                        () -> queries.projectItems(token, owner, number, after));
            }

            @Override
            public GitHubGraphQl.ProjectItems projectItems(
                    final String token,
                    final String owner,
                    final int number,
                    final @Nullable String after,
                    final int first) {
                return call(
                        "reading the project's items",
                        Kind.READ,
                        () -> queries.projectItems(token, owner, number, after, first));
            }

            @Override
            public GitHubGraphQl.ProjectItems projectItems(
                    final String token,
                    final String owner,
                    final int number,
                    final @Nullable String after,
                    final int first,
                    final GitHubGraphQl.ItemLimits limits) {
                return call(
                        "reading the project's items",
                        Kind.READ,
                        () -> queries.projectItems(token, owner, number, after, first, limits));
            }

            @Override
            public GitHubGraphQl.IssueBoard issueBoard(
                    final String token, final List<String> issueIds, final String projectId) {
                return call(
                        "reading " + issueIds.size() + " issue(s)",
                        Kind.READ,
                        () -> queries.issueBoard(token, issueIds, projectId));
            }
        };
    }
}
