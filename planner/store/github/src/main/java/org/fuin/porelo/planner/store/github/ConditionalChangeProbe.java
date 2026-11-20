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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
 * a read that failed is asked again rather than answered unchanged.
 */
public final class ConditionalChangeProbe implements ChangeProbe, Pace {

    /** How long all the asks of one look may take together. */
    static final Duration DEADLINE = Duration.ofSeconds(30);

    /** One page of the project's items as last answered: where it starts, its tag, the next. */
    private record PageTag(@Nullable String after, @Nullable String etag, @Nullable String next) {}

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

    private Tags confirmed = Tags.NONE;

    private @Nullable Tags pending;

    private @Nullable Snapshot pendingFor;

    private @Nullable String failed;

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
    public synchronized boolean unchanged(final Snapshot current) {
        final Tags waiting = pending;
        if (waiting != null && pendingFor != current) {
            // A read after the look that saw the change replaced the board, so its tags hold.
            confirmed = waiting;
        }
        pending = null;
        pendingFor = null;
        final ProjectConfig config = current.config();
        final String token = tokens.token(config.forge());
        final Look look;
        try {
            look = look(config, token);
        } catch (final GitHubException e) {
            if (pace.noted(e).rateLimited()) {
                failed = null;
                throw new StoreException.Unreachable(
                        config.own(), Objects.requireNonNull(pace.hold()));
            }
            failed =
                    "GitHub did not answer whether the project changed (HTTP "
                            + e.status()
                            + "); it is read whole at each look";
            return false;
        } catch (final RuntimeException e) {
            // The whole read that follows answers for GitHub; its refusal is the one shown.
            failed =
                    "GitHub did not answer whether the project changed; it is read whole at each"
                            + " look";
            return false;
        }
        failed = null;
        final ConditionalReads.Answer lowest = look.lowest();
        if (lowest != null) {
            pace.seen(lowest.remaining(), lowest.limit());
        }
        if (look.unchanged()) {
            confirmed = look.tags();
            return true;
        }
        pending = look.tags();
        pendingFor = current;
        return false;
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
    public synchronized List<String> faults() {
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

    private Look look(final ProjectConfig config, final String token) {
        final String owner = config.project().owner();
        final Map<String, CompletableFuture<ConditionalReads.Answer>> issues =
                new LinkedHashMap<>();
        for (final String repository : config.repositories()) {
            final String etag = confirmed.issues().get(repository);
            issues.put(
                    repository,
                    CompletableFuture.supplyAsync(
                            () -> reads.issues(token, owner, repository, etag), executor));
        }
        final List<PageTag> before = confirmed.pages();
        final CompletableFuture<List<Paged>> items =
                CompletableFuture.supplyAsync(
                        () -> pages(token, owner, config.project().number(), before), executor);

        final Map<String, @Nullable String> issueTags = new LinkedHashMap<>();
        final List<ConditionalReads.Answer> answers = new ArrayList<>();
        boolean unchanged = true;
        final long end = System.nanoTime() + deadline.toNanos();
        for (final Map.Entry<String, CompletableFuture<ConditionalReads.Answer>> each :
                issues.entrySet()) {
            final ConditionalReads.Answer answer = awaited(each.getValue(), end);
            issueTags.put(each.getKey(), answer.etag());
            answers.add(answer);
            unchanged &= !answer.changed();
        }
        final List<PageTag> pageTags = new ArrayList<>();
        final List<Paged> paged = awaited(items, end);
        for (final Paged page : paged) {
            pageTags.add(page.tag());
            answers.add(page.answer());
            unchanged &= !page.answer().changed();
        }
        unchanged &= pageTags.size() == before.size();
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

    /** One page of items as answered now, with the tag kept for it. */
    private record Paged(PageTag tag, ConditionalReads.Answer answer) {}

    /** Asks each page of the project's items in turn, each with the tag its place had. */
    private List<Paged> pages(
            final String token, final String owner, final int number, final List<PageTag> before) {
        final List<Paged> pages = new ArrayList<>();
        @Nullable String after = null;
        for (int page = 0; page < ItemsBoardReader.MOST_PAGES; page++) {
            final @Nullable String cursor = after;
            final PageTag known =
                    page < before.size() && Objects.equals(before.get(page).after(), cursor)
                            ? before.get(page)
                            : null;
            final ConditionalReads.Answer answer =
                    reads.projectItems(
                            token, owner, number, cursor, known == null ? null : known.etag());
            final @Nullable String next;
            if (answer.changed()) {
                next = answer.next();
            } else if (known != null) {
                next = known.next();
            } else {
                // A 304 to a page never answered says nothing of where the next one starts.
                pages.add(new Paged(new PageTag(cursor, null, null), changed(answer)));
                return pages;
            }
            pages.add(new Paged(new PageTag(cursor, answer.etag(), next), answer));
            if (next == null || next.equals(cursor)) {
                return pages;
            }
            after = next;
        }
        // More pages than a read takes: the whole read refuses the project, so it is read.
        pages.add(
                new Paged(
                        new PageTag(after, null, null),
                        new ConditionalReads.Answer(true, null, null, null, null)));
        return pages;
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
