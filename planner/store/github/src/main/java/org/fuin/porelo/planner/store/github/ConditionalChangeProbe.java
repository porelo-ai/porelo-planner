package org.fuin.porelo.planner.store.github;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.fuin.porelo.forge.github.GitHubException;
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.planner.store.TokenSource;
import org.jspecify.annotations.Nullable;

/**
 * Asks GitHub, with the entity tags of the last answers and in parallel, whether any issue of the
 * project's repositories or any page of its items changed; only when one did is the whole project
 * read again. A 304 costs no rate limit.
 *
 * <p>The tags of an answer that saw a change count only once a read after it replaced the board, so
 * a read that failed is asked again rather than answered unchanged. The lock covers only what the
 * probe keeps, never an ask to GitHub, so the board's faults are never kept waiting by a look.
 */
public final class ConditionalChangeProbe implements ChangeProbe, Pace {

    /** How long all the asks of one look may take together. */
    static final Duration DEADLINE = Duration.ofSeconds(30);

    /** The most asks of one look in flight at once, so GitHub sees no burst. */
    static final int PARALLEL = 4;

    /**
     * One page of the project's items as last answered: where it starts, its tag, the next, and
     * whether it held a whole page of items.
     */
    private record PageTag(
            @Nullable String after, @Nullable String etag, @Nullable String next, boolean full) {}

    /** The tags of one look: each repository's issues and each page of the project's items. */
    private record Tags(Map<String, @Nullable String> issues, List<PageTag> pages) {

        static final Tags NONE = new Tags(Map.of(), List.of());
    }

    /** One look's answers: their tags, whether all were 304, the lowest rate limit seen. */
    private record Look(Tags tags, boolean unchanged, ConditionalReads.@Nullable Answer lowest) {}

    private final ConditionalReads reads;

    private final TokenSource tokens;

    private final GitHubPace pace;

    private final Executor executor;

    private final Duration deadline;

    private final Semaphore inFlight = new Semaphore(PARALLEL);

    private Tags confirmed = Tags.NONE;

    private @Nullable Tags pending;

    private @Nullable Snapshot pendingFor;

    /** Counts what was forgotten, so a look that began before keeps none of its tags. */
    private long era;

    private volatile @Nullable String failed;

    /**
     * Makes the probe of one project.
     *
     * @param reads the conditional reads
     * @param tokens the person's token, asked for once a look
     * @param pace the pace of the person's token, told what the answers say of the rate limit
     * @param executor runs the asks side by side
     */
    public ConditionalChangeProbe(
            final ConditionalReads reads,
            final TokenSource tokens,
            final GitHubPace pace,
            final Executor executor) {
        this(reads, tokens, pace, executor, DEADLINE);
    }

    /** Makes the probe with its own deadline. */
    ConditionalChangeProbe(
            final ConditionalReads reads,
            final TokenSource tokens,
            final GitHubPace pace,
            final Executor executor,
            final Duration deadline) {
        this.reads = reads;
        this.tokens = tokens;
        this.pace = pace;
        this.executor = executor;
        this.deadline = deadline;
    }

    @Override
    public boolean unchanged(final Snapshot current) {
        final Tags known;
        final long began;
        synchronized (this) {
            final Tags waiting = pending;
            if (waiting != null && pendingFor != current) {
                // A read after the look that saw the change replaced the board, so its tags hold.
                confirmed = waiting;
            }
            pending = null;
            pendingFor = null;
            known = confirmed;
            began = era;
        }
        final ProjectConfig config = current.config();
        final String token = tokens.token(config.forge());
        final Look look;
        try {
            look = look(config, token, known);
        } catch (final StoreException.Unreachable e) {
            failed = null;
            throw e;
        } catch (final GitHubException e) {
            failed =
                    "GitHub did not answer whether the project changed (HTTP "
                            + e.status()
                            + "); it is read whole at each look";
            return changedUnlessHeld(config);
        } catch (final RuntimeException e) {
            // The whole read that follows answers for GitHub; its refusal is the one shown.
            failed =
                    "GitHub did not answer whether the project changed; it is read whole at each"
                            + " look";
            return changedUnlessHeld(config);
        }
        failed = null;
        final ConditionalReads.Answer lowest = look.lowest();
        if (lowest != null) {
            pace.seen(lowest.remaining(), lowest.limit());
        }
        if (look.unchanged()) {
            synchronized (this) {
                if (era == began) {
                    confirmed = look.tags();
                }
            }
            return true;
        }
        changedUnlessHeld(config);
        synchronized (this) {
            if (era == began) {
                pending = look.tags();
                pendingFor = current;
            }
        }
        return false;
    }

    @Override
    public synchronized void forget() {
        confirmed = Tags.NONE;
        pending = null;
        pendingFor = null;
        era++;
    }

    @Override
    public Pace pace() {
        return this;
    }

    @Override
    public @Nullable String hold() {
        return pace.hold();
    }

    @Override
    public List<String> faults() {
        final String words = failed;
        final List<String> faults = new ArrayList<>(pace.faults());
        if (words != null) {
            faults.add(words);
        }
        return faults;
    }

    @Override
    public Duration delay(final Duration probed) {
        return pace.delay(probed);
    }

    /**
     * Answers that the project is to be read whole, unless the pace holds: a whole read is never
     * sent while GitHub's rate limit is spent, whichever project found it so.
     */
    private boolean changedUnlessHeld(final ProjectConfig config) {
        final String hold = pace.hold();
        if (hold != null) {
            throw new StoreException.Unreachable(config.own(), hold);
        }
        return false;
    }

    private Look look(final ProjectConfig config, final String token, final Tags known) {
        final String owner = config.project().owner();
        final long end = System.nanoTime() + deadline.toNanos();
        final AtomicBoolean over = new AtomicBoolean();
        final Map<String, CompletableFuture<ConditionalReads.Answer>> issues =
                new LinkedHashMap<>();
        for (final String repository : config.repositories()) {
            final String etag = known.issues().get(repository);
            issues.put(
                    repository,
                    CompletableFuture.supplyAsync(
                            () ->
                                    bounded(
                                            () -> reads.issues(token, owner, repository, etag),
                                            end,
                                            over),
                            executor));
        }
        final List<PageTag> before = known.pages();
        final CompletableFuture<List<Paged>> items =
                CompletableFuture.supplyAsync(
                        () -> pages(token, owner, config.project().number(), before, end, over),
                        executor);
        try {
            // Every ask is awaited before any is judged, so a spent rate limit any of them met
            // holds the pace, whatever failed before it.
            final Map<String, ConditionalReads.Answer> issueAnswers = new LinkedHashMap<>();
            final List<RuntimeException> failures = new ArrayList<>();
            for (final Map.Entry<String, CompletableFuture<ConditionalReads.Answer>> each :
                    issues.entrySet()) {
                try {
                    issueAnswers.put(each.getKey(), awaited(each.getValue(), end));
                } catch (final RuntimeException e) {
                    failures.add(e);
                }
            }
            List<Paged> paged = List.of();
            try {
                paged = awaited(items, end);
            } catch (final RuntimeException e) {
                failures.add(e);
            }
            for (final RuntimeException failure : failures) {
                if (failure instanceof GitHubException refused && refused.rateLimited()) {
                    throw new StoreException.Unreachable(
                            config.own(), pace.refused(refused.delay()));
                }
            }
            if (!failures.isEmpty()) {
                throw failures.get(0);
            }
            return judged(known, issueAnswers, paged);
        } finally {
            // No ask outlives its look: the pages stop, and what has not begun never does.
            over.set(true);
            issues.values().forEach(future -> future.cancel(true));
            items.cancel(true);
        }
    }

    private static Look judged(
            final Tags known,
            final Map<String, ConditionalReads.Answer> issueAnswers,
            final List<Paged> paged) {
        final Map<String, @Nullable String> issueTags = new LinkedHashMap<>();
        final List<ConditionalReads.Answer> answers = new ArrayList<>();
        boolean unchanged = true;
        for (final Map.Entry<String, ConditionalReads.Answer> each : issueAnswers.entrySet()) {
            final ConditionalReads.Answer answer = each.getValue();
            issueTags.put(each.getKey(), kept(answer, known.issues().get(each.getKey())));
            answers.add(answer);
            unchanged &= !answer.changed();
        }
        final List<PageTag> pageTags = new ArrayList<>();
        for (final Paged page : paged) {
            pageTags.add(page.tag());
            answers.add(page.answer());
            unchanged &= !page.answer().changed();
        }
        unchanged &= pageTags.size() == known.pages().size();
        ConditionalReads.@Nullable Answer lowest = null;
        for (final ConditionalReads.Answer answer : answers) {
            if (answer.remaining() != null
                    && answer.limit() != null
                    && (lowest == null || share(answer) < share(Objects.requireNonNull(lowest)))) {
                lowest = answer;
            }
        }
        return new Look(new Tags(issueTags, pageTags), unchanged, lowest);
    }

    /** The tag to send next time: a 304 that names none keeps the one sent. */
    private static @Nullable String kept(
            final ConditionalReads.Answer answer, final @Nullable String sent) {
        return !answer.changed() && answer.etag() == null ? sent : answer.etag();
    }

    /** One page of items as answered now, with the tag kept for it. */
    private record Paged(PageTag tag, ConditionalReads.Answer answer) {}

    /** Asks each page of the project's items in turn, each with the tag its place had. */
    private List<Paged> pages(
            final String token,
            final String owner,
            final int number,
            final List<PageTag> before,
            final long end,
            final AtomicBoolean over) {
        final List<Paged> pages = new ArrayList<>();
        @Nullable String after = null;
        for (int page = 0; page < ItemsBoardReader.MOST_PAGES; page++) {
            final @Nullable String cursor = after;
            final PageTag known =
                    page < before.size() && Objects.equals(before.get(page).after(), cursor)
                            ? before.get(page)
                            : null;
            // A full last page may have a page after it that its own tag never shows, so it is
            // asked whole: the same tag and no next page is no change.
            final boolean whole = known != null && known.next() == null && known.full();
            final String sent = known == null || whole ? null : known.etag();
            final ConditionalReads.Answer asked =
                    bounded(
                            () -> reads.projectItems(token, owner, number, cursor, sent),
                            end,
                            over);
            final ConditionalReads.Answer answer =
                    whole && asked.next() == null && sameTag(asked.etag(), known)
                            ? new ConditionalReads.Answer(
                                    false,
                                    asked.etag(),
                                    null,
                                    asked.remaining(),
                                    asked.limit(),
                                    true)
                            : asked;
            final @Nullable String next;
            final boolean full;
            if (answer.changed()) {
                next = answer.next();
                full = answer.full();
            } else if (known != null) {
                next = known.next();
                full = known.full();
            } else {
                // A 304 to a page never answered says nothing of where the next one starts.
                pages.add(new Paged(new PageTag(cursor, null, null, false), changed(answer)));
                return pages;
            }
            pages.add(
                    new Paged(
                            new PageTag(
                                    cursor,
                                    kept(answer, known == null ? null : known.etag()),
                                    next,
                                    full),
                            answer));
            if (next == null || next.equals(cursor)) {
                return pages;
            }
            after = next;
        }
        // More pages than a read takes: the whole read refuses the project, so it is read.
        pages.add(
                new Paged(
                        new PageTag(after, null, null, false),
                        new ConditionalReads.Answer(true, null, null, null, null)));
        return pages;
    }

    private static boolean sameTag(final @Nullable String etag, final @Nullable PageTag known) {
        return etag != null && known != null && etag.equals(known.etag());
    }

    /**
     * Runs one ask once fewer than {@link #PARALLEL} are in flight, unless the look is over or its
     * deadline passed.
     */
    private <T> T bounded(final Supplier<T> ask, final long end, final AtomicBoolean over) {
        try {
            if (over.get()
                    || !inFlight.tryAcquire(
                            Math.max(0, end - System.nanoTime()), TimeUnit.NANOSECONDS)) {
                throw new IllegalStateException("GitHub did not answer the change check in time");
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("The change check was interrupted", e);
        }
        try {
            if (over.get() || end - System.nanoTime() <= 0) {
                throw new IllegalStateException("GitHub did not answer the change check in time");
            }
            return ask.get();
        } finally {
            inFlight.release();
        }
    }

    private static ConditionalReads.Answer changed(final ConditionalReads.Answer answer) {
        return new ConditionalReads.Answer(true, null, null, answer.remaining(), answer.limit());
    }

    private static double share(final ConditionalReads.Answer answer) {
        return (double) Objects.requireNonNull(answer.remaining())
                / Math.max(1L, Objects.requireNonNull(answer.limit()));
    }

    private static <T> T awaited(final CompletableFuture<T> future, final long end) {
        try {
            return future.get(Math.max(0, end - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("The change check was interrupted", e);
        } catch (final TimeoutException e) {
            future.cancel(true);
            throw new IllegalStateException("GitHub did not answer the change check in time", e);
        } catch (final ExecutionException e) {
            final Throwable cause = e.getCause();
            if (cause instanceof RuntimeException failure) {
                throw failure;
            }
            throw new CompletionException(cause);
        }
    }
}
